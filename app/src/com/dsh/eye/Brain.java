package com.dsh.eye;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 直连 DeepSeek：一次 HTTP 请求 = 一轮对话。
 *
 * 这一层只做四件最无聊的事：**拼 URL、发字节、读回来、把回答拆成三段**
 * （正文 / 思考过程 / 工具调用）。**它不认识上下文，也不认识工具** ——
 * 那些是 {@link Asker} 的事。这么切是因为"哪一轮说了什么"属于会话逻辑，
 * 而"HTTP 会不会超时"属于传输逻辑，混在一起改一个就会碰坏另一个。
 *
 * ## 为什么用 org.json
 *
 * 手拼字符串在 v3 之前够用（只有文本 + base64 图）。v4 要解析 `tool_calls`
 * （`arguments` 是一个**装着 JSON 的字符串**，里面全是转义），手写扫描器第一个
 * 带引号的参数就会错。`org.json` 是 **Android 框架自带的**（`android.jar` 里就有，
 * `Tuning` 一直在用），不算引第三方库 —— 包体积一个字节都不涨。
 *
 * ## 思考（thinking）
 *
 * ```
 * off    → {"thinking":{"type":"disabled"}}，且**不发** reasoning_effort
 * low    → enabled + "low"
 * medium → enabled + "medium"
 * high   → enabled + "high"
 * ```
 * 两条硬事实（官方文档 + 实测）：
 *   1. **思考模式下 `temperature` 无效**（不报错，也不生效）→ 开着就**别发**，
 *      别让代码看起来像在调参；
 *   2. **带 `tools` 时历史里的 `reasoning_content` 必须原样回传**，漏一条直接 400。
 *      回传由 {@link Asker} 负责（它才知道哪些轮被保留了）。
 */
final class Brain {

    /** 一次工具调用。`args` 是**原始 JSON 字符串**（官方就是字符串，别再包一层）。 */
    static final class Call {
        String id;
        String name;
        String args;
    }

    /** 一轮的产出。三段可以同时存在：想了、说了、还要动手。 */
    static final class Resp {
        String text = "";
        String reasoning = "";
        java.util.List<Call> calls = new java.util.ArrayList<>(0);
        /** 被 max_tokens 截断了（界面可以提示"答案没说完"）。 */
        boolean truncated;

        boolean hasCalls() { return calls != null && !calls.isEmpty(); }
    }

    /** HTTP 失败。带状态码，好让上层区分"该降级"和"就是网络差"。 */
    static final class HttpError extends Exception {
        final int code;
        final String body;
        HttpError(int code, String body) {
            super("HTTP " + code + "：" + clip(body, 160));
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private Brain() { }

    /** 一次请求最多等多久（毫秒）。由 EyeService 在 applySettings 里按设置灌进来。 */
    private static volatile int readTimeoutMs = 60_000;

    /**
     * 正在飞的那条连接。**打断只能靠它**。
     *
     * `Thread.interrupt()` 对 `HttpURLConnection` 的读**完全无效**（它在 native socket 上阻塞），
     * 所以"用户又说话了要取消"如果不 disconnect，那条线程会一直挂到 readTimeout（最长 180 秒）。
     * 连发几句就是几个线程 + 几个 socket 堆着 —— 这就是"死线程堆积"的来源。
     */
    private static volatile HttpURLConnection inFlight;

    /**
     * "这次断连是**我们主动掐的**"。不区分的话，流式读到一半被自己 disconnect，
     * 会当成网络错误报给用户（"没答上：Socket closed"），而实际上他只是按了停止。
     */
    private static volatile boolean aborting;

    /** 掐掉当前请求（会让阻塞的 read 抛 IOException，线程立刻退出）。 */
    static void abort() {
        aborting = true;
        HttpURLConnection c = inFlight;
        inFlight = null;
        if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
    }

    static void setReadTimeoutSec(int sec) {
        readTimeoutMs = Math.max(10, Math.min(180, sec)) * 1000;
    }

    // ---------------------------------------------------------------- 发

    /**
     * 发一次，**同步、必须在后台线程调用**。
     *
     * @return 拆好的回答
     * @throws HttpError 4xx/5xx（上层据此判断要不要降级）
     */
    static Resp chat(Config cfg, JSONArray messages, String think, int maxTokens, JSONArray tools)
            throws Exception {
        aborting = false;
        JSONObject body = build(cfg.apiModel(), messages, think, maxTokens, tools);
        // 一行请求画像：排障时"到底发没发 thinking / 带没带 tools"看这一行就够，
        // 不必去抓包（请求体里全是 base64，抓了也读不动）
        EyeLog.w("brain", "think=" + think + " msgs=" + messages.length()
            + " tools=" + (tools == null ? 0 : tools.length())
            + " max=" + maxTokens + " chars=" + body.toString().length());
        String raw = post(cfg.apiBase(), cfg.apiKey(), body.toString(),
            // 大上下文要等更久：1M token 的输入光服务端预填就要几十秒。
            // 只在请求变大时**放宽**，绝不放宽普通过程的设置值。
            Math.max(readTimeoutMs, 20_000 + body.toString().length() / 30));
        return parse(raw);
    }

    /** 拼请求体。抽出来是为了让自测脚本能对着它做断言。 */
    static JSONObject build(String model, JSONArray messages, String think, int maxTokens,
                            JSONArray tools) throws Exception {
        return build(model, messages, think, maxTokens, tools, false);
    }

    static JSONObject build(String model, JSONArray messages, String think, int maxTokens,
                            JSONArray tools, boolean stream) throws Exception {
        JSONObject b = new JSONObject();
        b.put("model", model);
        b.put("max_tokens", maxTokens);
        b.put("messages", messages);
        b.put("stream", stream);

        boolean on = think != null && !"off".equals(think);
        JSONObject th = new JSONObject();
        th.put("type", on ? "enabled" : "disabled");
        b.put("thinking", th);
        if (on) {
            // off 时**不写这个键**：官方说"关掉思考还带 effort"是没定义的组合，不发最安全
            b.put("reasoning_effort", think);
        } else {
            // 思考关着的时候 temperature 才有意义（开着发了也白发，官方明说无效）
            b.put("temperature", 0.3);
        }
        if (tools != null && tools.length() > 0) b.put("tools", tools);
        return b;
    }

    private static String post(String base, String key, String body) throws Exception {
        return post(base, key, body, readTimeoutMs);
    }

    private static String post(String base, String key, String body, int timeoutMs) throws Exception {
        HttpURLConnection c = null;
        try {
            String url = trimSlash(base) + "/chat/completions";
            c = (HttpURLConnection) new URL(url).openConnection();
            inFlight = c;
            c.setRequestMethod("POST");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(Math.max(10_000, timeoutMs));
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Accept", "application/json");
            OutputStream out = c.getOutputStream();
            out.write(body.getBytes("UTF-8"));
            out.close();
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new HttpError(code, resp);
            return resp;
        } finally {
            if (inFlight == c) inFlight = null;
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    // ---------------------------------------------------------------- 流式

    /** 流式回调：每收到一小段正文就叫一次。**在后台线程上**，UI 要自己 post。 */
    interface StreamCb {
        void onDelta(String text);
    }

    /**
     * 一次流式请求的结果。
     *
     * `aborted` = 是**我们**掐的（用户按了停止），不是出错 —— 上层要拿 `partial`
     * 当"说到一半的回答"存下来，而不是报错。
     */
    static final class StreamResult {
        Resp resp = new Resp();
        String partial = "";
        boolean aborted;
        /** 服务端不认 `stream:true`（返回了整包 JSON），已经自动退回非流式。 */
        boolean fellBack;
    }

    /**
     * 发一次流式请求，边收边回调。
     *
     * ## 三个坑（都得处理，不然表现很怪）
     *
     * 1. **`tool_calls` 也是流式分片**：`index` 相同的是同一个调用，`arguments` 要
     *    **字符串累加**再整体 parse。拼错了就是"调了个不存在的工具"。
     * 2. **`reasoning_content` 是独立的一路**，要单独累加，否则"思考 N 秒"和下一轮回传都会缺。
     * 3. **一旦这一轮出现 `tool_calls`，已经吐给界面的字就是废的** —— 由上层
     *    撤掉临时气泡（见 `StreamResult.hasCalls()`）。豆包那种"话说一半突然改口"
     *    就是没处理这条。
     *
     * ## 降级
     *
     * 有的兼容端点不认 `stream`，会直接把整包 JSON 吐回来（不是 `data:` 开头）。
     * 这时候 `fellBack=true`，内容还在 `resp` 里，上层照常用。
     */
    static StreamResult streamChat(Config cfg, JSONArray messages, String think, int maxTokens,
                                   JSONArray tools, StreamCb cb) throws Exception {
        StreamResult out = new StreamResult();
        aborting = false;
        JSONObject body = build(cfg.apiModel(), messages, think, maxTokens, tools, true);
        EyeLog.w("brain", "stream think=" + think + " msgs=" + messages.length()
            + " tools=" + (tools == null ? 0 : tools.length()) + " chars=" + body.toString().length());

        HttpURLConnection c = null;
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        java.util.TreeMap<Integer, Call> calls = new java.util.TreeMap<>();
        StringBuilder rawAll = new StringBuilder();
        boolean gotData = false;

        try {
            String url = trimSlash(cfg.apiBase()) + "/chat/completions";
            c = (HttpURLConnection) new URL(url).openConnection();
            inFlight = c;
            c.setRequestMethod("POST");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(Math.max(readTimeoutMs, 20_000 + body.toString().length() / 30));
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Authorization", "Bearer " + cfg.apiKey());
            c.setRequestProperty("Accept", "text/event-stream");
            OutputStream o = c.getOutputStream();
            o.write(body.toString().getBytes("UTF-8"));
            o.close();

            int code = c.getResponseCode();
            if (code >= 400) throw new HttpError(code, read(c.getErrorStream()));

            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    if (rawAll.length() < 200_000) rawAll.append(line).append('\n');
                    continue;
                }
                String p = line.substring(5).trim();
                if ("[DONE]".equals(p)) break;
                if (p.length() == 0) continue;
                gotData = true;
                try { merge(p, content, reasoning, calls, cb); }
                catch (Throwable e) { EyeLog.w("brain", "跳过一帧坏数据: " + Brain.clip(p, 60)); }
            }
            if (!gotData) {
                // 不认 stream 的端点：整包 JSON 原样塞在 rawAll 里
                out.fellBack = true;
                out.resp = parse(rawAll.toString());
                out.partial = out.resp.text;
                if (out.resp.text.length() > 0 && cb != null) cb.onDelta(out.resp.text);
                return out;
            }
            out.resp.text = content.toString();
            out.resp.reasoning = reasoning.toString();
            out.resp.calls = new java.util.ArrayList<>(calls.values());
            out.partial = out.resp.text;
            return out;
        } catch (Throwable e) {
            boolean ours = aborting;
            if (ours) {
                out.aborted = true;
                out.partial = content.toString();
                out.resp.text = out.partial;
                out.resp.reasoning = reasoning.toString();
                return out;
            }
            // 还没吐出任何字就炸了 → 退回非流式再试一次（有些代理不让长连接）
            if (content.length() == 0) {
                EyeLog.w("brain", "流式失败，退回整包: " + e);
                out.fellBack = true;
                out.resp = chat(cfg, messages, think, maxTokens, tools);
                out.partial = out.resp.text;
                if (out.resp.text.length() > 0 && cb != null) cb.onDelta(out.resp.text);
                return out;
            }
            throw e;
        } finally {
            if (inFlight == c) inFlight = null;
            aborting = false;
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    /** 拆一帧 SSE 的 delta，累加进 content / reasoning / calls。 */
    private static void merge(String payload, StringBuilder content, StringBuilder reasoning,
                              java.util.TreeMap<Integer, Call> calls, StreamCb cb) throws Exception {
        JSONObject o = new JSONObject(payload);
        JSONArray ch = o.optJSONArray("choices");
        if (ch == null || ch.length() == 0) return;
        JSONObject d = ch.getJSONObject(0).optJSONObject("delta");
        if (d == null) return;

        String t = str(d, "content");
        if (t.length() > 0) {
            content.append(t);
            if (cb != null) cb.onDelta(t);
        }
        String rs = str(d, "reasoning_content");
        if (rs.length() == 0) rs = str(d, "reasoning");
        if (rs.length() > 0) reasoning.append(rs);

        JSONArray tc = d.optJSONArray("tool_calls");
        if (tc == null) return;
        for (int i = 0; i < tc.length(); i += 1) {
            JSONObject t1 = tc.optJSONObject(i);
            if (t1 == null) continue;
            int idx = t1.optInt("index", i);
            Call call = calls.get(idx);
            if (call == null) { call = new Call(); calls.put(idx, call); }
            String id = t1.optString("id", "");
            if (id.length() > 0) call.id = id;
            JSONObject fn = t1.optJSONObject("function");
            if (fn == null) continue;
            String name = fn.optString("name", "");
            if (name.length() > 0) call.name = name;
            String args = str(fn, "arguments");
            if (args.length() > 0) {
                if (call.args == null) call.args = "";
                call.args += args;                  // ⚠️ 分片累加，不能覆盖
            }
        }
    }

    // ---------------------------------------------------------------- 拆

    static Resp parse(String raw) throws Exception {
        Resp r = new Resp();
        if (raw == null || raw.trim().length() == 0) throw new Exception("空响应");
        JSONObject o = new JSONObject(raw);
        JSONArray choices = o.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            // 有的兼容端点会把错误塞在 200 里
            String err = o.optString("error", "");
            throw new Exception(err.length() > 0 ? clip(err, 200) : "响应里没有 choices");
        }
        JSONObject ch = choices.getJSONObject(0);
        JSONObject msg = ch.optJSONObject("message");
        if (msg == null) throw new Exception("响应里没有 message");
        r.text = str(msg, "content");
        // 官方字段名是 reasoning_content；个别兼容端点叫 reasoning
        r.reasoning = str(msg, "reasoning_content");
        if (r.reasoning.length() == 0) r.reasoning = str(msg, "reasoning");

        JSONArray tc = msg.optJSONArray("tool_calls");
        if (tc != null) {
            for (int i = 0; i < tc.length(); i += 1) {
                JSONObject t = tc.optJSONObject(i);
                if (t == null) continue;
                JSONObject fn = t.optJSONObject("function");
                if (fn == null) continue;
                Call c = new Call();
                c.id = t.optString("id", "call_" + i);
                c.name = fn.optString("name", "");
                c.args = fn.optString("arguments", "{}");
                if (c.name.length() == 0) continue;
                r.calls.add(c);
            }
        }
        String fin = ch.optString("finish_reason", "");
        r.truncated = "length".equals(fin);
        return r;
    }

    /**
     * 安全取一个字符串字段。
     *
     * ⚠️ **必须走这里而不是 `optString`**：模型"只调工具、不说话"时，
     * `content` 是 JSON 的 `null`。`org.json` 的 `optString` 对 NULL 会返回字符串
     * `"null"`（`JSON.toString(NULL)` 的字面量），于是界面上会出现一条回答写着 "null"。
     */
    static String str(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return "";
        String s = String.valueOf(v);
        return "null".equals(s) ? "" : s;
    }

    /** 带兜底值的版本（`optString(key, def)` 的安全替代）。 */
    static String str(JSONObject o, String key, String def) {
        String s = str(o, key);
        return s.length() == 0 ? def : s;
    }

    // ---------------------------------------------------------------- 摘要

    /**
     * 把一段"要被裁掉的旧对话"压成前情摘要。**同步、必须在后台线程调用。**
     *
     * 每次只压**还没压过的那一段**（水位由调用方管），压完拼在已有摘要后面。
     * 所以压缩成本是"每丢一批付一次"，不会每问都付。
     *
     * 摘要**不带思考**：它是机械活，想了也不会更好，只会更慢更贵。
     */
    static String summarize(Config cfg, String oldText, int maxChars) {
        if (oldText == null || oldText.trim().length() == 0) return null;
        String sys = "你在给一段对话写前情摘要，供后续继续对话时当背景。要求：不超过 "
            + maxChars + " 字；只保留事实（谁、做了什么、结论、用户明确说过的偏好或数据）；"
            + "不要评论、不要分点符号、不要客套话、不要复述原文。";
        try {
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", sys));
            msgs.put(new JSONObject().put("role", "user").put("content", oldText));
            JSONObject body = build(cfg.apiModel(), msgs, "off", Math.max(300, maxChars * 2), null);
            String resp = post(cfg.apiBase(), cfg.apiKey(), body.toString());
            Resp r = parse(resp);
            String content = r.text == null ? "" : r.text.trim();
            return content.length() == 0 ? null : content;
        } catch (Throwable e) {
            EyeLog.w("brain", "摘要失败: " + e);
            return null;
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static String trimSlash(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t.length() == 0 ? Config.DEFAULT_BASE : t;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        String t = s.replace('\n', ' ');
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }
}
