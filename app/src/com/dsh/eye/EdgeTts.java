package com.dsh.eye;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 小轩（`zh-CN-XiaoxuanNeural`）—— 直接用微软 Edge 的朗读接口合成 MP3。
 *
 * ## 为什么不是"调个 API"
 *
 * 这个接口没有 key、没有文档，是**浏览器朗读功能**的后端。要连上得凑齐四样东西：
 *
 * 1. **`Sec-MS-GEC` 令牌**（{@link Drm}）—— 五分钟一格，服务端会校验，假的直接 403；
 * 2. **WebSocket 握手** —— 手机上连不了 WebView（安全策略），也不能引 OkHttp
 *    （这个 App 全靠零依赖压到 113KB），所以 RFC6455 的帧编解码是手写的；
 * 3. **SSML** —— 音色、语速都写在里面；
 * 4. **音频帧** —— 二进制帧前两个字节是"头有多长"，跳过头才是 MP3。
 *
 * ## 三层兜底（缺一不可）
 *
 * ```
 * ① 本地缓存命中？  → 直接播         （DSH-Go/tts/<sha1>.mp3）
 * ② 连上合成 → 存缓存 → 播
 * ③ 403 / 超时 / 没网 → 回落系统 TTS（并在日志记一笔）
 * ```
 *
 * 缓存不是优化，是**降低 403 概率的手段**：常用短句合成一次就永久命中，
 * 日常几乎不触网。微软哪天把协议改了，这一层还能让常用句照常出声。
 *
 * ⚠️ 这是逆向接口，**随时可能失效**。所以它必须是"可关"的（设置里能切回系统音），
 * 且失败绝不能让对话卡住或报错。
 */
final class EdgeTts {

    private static final String HOST = "speech.platform.bing.com";
    private static final String PATH = "/consumer/speech/synthesize/readaloud/edge/v1";
    /** 和 edge-tts 7.3.1 对齐：`2-<Chromium 版本>`。服务端会看这个。 */
    private static final String GEC_VERSION = "2-143.0.3650.75";
    private static final String FORMAT = "audio-24khz-48kbitrate-mono-mp3";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        + " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";

    /** MP3 缓存目录。和 conv / memory 一个层级，用户拿得到、也能自己删。 */
    static final String CACHE_DIR = "/sdcard/Download/DSH-Go/tts";

    /** 单次合成的上限：超过就截断，别让一句 10 分钟的朗读把内存吃了。 */
    private static final int MAX_AUDIO = 4 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT = 8_000;
    private static final int READ_TIMEOUT = 20_000;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Random RND = new Random();

    private EdgeTts() { }

    // ---------------------------------------------------------------- 对外

    /**
     * 合成一句，**同步、必须在后台线程调**。
     *
     * @param voice 音色 id（`zh-CN-XiaoxuanNeural`）
     * @param rate  语速，如 `+0%` / `-10%` / `+20%`
     * @return MP3 字节；失败抛异常（调用方去回落系统 TTS）
     */
    static byte[] synth(String text, String voice, String rate) throws Exception {
        String clean = sanitize(text);
        if (clean.trim().length() == 0) throw new IOException("空文本");
        try {
            return once(clean, voice, rate, 0);
        } catch (HandshakeError he) {
            /**
             * 403 多半是**本机时钟不准**（令牌按五分钟取整，偏了几分钟就对不上）。
             * 用 403 响应里的 `Date` 头校正一次再试 —— 官方实现也是这么做的。
             */
            if (he.dateHeader == null) throw he;
            double server = parseHttpDate(he.dateHeader);
            if (server <= 0) throw he;
            double now = System.currentTimeMillis() / 1000.0 + Drm.skew();
            double adj = server - now;
            EyeLog.w("edge", "403，按服务端时间校正 " + String.format(Locale.ROOT, "%+.0f", adj) + "s 后重试");
            Drm.setSkew(Drm.skew() + adj);
            return once(clean, voice, rate, 0);
        }
    }

    /** 缓存文件名：音色 + 语速 + 文本，任一变了就是另一份。 */
    static File cacheFile(String text, String voice, String rate) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest((voice + "|" + rate + "|" + text).getBytes(UTF8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return new File(dir(), sb.toString() + ".mp3");
        } catch (Throwable e) {
            return null;
        }
    }

    static File dir() {
        File d = new File(CACHE_DIR);
        if (!d.exists() && !d.mkdirs()) EyeLog.w("edge", "建不了 " + CACHE_DIR + "（没给存储权限？）");
        return d;
    }

    /** 缓存里有多少份。 */
    static int cacheCount() {
        File[] fs = dir().listFiles();
        return fs == null ? 0 : fs.length;
    }

    static void clearCache() {
        File[] fs = dir().listFiles();
        if (fs == null) return;
        for (File f : fs) { try { f.delete(); } catch (Throwable ignored) { } }
    }

    // ---------------------------------------------------------------- 一次合成

    private static byte[] once(String text, String voice, String rate, int depth) throws Exception {
        Socket raw = new Socket();
        SSLSocket sock = null;
        try {
            raw.connect(new InetSocketAddress(HOST, 443), CONNECT_TIMEOUT);
            raw.setSoTimeout(READ_TIMEOUT);
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            sock = (SSLSocket) f.createSocket(raw, HOST, 443, true);
            sock.setSoTimeout(READ_TIMEOUT);
            sock.startHandshake();

            String connId = uuidHex();
            String query = "?TrustedClientToken=" + Drm.TRUSTED_CLIENT_TOKEN
                + "&ConnectionId=" + connId
                + "&Sec-MS-GEC=" + Drm.token()
                + "&Sec-MS-GEC-Version=" + GEC_VERSION;
            String key = base64(randomBytes(16));

            StringBuilder req = new StringBuilder(512);
            req.append("GET ").append(PATH).append(query).append(" HTTP/1.1\r\n")
               .append("Host: ").append(HOST).append("\r\n")
               .append("Upgrade: websocket\r\n")
               .append("Connection: Upgrade\r\n")
               .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
               .append("Sec-WebSocket-Version: 13\r\n")
               .append("Pragma: no-cache\r\n")
               .append("Cache-Control: no-cache\r\n")
               .append("Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n")
               .append("User-Agent: ").append(UA).append("\r\n")
               .append("Accept-Language: en-US,en;q=0.9\r\n")
               .append("Cookie: muid=").append(uuidHex().toUpperCase(Locale.ROOT)).append(";\r\n")
               .append("\r\n");
            OutputStream out = sock.getOutputStream();
            out.write(req.toString().getBytes(UTF8));
            out.flush();

            String head = readHead(sock.getInputStream());
            if (head == null || !head.startsWith("HTTP/1.1 101")) {
                String date = headerOf(head, "date");
                throw new HandshakeError(head == null ? "no response" : firstLine(head), date);
            }

            // ① 告诉服务端要什么格式
            String ts = jsDate();
            String cfg = "X-Timestamp:" + ts + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"" + FORMAT + "\"}}}}\r\n";
            writeFrame(out, 1, cfg.getBytes(UTF8));

            // ② 发 SSML
            String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis'"
                + " xml:lang='en-US'><voice name='" + voice + "'>"
                + "<prosody pitch='+0Hz' rate='" + rate + "' volume='+0%'>"
                + xmlEscape(text)
                + "</prosody></voice></speak>";
            String envelope = "X-RequestId:" + uuidHex() + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + ts + "Z\r\n"
                + "Path:ssml\r\n\r\n"
                + ssml;
            writeFrame(out, 1, envelope.getBytes(UTF8));

            // ③ 收 MP3
            ByteArrayOutputStream audio = new ByteArrayOutputStream(64 * 1024);
            InputStream in = sock.getInputStream();
            int guard = 0;
            while (guard++ < 5000) {
                Frame fr = readFrame(in);
                if (fr == null) break;
                if (fr.opcode == 9) {                 // ping → 回 pong
                    writeFrame(out, 10, fr.payload);
                    continue;
                }
                if (fr.opcode == 8) break;            // close
                if (fr.opcode == 2) {                 // 二进制：前两字节是头长
                    if (fr.payload.length < 2) continue;
                    int hl = ((fr.payload[0] & 0xff) << 8) | (fr.payload[1] & 0xff);
                    if (hl > fr.payload.length) continue;
                    audio.write(fr.payload, hl + 2, fr.payload.length - hl - 2);
                    if (audio.size() > MAX_AUDIO) break;
                } else if (fr.opcode == 1) {          // 文本：turn.end 就是完了
                    String t = new String(fr.payload, UTF8);
                    if (t.contains("Path:turn.end")) break;
                }
            }
            byte[] mp3 = audio.toByteArray();
            if (mp3.length == 0) throw new IOException("没收到音频");
            return mp3;
        } finally {
            try { if (sock != null) sock.close(); } catch (Throwable ignored) { }
            try { raw.close(); } catch (Throwable ignored) { }
        }
    }

    /** 握手失败，带上 403 的 `Date` 头（校时要用）。 */
    static final class HandshakeError extends IOException {
        final String dateHeader;
        HandshakeError(String msg, String date) {
            super(msg);
            this.dateHeader = date;
        }
    }

    // ---------------------------------------------------------------- RFC6455（只做够用的部分）

    private static final class Frame {
        int opcode;
        byte[] payload;
    }

    /** 客户端发帧**必须加掩码**（RFC6455 硬性要求），服务端不加。 */
    private static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        byte[] p = payload == null ? new byte[0] : payload;
        ByteArrayOutputStream b = new ByteArrayOutputStream(p.length + 14);
        b.write(0x80 | (opcode & 0x0f));          // FIN + opcode
        if (p.length < 126) {
            b.write(0x80 | p.length);
        } else if (p.length <= 0xffff) {
            b.write(0x80 | 126);
            b.write((p.length >>> 8) & 0xff);
            b.write(p.length & 0xff);
        } else {
            b.write(0x80 | 127);
            for (int i = 7; i >= 0; i -= 1) b.write((int) (((long) p.length >>> (i * 8)) & 0xff));
        }
        byte[] mask = randomBytes(4);
        b.write(mask, 0, 4);
        for (int i = 0; i < p.length; i += 1) b.write(p[i] ^ mask[i % 4]);
        out.write(b.toByteArray());
        out.flush();
    }

    private static Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;
        Frame f = new Frame();
        f.opcode = b0 & 0x0f;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7f;
        if (len == 126) {
            len = ((long) readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i += 1) len = (len << 8) | readByte(in);
        }
        if (len < 0 || len > MAX_AUDIO) throw new IOException("帧太大：" + len);
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }
        byte[] p = new byte[(int) len];
        readFully(in, p);
        if (mask != null) for (int i = 0; i < p.length; i += 1) p[i] ^= mask[i % 4];
        f.payload = p;
        return f;
    }

    private static int readByte(InputStream in) throws IOException {
        int v = in.read();
        if (v < 0) throw new IOException("连接断了");
        return v;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n <= 0) throw new IOException("连接断了");
            off += n;
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(512);
        int c;
        while ((c = in.read()) >= 0) {
            b.write(c);
            byte[] a = b.toByteArray();
            int n = a.length;
            if (n >= 4 && a[n - 1] == '\n' && a[n - 2] == '\r' && a[n - 3] == '\n' && a[n - 4] == '\r') {
                return new String(a, UTF8);
            }
            if (n > 8192) break;
        }
        return b.size() == 0 ? null : new String(b.toByteArray(), UTF8);
    }

    private static String firstLine(String head) {
        int i = head.indexOf("\r\n");
        return i < 0 ? head : head.substring(0, i);
    }

    private static String headerOf(String head, String name) {
        if (head == null) return null;
        String low = head.toLowerCase(Locale.ROOT);
        int i = low.indexOf("\r\n" + name + ":");
        if (i < 0) return null;
        int from = i + name.length() + 3;
        int to = head.indexOf("\r\n", from);
        return (to < 0 ? head.substring(from) : head.substring(from, to)).trim();
    }

    /** `Thu, 12 Nov 2026 11:30:00 GMT` → unix 秒。 */
    private static double parseHttpDate(String s) {
        String[] pats = { "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss Z" };
        for (String p : pats) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("GMT"));
                Date d = f.parse(s);
                if (d != null) return d.getTime() / 1000.0;
            } catch (Throwable ignored) { }
        }
        return -1;
    }

    // ---------------------------------------------------------------- 杂项

    /**
     * 服务端不认几个控制字符（最要命的是垂直制表符，从 PDF 复制来的文本里很常见）。
     * 直接换成空格 —— 不换整句会被拒。
     */
    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i += 1) {
            char c = s.charAt(i);
            int code = c;
            if ((code >= 0 && code <= 8) || (code >= 11 && code <= 12) || (code >= 14 && code <= 31)) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String xmlEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i += 1) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '\'') sb.append("&apos;");
            else if (c == '"') sb.append("&quot;");
            else sb.append(c);
        }
        return sb.toString();
    }

    /** `Thu Nov 12 2026 11:30:00 GMT+0000 (Coordinated Universal Time)` —— 微软要的就是这个格式（注意不是 RFC 日期）。 */
    private static String jsDate() {
        SimpleDateFormat f = new SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date());
    }

    private static String uuidHex() {
        byte[] b = randomBytes(16);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RND.nextBytes(b);
        return b;
    }

    private static String base64(byte[] b) {
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    /** 读缓存文件（没有就 null）。 */
    static byte[] readCache(File f) {
        if (f == null || !f.exists()) return null;
        try {
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
            in.close();
            return off == b.length ? b : null;
        } catch (Throwable e) {
            return null;
        }
    }

    static void writeCache(File f, byte[] mp3) {
        if (f == null || mp3 == null || mp3.length == 0) return;
        try {
            FileOutputStream o = new FileOutputStream(f, false);
            o.write(mp3);
            o.close();
        } catch (Throwable e) {
            EyeLog.w("edge", "存缓存失败: " + e);
        }
    }
}
