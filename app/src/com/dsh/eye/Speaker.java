package com.dsh.eye;

import android.content.Context;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

/**
 * 语音朗读 —— 手机端唯一能出声的通道。
 *
 * 这段逻辑是从 DSH TTS Bridge 原样搬过来的（那边已经踩完了所有坑）：
 *  `am start` 被 assertPackageMatchesCallingUid 拒、`cmd texttospeech` 没实现、
 *  `am broadcast` 在 PRoot 里会随机 Failed transaction。所以真正开口只能由
 *  **本进程**调 TextToSpeech —— 现在插件直接打 HTTP `/say` 进来，比"写文件 + 轮询"
 *  少一跳，也不再依赖存储权限。
 *
 * ttsReady **必须 volatile**：worker 线程读、主线程回调写。没加的时候踩过：
 * TTS 明明初始化好了，轮询线程永远看到 false，于是一句话都不念。
 */
final class Speaker {

    private static final String TAG = "DshEyeTts";

    private final Context app;
    private TextToSpeech tts;
    private volatile boolean ready;
    private volatile int initStatus = -999;
    private volatile String lang = "?";
    private volatile String lastError;
    private volatile String lastText;
    private volatile int speaks;
    private volatile long lastAt;

    /**
     * 小轩这类**在线音色**（{@link EdgeTts}）。空 = 走系统 TTS。
     *
     * 存的是音色 id（`zh-CN-XiaoxuanNeural`），前缀 `edge:` 由 {@link Config} 那层去掉。
     * 之所以在 Speaker 里而不是单开一个类：**"念一句"的入口只能有一个** ——
     * 谁先谁后、失败了回落谁，必须由同一个队列决定（见 {@link #edgeQ}）。
     */
    private volatile String edgeVoice = "";
    private volatile String edgeRate = "+0%";
    private MediaPlayer mp;
    /**
     * 在线合成**串行**跑：合成要 300~800ms，并发跑会让两句话抢同一个 MediaPlayer，
     * 表现就是"念到一半跳回上一句"。单线程队列天然保证"一句接一句"。
     */
    private final java.util.concurrent.ExecutorService edgeQ =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int edgeOk, edgeCacheHit, edgeFail;
    private volatile String edgeErr;

    /** 系统里实际可用的中文音色名（`Voice.getName()`，华为上就是 `xiaoxuan` 这种）。 */
    private final java.util.List<android.speech.tts.Voice> voices = new java.util.ArrayList<>();
    /** 想要的音色。空 = 系统默认。 */
    private volatile String wanted = "";
    /** 当前真的生效的音色（设失败时和 wanted 不一样，这就是排查点）。 */
    private volatile String voiceName = "";
    private volatile String voiceErr;

    Speaker(Context ctx) {
        this.app = ctx.getApplicationContext();
        init();
    }

    private void init() {
        try {
            tts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
                public void onInit(int status) {
                    initStatus = status;
                    if (status != TextToSpeech.SUCCESS) {
                        lastError = "init-failed:" + status;
                        return;
                    }
                    try {
                        int lr = tts.setLanguage(Locale.CHINA);
                        lang = String.valueOf(lr);   // -1=MISSING_DATA -2=NOT_SUPPORTED
                        tts.setSpeechRate(1.0f);
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            public void onStart(String id) { }
                            public void onDone(String id) { }
                            public void onError(String id) { lastError = "utterance:" + id; }
                        });
                        ready = true;
                        lastError = null;
                        /**
                         * 初始化成功后才敢碰音色：`getVoices()` 在 init 之前返回 null，
                         * 有些引擎还会直接抛。这里顺手把可用音色列出来（设置页要用），
                         * 然后把用户选的那个套上。
                         */
                        enumerateVoices();
                        applyVoice();
                        Log.i(TAG, "tts ready lang=" + lang + " voice=" + voiceName
                            + " voices=" + voices.size() + (voiceErr == null ? "" : " err=" + voiceErr));
                    } catch (Throwable e) {
                        lastError = "setup:" + e;
                    }
                }
            });
        } catch (Throwable e) {
            lastError = "new-tts:" + e;
        }
    }

    /**
     * 念一段话。**长文本自动切段排队**。
     *
     * 以前是整段硬截到 500 字：正常模式的回答经常上千字，后半句直接被吞掉，
     * 用户听到一半没了。现在按句号切段，第一段 QUEUE_FLUSH（打断旧话），
     * 后面 QUEUE_ADD（接着念），最长念 8 段（≈960 字）。
     *
     * @return null 表示成功，否则是失败原因
     */
    String say(String text) {
        String s = text == null ? "" : plain(text).replace('\r', ' ').trim();
        if (s.length() == 0) return "empty";
        if (edgeVoice.length() > 0) {
            /**
             * 在线音色是**异步**的（要联网合成），所以这里立刻返回 null。
             * 调用方（{@link Asker} 那条线程）不该为了一句朗读卡 1 秒。
             */
            final String t = s;
            final String v = edgeVoice;
            final String r = edgeRate;
            try {
                edgeQ.submit(new Runnable() {
                    public void run() { speakEdge(t, v, r); }
                });
                lastText = s;
                lastAt = System.currentTimeMillis();
                return null;
            } catch (Throwable e) {
                edgeErr = "queue:" + e;
            }
        }
        return saySystem(s);
    }

    /** 原来的系统 TTS 那条路（也是在线音色失败后的兜底）。 */
    String saySystem(String text) {
        String s = text == null ? "" : plain(text).replace('\r', ' ').trim();
        if (s.length() == 0) return "empty";
        if (!ready) {
            // 初始化是异步的（1~2 秒）。没就绪就重试一次，别把这句话静默吞了。
            if (tts == null) init();
            return ready ? null : ("not-ready:" + initStatus);
        }
        java.util.List<String> parts = split(s, CHUNK, MAX_CHUNKS);
        if (parts.isEmpty()) return "empty";
        try {
            long stamp = System.currentTimeMillis();
            for (int i = 0; i < parts.size(); i += 1) {
                int queue = i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
                int r = tts.speak(parts.get(i), queue, null, "dsh-" + stamp + "-" + i);
                if (r != TextToSpeech.SUCCESS) {
                    lastError = "speak-ret:" + r;
                    return lastError;
                }
            }
            speaks += 1;
            lastText = s;
            lastAt = System.currentTimeMillis();
            lastError = null;
            return null;
        } catch (Throwable e) {
            lastError = "speak-ex:" + e;
            return lastError;
        }
    }

    /**
     * 把 Markdown 洗成"人话"再念。
     *
     * 正常模式的回答带 `**加粗**`、`- 列表`、`### 标题`，直接丢给 TTS 会逐字念出
     * "星号星号地面星号星号"、"减号"。字幕要保留格式，念的时候必须洗掉。
     */
    static String plain(String s) {
        String t = s;
        t = t.replaceAll("\\*\\*(.+?)\\*\\*", "$1");        // **加粗**
        t = t.replaceAll("`([^`]*)`", "$1");                // `代码`
        t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1"); // [文字](链接)
        t = t.replaceAll("(?m)^\\s*#{1,6}\\s*", "");        // ### 标题
        t = t.replaceAll("(?m)^\\s*[-*•]\\s+", "");         // - 列表
        t = t.replaceAll("(?m)^\\s*(\\d{1,2})[.)、]\\s+", "$1，"); // 1. 列表
        t = t.replace("*", "").replace("__", "");
        t = t.replaceAll("[ \\t]+", " ");
        return t;
    }

    /** 单段上限（字）。太长 TTS 引擎自己会截，而且打断粒度太粗。 */
    private static final int CHUNK = 120;
    /** 最多念几段 —— 再长就该用户自己看了，别把手机变成复读机。 */
    private static final int MAX_CHUNKS = 8;

    /**
     * 按句末标点切段；单个句子超过 max 就硬切。
     *
     * 英文句点要跟空格/结尾才算断句（否则 "3.5" 会被切成 "3." 和 "5"）。
     */
    private static java.util.List<String> split(String s, int max, int maxChunks) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < s.length() && out.size() < maxChunks) {
            char c = s.charAt(i);
            boolean cjk = c == '。' || c == '！' || c == '？' || c == '；' || c == '，' || c == '\n';
            boolean ascii = (c == '.' || c == '!' || c == '?' || c == ';')
                && (i + 1 >= s.length() || s.charAt(i + 1) == ' ');
            boolean hard = i + 1 - start >= max;
            if ((cjk && i + 1 - start >= 24) || ascii || hard) {
                String cut = s.substring(start, i + 1).trim();
                if (cut.length() > 0) out.add(cut);
                start = i + 1;
            }
            i += 1;
        }
        if (start < s.length() && out.size() < maxChunks) {
            String tail = s.substring(start).trim();
            if (tail.length() > 0) out.add(tail);
        }
        return out;
    }

    /** 语速。导盲场景 1.15~1.25 更跟得上；正常对话 1.0 就够。 */
    void setRate(float rate) {
        try {
            if (tts != null) tts.setSpeechRate(Math.max(0.5f, Math.min(2.0f, rate)));
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ 音色

    /** 列出系统里所有中文音色。init 之前调用只会拿到空表。 */
    private void enumerateVoices() {
        try {
            java.util.Set<android.speech.tts.Voice> vs = tts.getVoices();
            if (vs == null) return;
            java.util.List<android.speech.tts.Voice> zh = new java.util.ArrayList<>();
            for (android.speech.tts.Voice v : vs) {
                if (v == null) continue;
                Locale l = v.getLocale();
                if (l != null && "zh".equals(l.getLanguage())) zh.add(v);
            }
            java.util.Collections.sort(zh, new java.util.Comparator<android.speech.tts.Voice>() {
                public int compare(android.speech.tts.Voice x, android.speech.tts.Voice y) {
                    return x.getName().compareTo(y.getName());
                }
            });
            voices.clear();
            voices.addAll(zh);
        } catch (Throwable e) {
            voiceErr = "voices:" + e;
        }
    }

    /**
     * 音色的显示名。
     *
     * 用反射取 `getDisplayName()`：这个 SDK 的 `android.jar` 里没有该方法（API 号够但 stub 不全），
     * 而真机上是有的 —— 华为把三个中文音色都叫 `zh`，唯一能区分的就是 displayName。
     * 取不到就退回 getName()，不会崩。
     */
    private static String dispOf(android.speech.tts.Voice v) {
        String name = v.getName();
        String disp = null;
        try {
            java.lang.reflect.Method m = v.getClass().getMethod("getDisplayName");
            Object r = m.invoke(v);
            if (r instanceof String) disp = ((String) r).trim();
        } catch (Throwable ignored) { }
        // 引擎把 displayName 也报成 "zh"（华为就是这样），那就按 locale 说人话
        if (disp == null || disp.length() == 0 || disp.equals(name)) {
            String tag = String.valueOf(v.getLocale());
            if (tag.contains("Hans")) return "简体中文";
            if (tag.contains("Hant")) return "繁体中文";
            if ("zh".equals(tag)) return "中文（默认）";
            return tag;
        }
        return disp;
    }

    /**
     * 音色的**稳定 key**。
     *
     * 有的引擎（华为就是）把所有中文音色都叫 `zh`，光看名字分不出来 ——
     * 重名时补序号：`zh#2` = "第 2 个叫 zh 的"。序号按名字排序后算，同一台手机上稳定；
     * 换台手机对不上也没关系，找不到就退回系统默认，不会哑。
     */
    private java.util.List<String> keysNow() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < voices.size(); i += 1) {
            String n = voices.get(i).getName();
            int total = 0, nth = 0;
            for (int j = 0; j < voices.size(); j += 1) {
                if (!n.equals(voices.get(j).getName())) continue;
                total += 1;
                if (j < i) nth += 1;
            }
            out.add(total > 1 ? n + "#" + (nth + 1) : n);
        }
        return out;
    }

    /** 给人看的标签，和 {@link #keysNow()} 一一对应。 */
    java.util.List<String> voiceLabels() {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.Map<String, Integer> total = new java.util.HashMap<>();
        for (android.speech.tts.Voice v : voices) {
            String d = dispOf(v);
            total.put(d, (total.containsKey(d) ? total.get(d) : 0) + 1);
        }
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (android.speech.tts.Voice v : voices) {
            String d = dispOf(v);
            int n = (seen.containsKey(d) ? seen.get(d) : 0) + 1;
            seen.put(d, n);
            String label = total.get(d) > 1 ? d + " #" + n : d;
            if (v.isNetworkConnectionRequired()) label = label + "（联网）";
            out.add(label);
        }
        return out;
    }

    /** 每个音色的原始信息，排查用。 */
    String describeVoices() {
        java.util.List<String> keys = keysNow();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < voices.size(); i += 1) {
            android.speech.tts.Voice v = voices.get(i);
            if (i > 0) b.append(" | ");
            b.append(keys.get(i)).append('[').append(v.getName())
             .append('/').append(dispOf(v))
             .append('/').append(v.getLocale())
             .append("/q").append(v.getQuality())
             .append(v.isNetworkConnectionRequired() ? "/net" : "")
             .append(']');
        }
        return b.length() == 0 ? "（引擎没报任何中文音色）" : b.toString();
    }

    /**
     * 换音色。
     *
     * 传空 = 系统默认。key 对不上就**保持原样**并记下原因 ——
     * 静默失败比报错难查得多（不同机型装的音色包不一样）。
     */
    void setVoice(String key) {
        wanted = key == null ? "" : key.trim();
        /**
         * `edge:` 开头的走在线音色（小轩那些）。**这条判断必须在这里**：
         * 系统 TTS 里没有 `zh-CN-XiaoxuanNeural` 这个名字，走 applyVoice 只会
         * 得到一句"没有这个音色"，然后静默退回默认嗓子 —— 用户听到的是"设了没用"。
         */
        if (wanted.startsWith("edge:")) {
            edgeVoice = wanted.substring(5).trim();
            voiceName = edgeVoice;
            voiceErr = null;
            return;
        }
        edgeVoice = "";
        applyVoice();
    }

    /** 语速：系统那边是 float，edge 那边是 `+20%` 这种字符串，一起改。 */
    void setEdgeRate(float rate) {
        int pct = Math.round((rate - 1.0f) * 100);
        edgeRate = String.format(java.util.Locale.ROOT, "%+d%%", pct);
    }

    /**
     * 合成 → 播 → 存缓存；**任何一步失败就回落系统 TTS**。
     *
     * 跑在 {@link #edgeQ} 的线程上，所以可以直接阻塞。
     */
    private void speakEdge(String text, String voice, String rate) {
        byte[] mp3 = null;
        java.io.File cf = EdgeTts.cacheFile(text, voice, rate);
        mp3 = EdgeTts.readCache(cf);
        if (mp3 != null) {
            edgeCacheHit += 1;
        } else {
            try {
                mp3 = EdgeTts.synth(text, voice, rate);
                EdgeTts.writeCache(cf, mp3);
                edgeOk += 1;
            } catch (Throwable e) {
                edgeFail += 1;
                edgeErr = String.valueOf(e.getMessage() == null ? e : e.getMessage());
                EyeLog.w("edge", "合成失败，回落系统音：" + edgeErr);
                saySystem(text);
                return;
            }
        }
        if (!playMp3(mp3)) {
            edgeFail += 1;
            saySystem(text);
        }
    }

    /** 播一段 MP3。**写临时文件再播** —— MediaPlayer 吃路径最稳，喂字节流各机型差异大。 */
    private boolean playMp3(byte[] mp3) {
        java.io.File tmp = new java.io.File(app.getCacheDir(), "say.mp3");
        try {
            // ⚠️ 先停旧的再覆盖同一个文件：边播边写会把正在读的文件改掉（会卡一下/爆音）
            stopMp();
            java.io.FileOutputStream o = new java.io.FileOutputStream(tmp, false);
            o.write(mp3);
            o.close();
            MediaPlayer p = new MediaPlayer();
            try {
                p.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
                p.setDataSource(tmp.getAbsolutePath());
                p.prepare();
                p.start();
            } catch (Throwable e) {
                // prepare 失败也要 release：不然这条 native 播放器就一直挂在那儿
                try { p.release(); } catch (Throwable ignored) { }
                throw e;
            }
            mp = p;
            speaks += 1;
            lastError = null;
            return true;
        } catch (Throwable e) {
            lastError = "mp3:" + e;
            EyeLog.w("edge", "播放失败: " + e);
            return false;
        }
    }

    private void stopMp() {
        MediaPlayer p = mp;
        mp = null;
        if (p == null) return;
        try { p.stop(); } catch (Throwable ignored) { }
        try { p.release(); } catch (Throwable ignored) { }
    }

    /** 在线音色这会儿有没有在工作（设置页显示"正在用哪个嗓子"）。 */
    String edgeStatus() {
        if (edgeVoice.length() == 0) return "";
        return edgeVoice + " · 合成 " + edgeOk + " / 缓存 " + edgeCacheHit + " / 失败 " + edgeFail
            + (edgeErr == null ? "" : "（" + edgeErr + "）");
    }

    private void applyVoice() {
        if (tts == null || !ready) return;      // init 成功后会自动再调一次
        /**
         * 在线音色也走这条路（TTS 初始化完成时会再调一次 applyVoice）。
         * 不挡住的话，系统 TTS 会认真地去找一个叫 `edge:zh-CN-XiaoxuanNeural` 的嗓子、
         * 找不到、然后把 voiceError 写成"没有这个音色" —— 明明能正常出声，
         * 设置页/`/status` 却一直挂着一条红字。
         */
        if (wanted.startsWith(Config.EDGE_PREFIX)) {
            edgeVoice = wanted.substring(Config.EDGE_PREFIX.length()).trim();
            voiceName = edgeVoice;
            voiceErr = null;
            return;
        }
        if (wanted.length() == 0) { voiceName = ""; voiceErr = null; return; }
        try {
            if (voices.isEmpty()) enumerateVoices();
            int idx = keysNow().indexOf(wanted);
            if (idx < 0) {
                voiceErr = "没有这个音色：" + wanted + "（可用 " + voices.size() + " 个）";
                voiceName = "";
                return;
            }
            int r = tts.setVoice(voices.get(idx));
            if (r == TextToSpeech.SUCCESS) { voiceName = wanted; voiceErr = null; }
            else { voiceErr = "setVoice 返回 " + r; voiceName = ""; }
        } catch (Throwable e) {
            voiceErr = "setVoice:" + e;
            voiceName = "";
        }
    }

    String voiceName() { return voiceName; }

    boolean isReady() { return ready; }

    String lastError() { return lastError; }

    /** 和 {@link #voiceLabels()} 一一对应的稳定 key（设置页存的就是它）。 */
    java.util.List<String> voiceKeys() { return keysNow(); }

    void stop() {
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) { }
        tts = null;
        ready = false;
        stopMp();
        try { edgeQ.shutdownNow(); } catch (Throwable ignored) { }
    }

    /** 打断（用户又说话了 / 关了悬浮窗）：把正在念的那句掐掉。 */
    void shutUp() {
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) { }
        stopMp();
    }

    /** 一段 JSON，给 `/tts/status` 和诊断用。 */
    String statusJson() {
        java.util.List<String> keys = keysNow();
        StringBuilder vs = new StringBuilder();
        for (String k : keys) {
            if (vs.length() > 0) vs.append(",");
            vs.append("\"").append(Json.esc(k)).append("\"");
        }
        return "{\"ready\":" + ready
            + ",\"init\":" + initStatus
            + ",\"lang\":\"" + Json.esc(lang) + "\""
            + ",\"voice\":\"" + Json.esc(voiceName) + "\""
            + ",\"wanted\":\"" + Json.esc(wanted) + "\""
            + ",\"voiceError\":" + (voiceErr == null ? "null" : "\"" + Json.esc(voiceErr) + "\"")
            + ",\"voices\":[" + vs + "]"
            + ",\"edge\":\"" + Json.esc(edgeVoice) + "\""
            + ",\"edgeOk\":" + edgeOk
            + ",\"edgeHit\":" + edgeCacheHit
            + ",\"edgeFail\":" + edgeFail
            + ",\"speaks\":" + speaks
            + ",\"lastAt\":" + lastAt
            + ",\"lastText\":" + (lastText == null ? "null" : "\"" + Json.esc(lastText) + "\"")
            + ",\"lastError\":" + (lastError == null ? "null" : "\"" + Json.esc(lastError) + "\"")
            + "}";
    }

}
