package com.dsh.eye;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * 一个 40KB 的 HTTP 服务：只用 ServerSocket，不引任何库。
 *
 * 端点故意和 Android IP Camera App 保持**同名同义**，这样插件 0.1.x 一行都不用改：
 *
 *   GET /video/mjpeg            multipart/x-mixed-replace 连续流
 *   GET /frame.jpg              抓一张（对应 CAM_MODE=grab）
 *   GET /control/status         状态 JSON（老桥 App 的守护就是这么探活的）
 *   GET /?camera=back&rotate=90&torch=on&resolution=low&fps=15   下发控制参数
 *   GET /say?text=...           朗读（新增，替掉"写 say.txt + 轮询"）
 *   GET /tts/status             语音状态（新增）
 *   GET /mode?set=normal|guide  切「正常 / 导盲」，?next=1 循环
 *
 * 只绑 127.0.0.1：这是给同机进程用的管道，不该暴露到局域网。
 * PC 侧调试走 `adb forward tcp:18150 tcp:4444`，adbd 就是连设备的 127.0.0.1。
 */
final class HttpServer {

    private static final String TAG = "DshEyeHttp";
    private static final int PORT = 4444;
    private static final String BOUNDARY = "dshframe";

    static final class Frame {
        final long seq;
        final long wall;
        final byte[] data;
        Frame(long seq, long wall, byte[] data) { this.seq = seq; this.wall = wall; this.data = data; }
    }

    interface Source {
        Frame latestFrame();
        Frame waitFrame(long afterSeq, long timeoutMs);
        boolean streaming();
        String statusJson();
        /** 应用一组控制参数，返回给人看的一句话。 */
        String applyControl(Map<String, String> q);
        String say(String text);
        String ttsJson();
        /** 设置模型参数（key/base/model/think），返回脱敏后的状态。 */
        String setConfig(Map<String, String> q);

        /** 同步问一句，拿回回答。给 PC 端调这个 App 用，也给自测用。 */
        default String askOnce(String text, int timeoutSec) { return "no brain"; }

        /** 会话+记忆导出成摊平目录（顺带打个 zip），返回 JSON。 */
        default String exportData() { return "{\"ok\":false,\"error\":\"not supported\"}"; }

        /** 导出并直接返回 zip 的 base64（局域网模式用，省掉 adb pull）。 */
        default String exportB64() { return "{\"ok\":false,\"error\":\"not supported\"}"; }

        /** 只导**一段**对话（含摘要，图片 offload），给电脑转 DSH 会话用。 */
        default String exportOne(String convId) { return "{\"ok\":false,\"error\":\"not supported\"}"; }

        /** 局域网模式：电脑 POST 一段 jsonl 过来，直接并入 `<name>.jsonl`。 */
        default String importText(String name, String body) { return "{\"ok\":false,\"error\":\"not supported\"}"; }

        /** 把 import/ 目录合并进来，返回 JSON。 */
        default String importData() { return "{\"ok\":false,\"error\":\"not supported\"}"; }

        /** 要不要绑 `0.0.0.0`（局域网模式）。默认不。 */
        default boolean lan() { return false; }

        /** 重新绑一次（用户开关切了要立刻生效，不能等下次进程启动）。 */
        default void rebind() { }

        /** 模式：v4 只剩 normal（老脚本还在传，接了不崩）。 */
        default String modeSet(Map<String, String> q) { return "no mode"; }

        /** 音色：不带参数看现状，`?name=xiaoxuan` 换（空值回系统默认）。 */
        default String ttsVoice(Map<String, String> q) { return "no tts"; }

        /**
         * **按需抓一帧**（v4 没有常驻采集了）。
         *
         * 默认实现退回"最近一帧"，给没有相机的实现兜底；{@link EyeService} 会覆盖成
         * "借相机 → 等帧 → 还相机"。
         */
        default Frame grabFrame() { return latestFrame(); }

        /** MJPEG 流开始/结束（用来借还相机）。 */
        default void streamOpened() { }
        default void streamClosed() { }

        /** `/look`：现场抓一张拼好的宫格，返回 JSON（图 base64）。 */
        default String lookSheet(double seconds) { return "{\"error\":\"not supported\"}"; }
    }

    private final Source src;
    private ServerSocket ss;
    private Thread accept;
    private volatile boolean running;
    private volatile String lastError;
    private volatile int clients;
    private final Object bindLock = new Object();

    HttpServer(Source source) { this.src = source; }

    void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() { bindLoop(); }
        }, "dsh-eye-http");
        t.setDaemon(true);
        accept = t;
        t.start();
    }

    void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (Throwable ignored) { }
        ss = null;
    }

    String lastError() { return lastError; }
    int clients() { return clients; }
    boolean listening() { return ss != null && !ss.isClosed(); }

    /**
     * 端口被占（旧 IP Camera 还没卸）不致命：每 3 秒再试一次。
     * 卸载对方之后不用重启本 App 也能自己接上。
     */
    private void bindLoop() {
        while (running) {
            try {
                // 局域网模式绑 0.0.0.0；否则只听本机（默认，安全）
                String bind = src.lan() ? "0.0.0.0" : "127.0.0.1";
                ServerSocket s = new ServerSocket(PORT, 8, InetAddress.getByName(bind));
                s.setReuseAddress(true);
                synchronized (bindLock) { ss = s; lastError = null; }
                Log.i(TAG, "listening 127.0.0.1:" + PORT);
                acceptLoop(s);
            } catch (Throwable e) {
                lastError = "端口 " + PORT + " 绑定失败：" + e.getMessage();
                Log.w(TAG, lastError);
            }
            if (!running) break;
            try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
        }
    }

    private void acceptLoop(ServerSocket s) {
        while (running && !s.isClosed()) {
            Socket c;
            try {
                c = s.accept();
            } catch (IOException e) {
                break;
            }
            try { c.setSoTimeout(15000); c.setTcpNoDelay(true); } catch (Throwable ignored) { }
            Thread t = new Thread(new Conn(c), "dsh-eye-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private final class Conn implements Runnable {
        private final Socket s;
        Conn(Socket socket) { this.s = socket; }

        public void run() {
            clients += 1;
            try {
                handle(s);
            } catch (Throwable e) {
                // 客户端随时可能断开（插件重连、curl 超时），不是错误
            } finally {
                clients -= 1;
                try { s.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 读 POST body（按 Content-Length）。局域网推送用得到。 */
    private static byte[] readBody(InputStream in, String head) throws IOException {
        int len = 0;
        for (String h : head.split("\r\n")) {
            if (h.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                try { len = Integer.parseInt(h.substring(15).trim()); } catch (Throwable ignored) { }
            }
        }
        if (len <= 0) return new byte[0];
        if (len > 16 * 1024 * 1024) throw new IOException("body 太大：" + len);
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n <= 0) break;
            off += n;
        }
        return off == len ? b : java.util.Arrays.copyOf(b, off);
    }

    private void handle(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        String head = readHead(in);
        if (head == null) return;
        String line = head.split("\r\n", 2)[0];
        String[] parts = line.split(" ");
        if (parts.length < 2) return;
        String path = parts[1];
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
        Map<String, String> params = parseQuery(query);

        if (path.equals("/video/mjpeg") || path.equals("/stream") || path.equals("/mjpeg")) {
            stream(s, in);
            return;
        }
        if (path.equals("/frame.jpg") || path.equals("/frame")) {
            // v4：相机平时是关的，这里现场借一台（≤1.5 秒），给完就还。
            Frame f = src.grabFrame();
            if (f == null) { send(s, 503, "text/plain", "no frame yet".getBytes("UTF-8")); return; }
            send(s, 200, "image/jpeg", f.data);
            return;
        }
        /**
         * `/look`：**直接返回拼好的宫格**（v4 新增）。
         *
         * 现在只给 App 内部和自测用；等哪天插件瘦身，它就是主入口。所以返回结构要稳定：
         * `{images:[base64 jpeg], frames, tokens, why, canvas:{w,h}}`。
         */
        if (path.equals("/look")) {
            double sec = 2.5;
            try { sec = Double.parseDouble(params.getOrDefault("seconds", "2.5")); } catch (Throwable ignored) { }
            String r = src.lookSheet(sec);
            send(s, 200, "application/json; charset=utf-8", r.getBytes("UTF-8"));
            return;
        }
        if (path.equals("/control/status") || path.equals("/status")) {
            send(s, 200, "application/json", src.statusJson().getBytes("UTF-8"));
            return;
        }
        if (path.equals("/config")) {
            // PC 侧配置口子：手机键盘打 key 太痛苦，adb forward 之后一条 curl 就设好。
            // 只听 127.0.0.1，不暴露到局域网；回包里 key 只给前 6 位 + 尾 4 位。
            String r = src.setConfig(params);
            send(s, 200, "text/plain; charset=utf-8", r.getBytes("UTF-8"));
            return;
        }

        if (path.equals("/export")) {
            String conv = params.get("conv");
            String r;
            if (conv != null && conv.length() > 0) r = src.exportOne(conv);
            else if ("1".equals(params.get("b64"))) r = src.exportB64();
            else r = src.exportData();
            send(s, 200, "application/json; charset=utf-8", r.getBytes("UTF-8"));
            return;
        }
        if (path.equals("/import")) {
            // POST = 电脑直接推一段内容过来（局域网模式）；GET = 合并本地 import/ 目录（插线模式）
            String r;
            if ("POST".equalsIgnoreCase(parts[0])) {
                byte[] body = readBody(in, head);
                r = src.importText(params.get("name"), new String(body, "UTF-8"));
            } else {
                r = src.importData();
            }
            send(s, 200, "application/json; charset=utf-8", r.getBytes("UTF-8"));
            return;
        }
        if (path.equals("/tts/status")) {
            send(s, 200, "application/json", src.ttsJson().getBytes("UTF-8"));
            return;
        }

        if (path.equals("/tts/voice")) {
            String r = src.ttsVoice(params);
            send(s, 200, "text/plain; charset=utf-8", r.getBytes("UTF-8"));
            return;
        }

        if (path.equals("/mode")) {
            String r = src.modeSet(params);
            send(s, 200, "text/plain; charset=utf-8", r.getBytes("UTF-8"));
            return;
        }

        /**
         * 同步问一句，回答原样返回。
         *
         * 存在的理由：眼睛/耳朵都在手机上，但**手**（打字）不在 ——
         * 要让 PC 端 agent 跟它说话、或者自测时问一句，都得有个能发消息的口子。
         */
        if (path.equals("/ask")) {
            int sec = 30;
            try { sec = Integer.parseInt(params.getOrDefault("timeout", "30")); } catch (Throwable ignored) { }
            String r = src.askOnce(params.get("text"), Math.max(5, Math.min(90, sec)));
            send(s, 200, "text/plain; charset=utf-8", r.getBytes("UTF-8"));
            return;
        }
        if (path.equals("/say")) {
            String text = params.get("text");
            if (text == null) text = params.get("say");
            String err = src.say(text);
            String body = err == null
                ? "{\"ok\":true}"
                : "{\"ok\":false,\"error\":\"" + Json.esc(err) + "\"}";
            send(s, err == null ? 200 : 503, "application/json", body.getBytes("UTF-8"));
            return;
        }

        // 根路径：带参数就是控制（`GET /?camera=back&rotate=90`），不带就是一句问候
        if (params.isEmpty()) {
            String body = "鱼眼 · See on Phone\n" + src.statusJson() + "\n";
            send(s, 200, "text/plain; charset=utf-8", body.getBytes("UTF-8"));
            return;
        }
        String msg = src.applyControl(params);
        send(s, 200, "text/plain; charset=utf-8", msg.getBytes("UTF-8"));
    }

    /** 读请求头，最多 8KB —— 我们只认第一行，其余读掉是为了让 keep-alive 的客户端不卡。 */
    private String readHead(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder(256);
        int c;
        while ((c = in.read()) != -1) {
            b.append((char) c);
            if (b.length() > 8192) break;
            int n = b.length();
            if (n >= 4 && b.charAt(n - 1) == '\n' && b.charAt(n - 2) == '\r'
                && b.charAt(n - 3) == '\n' && b.charAt(n - 4) == '\r') break;
        }
        return b.length() == 0 ? null : b.toString();
    }

    private void send(Socket s, int code, String type, byte[] body) throws IOException {
        OutputStream out = s.getOutputStream();
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
            + "Content-Type: " + type + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n\r\n";
        out.write(head.getBytes("ASCII"));
        out.write(body);
        out.flush();
    }

    private static String reason(int code) {
        if (code == 200) return "OK";
        if (code == 400) return "Bad Request";
        if (code == 503) return "Service Unavailable";
        return "OK";
    }

    /**
     * MJPEG 流。每帧一个 part，带 Content-Length —— 老插件靠 SOI/EOI 抠帧，
     * 不依赖 boundary，但标准播放器（浏览器、VLC）靠它，两边都能吃。
     *
     * v4 的关键差别：**连上才开相机，断开 3 秒就关**（`streamOpened`/`streamClosed`）。
     * 所以这里不能"没帧就 break" —— 相机冷启动要 0.5~1.5 秒，那时一帧都还没有。
     */
    private void stream(Socket s, InputStream in) throws IOException {
        OutputStream out = s.getOutputStream();
        String head = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
            + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
            + "Pragma: no-cache\r\n"
            + "Connection: close\r\n\r\n";
        out.write(head.getBytes("ASCII"));
        out.flush();

        src.streamOpened();
        try {
            long after = 0;
            long startedAt = System.currentTimeMillis();
            while (running && !s.isClosed()) {
                Frame f = src.waitFrame(after, 800);
                if (f == null) {
                    // 前 3 秒允许空转（等相机冷启动）；之后还没帧就是真没画面
                    if (System.currentTimeMillis() - startedAt > 3000) break;
                    continue;
                }
                after = f.seq;
                writePart(out, f);
            }
        } finally {
            src.streamClosed();
        }
    }

    private void writePart(OutputStream out, Frame f) throws IOException {
        String h = "--" + BOUNDARY + "\r\n"
            + "Content-Type: image/jpeg\r\n"
            + "Content-Length: " + f.data.length + "\r\n\r\n";
        out.write(h.getBytes("ASCII"));
        out.write(f.data);
        out.write('\r');
        out.write('\n');
        out.flush();
    }

    private Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<String, String>();
        if (q == null || q.length() == 0) return m;
        for (String kv : q.split("&")) {
            if (kv.length() == 0) continue;
            int i = kv.indexOf('=');
            String k, v;
            if (i < 0) { k = kv; v = ""; } else { k = kv.substring(0, i); v = kv.substring(i + 1); }
            try {
                k = URLDecoder.decode(k, "UTF-8");
                v = URLDecoder.decode(v, "UTF-8");
            } catch (Throwable ignored) { }
            m.put(k.toLowerCase(), v);
        }
        return m;
    }
}
