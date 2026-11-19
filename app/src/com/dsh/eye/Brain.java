package com.dsh.eye;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 直连 DeepSeek：一次 HTTP 请求 = 一轮对话。
 *
 * 这一层只做四件最无聊的事：**拼 URL、发字节、读回来、把回答拆成几段**
 * （正文 / 思考过程 / 工具调用 / 搜索步骤 / 引用 / 标题）。
 * **它不认识上下文，也不认识工具** —— 那些是 {@link Asker} 的事。
 * 这么切是因为"哪一轮说了什么"属于会话逻辑，而"HTTP 会不会超时"属于传输逻辑，
 * 混在一起改一个就会碰坏另一个。
 *
 * ## 请求形状只有一个来源
 *
 * v5 之后 {@link #build(Config, Caps.Plan, JSONArray, boolean)} **只接受
 * {@link Caps.Plan}**（T1 已经把"要不要带 tools / title / thinking / temperature / 文档"
 * 全部收敛到那里）。本类里**不许**再写 {@code if (mode == WEB)} —— 一旦同一个判断散在
 * 两处，就一定会出现"界面允许、请求不让"或者反过来。
 *
 * ## 为什么用 org.json
 *
 * 手拼字符串在 v3 之前够用（只有文本 + base64 图）。v4 要解析 `tool_calls`
 * （`arguments` 是一个**装着 JSON 的字符串**，里面全是转义），手写扫描器第一个
 * 带引号的参数就会错。`org.json` 是 **Android 框架自带的**（`android.jar` 里就有，
 * `Tuning` 一直在用），不算引第三方库 —— 包体积一个字节都不涨。
 *
 * ## T0 探针实测（这四条决定了下面的解析路径）
 *
 * | 事实 | 影响 |
 * |---|---|
 * | `server_tool_event` 挂在 **`choices[0]` 顶层** | 先读 choice，再读 delta（两边都读） |
 * | 最后一帧的 **`delta`** 同时含 `server_tool_calls` + `annotations` + `title` | 见到就收，收到 finish 即收尾 |
 * | `title:{"enabled":true}` 在 `/chat/completions` **生效** | 联网模式随本轮带，不用独立轻请求 |
 * | `server_tool_calls` 的 `signature` 是几十 KB 的加密正文，**改一个字都不行** | 原样 `toString()` 存、原样回传，绝不重新拼装 |
 *
 * ## 思考（thinking）
 *
 * ```
 * off    → {"thinking":{"type":"disabled"}}，且**不发** reasoning_effort，发 temperature
 * auto   → enabled，**不发** reasoning_effort（让服务端自己定）
 * high   → enabled + "high"
 * max    → enabled + "max"
 * ```
 *
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
        List<Call> calls = new ArrayList<Call>(0);
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
        boolean on = thinkingOf(think);
        JSONObject body = assemble(cfg.apiModel(), maxTokens, messages, false,
            on, effortOf(think), !on, tools, false);
        EyeLog.w("brain", "chat think=" + think + " msgs=" + messages.length()
            + " tools=" + (tools == null ? 0 : tools.length())
            + " max=" + maxTokens + " chars=" + body.toString().length());
        String raw = post(cfg.apiBase(), cfg.apiKey(), body.toString(),
            // 大上下文要等更久：1M token 的输入光服务端预填就要几十秒。
            // 只在请求变大时**放宽**，绝不放宽普通过程的设置值。
            10_000, Math.max(readTimeoutMs, 20_000 + body.toString().length() / 30));
        return parse(raw);
    }

    /**
     * 拼请求体（v5 正路）：**形状全听 {@link Caps.Plan}**。
     *
     * @param plan null 时按"聊天 + 当前档位 + 不带工具"兜底（老调用点/自测脚本用）
     */
    static JSONObject build(Config cfg, Caps.Plan plan, JSONArray messages, boolean stream)
            throws Exception {
        Caps.Plan p = plan != null ? plan
            : Caps.plan(Caps.Mode.CHAT, cfg.think(), cfg.apiModel(),
                false, false, true, cfg.allowDocsInWeb(), cfg.web());
        return assemble(cfg.apiModel(), cfg.maxTokens(), messages, stream,
            p.thinking, p.effort, p.sendTemperature, toolsOf(p), p.title);
    }

    /**
     * 档位原文 → 是否开思考。
     *
     * ⚠️ 老调用点直接传 `cfg.think()`，而默认档位是 **`auto`** —— 原样写进
     * `reasoning_effort` 会发出 {@code "auto"} 这个官方不认的值（400）。
     * 所以老路径也必须过这里归一。
     */
    private static boolean thinkingOf(String think) {
        return !Caps.EFF_OFF.equals(Caps.normEffort(think));
    }

    /**
     * 档位原文 → `reasoning_effort` 的值。**`auto` / `off` 都返回 null（这个键根本不写）**。
     *
     * 官方对 `auto` 的定义是"让服务端自己定"，写一个 `"auto"` 字面量反而不合法；
     * `off` 时更不能写 effort（关掉思考还带 effort 是没定义的组合）。
     */
    private static String effortOf(String think) {
        String e = Caps.normEffort(think);
        if (Caps.EFF_AUTO.equals(e) || Caps.EFF_OFF.equals(e)) return null;
        return e;
    }

    /**
     * 拼请求体的**唯一内核**。所有写入 `thinking / reasoning_effort / temperature /
     * tools / title` 的地方都在这一个方法里 —— 别在别处再拼一次。
     */
    private static JSONObject assemble(String model, int maxTokens, JSONArray messages,
                                       boolean stream, boolean thinking, String effort,
                                       boolean sendTemperature, JSONArray tools, boolean title)
            throws Exception {
        JSONObject b = new JSONObject();
        b.put("model", model);
        b.put("max_tokens", maxTokens);
        b.put("messages", messages == null ? new JSONArray() : messages);
        b.put("stream", stream);

        // thinking 恒发：不写这个键在不同端点上默认值不一样，
        // 写死成 enabled/disabled 才能让"关掉思考"真的关掉。
        JSONObject th = new JSONObject();
        th.put("type", thinking ? "enabled" : "disabled");
        b.put("thinking", th);
        // auto / off 都**不写**这个键：关思考还带 effort 是官方没定义的组合，不发最安全
        if (effort != null && effort.length() > 0) b.put("reasoning_effort", effort);
        // 思考开着时 temperature 无效（官方明说），所以只在关思考时发
        if (sendTemperature) b.put("temperature", 0.3);

        if (tools != null && tools.length() > 0) b.put("tools", tools);
        // title 只可能来自联网模式（与 function tools 互斥，闸在 Caps.plan）
        if (title) b.put("title", new JSONObject().put("enabled", true));
        // safety_check 永远不发（v5 不用它）

        reqCheck(b);
        return b;
    }

    /**
     * 把 Plan 翻成 `tools[]`。**这是 function 与服务端工具的唯一装配点。**
     *
     * 结构上不混用（{@link Caps#mixed} 保证），这里再加一层"按 kind 分支"，
     * 让混用连写都写不出来。
     */
    private static JSONArray toolsOf(Caps.Plan p) throws Exception {
        if (p == null) return null;
        if (p.kind == Caps.Plan.Kind.SERVER) {
            JSONArray a = new JSONArray();
            if (p.allowWebSearch) a.put(new JSONObject().put("type", "deepseek_web_search"));
            if (p.allowReadUrl) a.put(new JSONObject().put("type", "deepseek_read_url"));
            return a;
        }
        if (p.kind == Caps.Plan.Kind.CLIENT) {
            // 混合模式（Agent + 联网）多一个 web_search function；普通聊天/Agent 就是原样
            return p.allowToolsFunction ? Tools.spec(p.hybrid) : null;
        }
        return null;
    }

    /**
     * **混合模式专用的联网搜索**：直接打官方的原子搜索接口
     * `POST /v1/tools/search`，把结构化结果当工具结果返回。
     *
     * ## 为什么不再"内部再发一次聊天"
     *
     * 早期版本是让内部再发一次 `deepseek_web_search` 的 Chat Completions，
     * 靠模型把网页内容总结一遍。两个问题：多一次模型往返（实测 3.3s）、
     * 结果被总结过一遍（Agent 拿不到原始摘要）。
     *
     * 官方现在开放了独立的 `/v1/tools/search`：body 只有 `{"query":"…"}`，
     * 返回 `results[]`（title / site_name / url / summary / date / authority），
     * 按次计费。直接用它：快、便宜、结构化。
     *
     * ## 约束
     * 1. **不抛**：工具失败返回 `{"error":"人话"}`，让 Agent 自己决定要不要重试；
     * 2. **不流式回调**：结果只进上下文，不提前播给用户；
     * 3. **不产生 `Proto.Step`**：外层的 `web_search` 工具调用已经由 `Asker` 发步骤，
     *    这里再发一次会让界面画两行。
     *
     * @return `{"answer":"1. 标题 — 站点（日期）\n摘要\nURL\n…","sources":[…]}`
     *         或 `{"error":"…"}`
     */
    static String search(Config cfg, String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() > 200) q = q.substring(0, 200);
        if (cfg == null) return "{\"error\":\"没有配置，搜不了\"}";
        if (q.length() == 0) return "{\"error\":\"没给搜索词\"}";
        long t0 = System.currentTimeMillis();
        try {
            JSONObject req = new JSONObject();
            req.put("query", q);
            String raw = postUrl(trimSlash(cfg.apiBase()) + "/v1/tools/search",
                cfg.apiKey(), req.toString(), 10_000, 25_000);
            JSONObject o = new JSONObject(raw);
            JSONArray rs = o.optJSONArray("results");
            if (rs == null || rs.length() == 0) {
                EyeLog.w("search", "q=" + clip(q, 40) + " results=0");
                return "{\"error\":\"没有搜到结果\"}";
            }
            StringBuilder text = new StringBuilder();
            JSONArray src = new JSONArray();
            int n = Math.min(rs.length(), 8);
            for (int i = 0; i < n; i += 1) {
                JSONObject r = rs.optJSONObject(i);
                if (r == null) continue;
                String title = r.optString("title", "").trim();
                String site = r.optString("site_name", "").trim();
                String url = r.optString("url", "").trim();
                String date = r.optString("date", "").trim();
                String sum = r.optString("summary", "").trim();
                if (sum.length() > 600) sum = sum.substring(0, 600) + "…";
                if (text.length() > 0) text.append("\n\n");
                text.append(i + 1).append(". ").append(title);
                if (site.length() > 0) text.append(" — ").append(site);
                if (date.length() > 0) text.append("（").append(date).append("）");
                if (sum.length() > 0) text.append("\n").append(sum);
                if (url.length() > 0) text.append("\n").append(url);
                src.put(new JSONObject().put("title", title).put("url", url).put("site", site));
            }
            JSONObject out = new JSONObject();
            out.put("answer", text.toString());
            out.put("sources", src);
            EyeLog.w("search", "q=" + clip(q, 40) + " results=" + n
                + " chars=" + text.length() + " ms=" + (System.currentTimeMillis() - t0));
            return out.toString();
        } catch (Throwable e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            EyeLog.w("search", "q=" + clip(q, 40) + " 失败：" + clip(why, 120));
            // 人话：把技术细节留在日志里，别把 "HTTP 500: {...}" 喂给模型
            String human = e instanceof HttpError ? Err.cta(Err.of(((HttpError) e).code, ((HttpError) e).body))
                : e instanceof java.net.SocketTimeoutException ? Err.cta(Err.Code.TIMEOUT)
                : e instanceof java.io.IOException ? Err.cta(Err.Code.OFFLINE)
                : "联网搜索失败";
            try {
                return new JSONObject().put("error", human).toString();
            } catch (Throwable ignored) {
                return "{\"error\":\"联网搜索失败\"}";
            }
        }
    }

    /**
     * **混合模式专用的网页读取**：官方原子接口 `POST /v1/tools/fetch`。
     *
     * 请求体只有 `{"url":"…"}`，返回 `{"content":"…"}`（已去导航/广告的正文）。
     * 比"内部再发一次聊天让它总结网页"快得多，也不会被模型改写成二手信息。
     *
     * 约束：不抛（工具失败返回人话 error）、不回调、不产生 `Proto.Step`。
     *
     * @return `{"answer":"正文（截断 8000 字）","sources":[{"title":域名,"url":…}]}`
     *         或 `{"error":"…"}`
     */
    static String fetch(Config cfg, String url) {
        String u = url == null ? "" : url.trim();
        if (u.length() > 2000) u = u.substring(0, 2000);
        if (cfg == null) return "{\"error\":\"没有配置，读不了\"}";
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            return "{\"error\":\"只支持 http/https 链接\"}";
        }
        long t0 = System.currentTimeMillis();
        try {
            JSONObject req = new JSONObject();
            req.put("url", u);
            String raw = postUrl(trimSlash(cfg.apiBase()) + "/v1/tools/fetch",
                cfg.apiKey(), req.toString(), 10_000, 30_000);
            JSONObject o = new JSONObject(raw);
            String content = o.optString("content", "").trim();
            if (content.length() == 0) return "{\"error\":\"这一页没有正文\"}";
            if (content.length() > 8000) content = content.substring(0, 8000) + "…";
            JSONObject out = new JSONObject();
            out.put("answer", content);
            JSONArray src = new JSONArray();
            src.put(new JSONObject().put("title", hostOf(u)).put("url", u));
            out.put("sources", src);
            EyeLog.w("fetch", "url=" + clip(u, 80) + " chars=" + content.length()
                + " ms=" + (System.currentTimeMillis() - t0));
            return out.toString();
        } catch (Throwable e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            EyeLog.w("fetch", "url=" + clip(u, 80) + " 失败：" + clip(why, 120));
            String human = e instanceof HttpError ? Err.cta(Err.of(((HttpError) e).code, ((HttpError) e).body))
                : e instanceof java.net.SocketTimeoutException ? Err.cta(Err.Code.TIMEOUT)
                : e instanceof java.io.IOException ? Err.cta(Err.Code.OFFLINE)
                : "网页读取失败";
            try {
                return new JSONObject().put("error", human).toString();
            } catch (Throwable ignored) {
                return "{\"error\":\"网页读取失败\"}";
            }
        }
    }

    /** `https://a.b.com/x` → `a.b.com`（给步骤条/来源用）。 */
    static String hostOf(String u) {
        try {
            String h = new java.net.URL(u).getHost();
            return h == null || h.length() == 0 ? u : h;
        } catch (Throwable e) {
            return u;
        }
    }

    /**
     * 从 `annotations[]` 里取来源（**去重、最多 8 条**）。
     *
     * ⚠️ 官方形状是嵌一层的 `{"type":"url_citation","url_citation":{url,title}}`；
     * 兼容端点可能给扁平形状，所以取不到时退回 `a` 本身（和 `Asker.buildCites` 同口径）。
     */
    private static JSONArray sourcesOf(StreamResult sr) throws Exception {
        JSONArray out = new JSONArray();
        JSONArray ann = sr == null ? null : sr.annotations;
        if (ann == null || ann.length() == 0) return out;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        for (int i = 0; i < ann.length() && out.length() < 8; i += 1) {
            JSONObject a = ann.optJSONObject(i);
            if (a == null) continue;
            JSONObject c = a.optJSONObject("url_citation");
            if (c == null) c = a;
            String url = c.optString("url", "");
            if (url.length() == 0) continue;
            if (!seen.add(url)) continue;
            out.put(new JSONObject().put("url", url).put("title", c.optString("title", "")));
        }
        return out;
    }

    /**
     * **发请求之前的最后一道闸**：把"混用"变成不会发出去的异常。
     *
     * 官方的答复是 1~2 秒后一个 400（`cannot be used together`），用户看到的是
     * "没答上"。这里直接在本地抛 {@code IllegalStateException("MIXED_TOOLS")}：
     * 一个字节都不发，日志里能一眼看出是 App 的 bug 而不是网络问题。
     *
     * 两条规则：
     *   1. `tools[]` 里同时有 `function` 与 `deepseek_web_search/deepseek_read_url` → 抛；
     *   2. 有 `function` 且 body 里带了 `title` / `safety_check` → 抛（同样互斥）。
     */
    static void reqCheck(JSONObject body) {
        if (body == null) return;
        JSONArray tools = body.optJSONArray("tools");
        boolean fn = false;
        boolean srv = false;
        if (tools != null) {
            for (int i = 0; i < tools.length(); i += 1) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) continue;
                String type = str(t, "type");
                if ("function".equals(type)) fn = true;
                else if ("deepseek_web_search".equals(type)
                    || "deepseek_read_url".equals(type)) srv = true;
            }
        }
        if (fn && srv) throw new IllegalStateException("MIXED_TOOLS");
        if (fn && (body.has("title") || body.has("safety_check"))) {
            throw new IllegalStateException("MIXED_TOOLS");
        }
    }

    /** 老签名（自测脚本 / 兼容端点用）：不经过 Plan，直接给字段。 */
    static JSONObject build(String model, JSONArray messages, String think, int maxTokens,
                            JSONArray tools) throws Exception {
        return build(model, messages, think, maxTokens, tools, false);
    }

    static JSONObject build(String model, JSONArray messages, String think, int maxTokens,
                            JSONArray tools, boolean stream) throws Exception {
        boolean on = thinkingOf(think);
        return assemble(model, maxTokens, messages, stream,
            on, effortOf(think), !on, tools, false);
    }

    private static String post(String base, String key, String body) throws Exception {
        return post(base, key, body, 10_000, readTimeoutMs);
    }

    private static String post(String base, String key, String body, int timeoutMs)
            throws Exception {
        return post(base, key, body, 10_000, timeoutMs);
    }

    private static String post(String base, String key, String body, int connectMs, int readMs)
            throws Exception {
        return postUrl(trimSlash(base) + "/chat/completions", key, body, connectMs, readMs);
    }

    /**
     * 任意**绝对 URL** 的 POST。
     *
     * `post(...)` 一直把路径写死成 `/chat/completions`；官方搜索是
     * `/v1/tools/search`，不能再走那条。抽出来以后，两者共用同一套超时/错误处理。
     */
    private static String postUrl(String url, String key, String body, int connectMs, int readMs)
            throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            inFlight = c;
            c.setRequestMethod("POST");
            c.setConnectTimeout(Math.max(1_000, connectMs));
            c.setReadTimeout(Math.max(1_000, readMs));
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

    /**
     * 流式回调：每一小段就叫一次。**在后台线程上**，UI 要自己 post 回主线程。
     *
     * 四个新回调都是 `default` —— 老的匿名类（`CallActivity` 那几条）一行都不用改。
     */
    interface StreamCb {
        /** 正文增量。 */
        void onDelta(String text);

        /** 思考增量（`reasoning_content`）。默认丢弃 —— 只有要显示"思考过程"的界面才需要。 */
        default void onReasoning(String text) { }

        /**
         * 思考结束（第一次开始吐正文时）。只回调一次。
         *
         * `ms` = 从第一段 reasoning 到最后一段 reasoning 的**净思考时间**，
         * 不含等网络的时间，所以比"整轮耗时"诚实。
         */
        default void onThinkDone(long ms) { }

        /** 服务端工具的一步（`call_started` / `call_completed` / `step_reasoning_done`）。 */
        default void onStep(Proto.Step s) { }

        /** 服务端随本轮给的会话标题（联网模式且 `title.enabled` 时才有）。 */
        default void onTitle(String title) { }
    }

    /**
     * 上传进度回调。
     *
     * `aborted()` 每 64KB 查一次 —— 100MB 的文件光写 socket 就要几十秒，
     * 用户点了取消必须**当轮就停**，不能等写完再说。
     */
    interface ProgressCb {
        void onProgress(long sent, long total);
        boolean aborted();
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

        // ── v5 ────────────────────────────────────────────────────────────
        /**
         * `server_tool_calls` 的**原始 JSON 文本**。
         *
         * ⚠️ 原样存（`JSONArray.toString()`），**绝不重新拼装** —— `signature` 是
         * 几十 KB 的加密页面正文，改一个字服务端不报错但网页内容静默丢失，模型开始编。
         */
        String stcJson;
        /** 引用（`delta.annotations`）。可能为 null。 */
        JSONArray annotations;
        /** 会话标题（`delta.title` / `message.title`）。可能为 null。 */
        String title;
        /** `stop` / `length` / `content_filter` / `refusal` / …（可能为空）。 */
        String finish;
        /** 搜索步骤（按到达顺序）。 */
        List<Proto.Step> steps = new ArrayList<Proto.Step>(0);
        /** 净思考耗时（毫秒，0 = 没思考或没记录）。 */
        long thinkMs;
        int promptTokens;
        int completionTokens;
    }

    /**
     * 发一次流式请求，边收边回调。
     *
     * ## 五个坑（都得处理，不然表现很怪）
     *
     * 1. **`tool_calls` 也是流式分片**：`index` 相同的是同一个调用，`arguments` 要
     *    **字符串累加**再整体 parse。拼错了就是"调了个不存在的工具"。
     * 2. **`reasoning_content` 是独立的一路**，要单独累加，否则"思考 N 秒"和下一轮回传都会缺。
     * 3. **一旦这一轮出现 `tool_calls`，已经吐给界面的字就是废的** —— 由上层
     *    撤掉临时气泡（见 `StreamResult.resp.hasCalls()`）。
     * 4. **`server_tool_event` 在 `choices[0]` 顶层**，不在 delta 里（T0 实测）。
     *    delta 里也读一遍只是为了兼容端点，不改变主路径。
     * 5. **顶层 `error` 帧**（200 里塞错误）必须当异常抛，否则会被当成"这一帧没内容"跳过。
     *
     * ## 降级
     *
     * 有的兼容端点不认 `stream`，会把整包 JSON 直接吐回来（不是 `data:` 开头）。
     * 这时候 `fellBack=true`，内容还在 `resp` 里，上层照常用。
     */
    static StreamResult streamChat(Config cfg, Caps.Plan plan, JSONArray messages, StreamCb cb)
            throws Exception {
        aborting = false;
        JSONObject body = build(cfg, plan, messages, true);
        // 排障用：把**实际发出去的** tools 打出来（plan 说 search=true 不等于 body 里真有）
        JSONArray tj = body.optJSONArray("tools");
        StringBuilder tn = new StringBuilder();
        if (tj != null) {
            for (int i = 0; i < tj.length(); i += 1) {
                JSONObject o = tj.optJSONObject(i);
                if (o == null) continue;
                if (tn.length() > 0) tn.append(',');
                JSONObject f = o.optJSONObject("function");
                tn.append(f == null ? str(o, "type") : str(f, "name"));
            }
        }
        EyeLog.w("brain", "stream " + (plan == null ? "plan=-" : plan.toString())
            + " tools=" + (tj == null ? 0 : tj.length()) + "[" + tn + "]"
            + " msgs=" + messages.length() + " chars=" + body.toString().length());
        return streamBody(cfg, body, cb);
    }

    /** 老签名（不经过 Plan）：内部按"think + tools"造一个等价请求体。 */
    static StreamResult streamChat(Config cfg, JSONArray messages, String think, int maxTokens,
                                   JSONArray tools, StreamCb cb) throws Exception {
        aborting = false;
        boolean on = thinkingOf(think);
        JSONObject body = assemble(cfg.apiModel(), maxTokens, messages, true,
            on, effortOf(think), !on, tools, false);
        EyeLog.w("brain", "stream(legacy) think=" + think + " msgs=" + messages.length()
            + " tools=" + (tools == null ? 0 : tools.length())
            + " chars=" + body.toString().length());
        return streamBody(cfg, body, cb);
    }

    /** 攒帧用的累加器。一帧一帧喂进来，最后一次性填进 {@link StreamResult}。 */
    private static final class Acc {
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final TreeMap<Integer, Call> calls = new TreeMap<Integer, Call>();
        final List<Proto.Step> steps = new ArrayList<Proto.Step>(0);
        String stcJson;
        JSONArray annotations;
        String title;
        String finish;
        int promptTokens;
        int completionTokens;
        /** 这一段思考是什么时候开始的（0 = 当前不在思考）。 */
        long thinkFrom;
        long thinkMs;
        boolean thinkDone;
    }

    private static StreamResult streamBody(Config cfg, JSONObject body, StreamCb cb)
            throws Exception {
        StreamResult out = new StreamResult();
        Acc acc = new Acc();
        StringBuilder rawAll = new StringBuilder();
        boolean gotData = false;
        HttpURLConnection c = null;
        try {
            String url = trimSlash(cfg.apiBase()) + "/chat/completions";
            c = (HttpURLConnection) new URL(url).openConnection();
            inFlight = c;
            c.setRequestMethod("POST");
            c.setConnectTimeout(15_000);
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
                try {
                    merge(p, acc, cb);
                } catch (HttpError he) {
                    throw he;                       // 顶层 error 帧：直接冒泡，别吞
                } catch (Throwable e) {
                    // 带上异常本身：只打帧前缀的话，"跳过了"永远不知道为什么
                    EyeLog.w("brain", "跳过一帧坏数据: " + e + " | " + Brain.clip(p, 300));
                }
            }
            if (!gotData) {
                // 不认 stream 的端点：整包 JSON 原样塞在 rawAll 里
                out.fellBack = true;
                out.resp = parse(rawAll.toString());
                absorbNonStream(out, rawAll.toString());
                out.partial = out.resp.text;
                if (out.resp.text.length() > 0 && cb != null) cb.onDelta(out.resp.text);
                return out;
            }
            fill(out, acc);
            return out;
        } catch (HttpError he) {
            throw he;                               // 4xx/5xx 不降级：降级只会把原因盖掉
        } catch (Throwable e) {
            boolean ours = aborting;
            if (ours) {
                out.aborted = true;
                out.partial = acc.content.toString();
                out.resp.text = out.partial;
                out.resp.reasoning = acc.reasoning.toString();
                out.resp.calls = new ArrayList<Call>(acc.calls.values());
                out.stcJson = acc.stcJson;
                out.title = acc.title;
                out.steps = acc.steps;
                out.thinkMs = acc.thinkMs;
                return out;
            }
            // 还没吐出任何字就炸了 → 退回非流式再试一次（有些代理不让长连接）
            if (acc.content.length() == 0) {
                EyeLog.w("brain", "流式失败，退回整包: " + e);
                JSONObject ns = new JSONObject(body.toString());
                ns.put("stream", false);
                String raw = post(cfg.apiBase(), cfg.apiKey(), ns.toString());
                out.fellBack = true;
                out.resp = parse(raw);
                absorbNonStream(out, raw);
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

    /** 累加器 → 结果。字段一个都不能漏（漏 `stcJson` 就等于这一轮没搜过）。 */
    private static void fill(StreamResult out, Acc acc) {
        out.resp.text = acc.content.toString();
        out.resp.reasoning = acc.reasoning.toString();
        out.resp.calls = new ArrayList<Call>(acc.calls.values());
        out.resp.truncated = "length".equals(acc.finish);
        out.partial = out.resp.text;
        out.stcJson = acc.stcJson;
        out.annotations = acc.annotations;
        out.title = acc.title;
        out.finish = acc.finish;
        out.steps = acc.steps;
        out.thinkMs = acc.thinkMs;
        out.promptTokens = acc.promptTokens;
        out.completionTokens = acc.completionTokens;
    }

    /**
     * 拆一帧 SSE，累加进累加器。
     *
     * 层路径（T0 实测）：
     * ```
     * choices[0].server_tool_event      ← 步骤事件
     * choices[0].delta.content          ← 正文
     * choices[0].delta.reasoning_content← 思考
     * choices[0].delta.tool_calls       ← 自定义工具（分片累加）
     * choices[0].delta.server_tool_calls← 服务端工具，原样存
     * choices[0].delta.annotations      ← 引用
     * choices[0].delta.title            ← 会话标题
     * choices[0].finish_reason          ← 收尾
     * usage                             ← token 数
     * ```
     */
    private static void merge(String payload, Acc acc, StreamCb cb) throws Exception {
        JSONObject o = new JSONObject(payload);

        // 顶层 error 帧（200 里塞错误）：当异常抛，别当"没内容"跳过
        JSONObject err = o.optJSONObject("error");
        if (err != null) {
            int code = err.optInt("code", 0);
            if (code < 400 || code > 599) code = 400;
            throw new HttpError(code, err.toString());
        }

        JSONObject usage = o.optJSONObject("usage");
        if (usage != null) {
            acc.promptTokens = usage.optInt("prompt_tokens", acc.promptTokens);
            acc.completionTokens = usage.optInt("completion_tokens", acc.completionTokens);
        }

        JSONArray ch = o.optJSONArray("choices");
        if (ch == null || ch.length() == 0) return;
        JSONObject c0 = ch.optJSONObject(0);
        if (c0 == null) return;

        JSONObject d = c0.optJSONObject("delta");

        // ① server_tool_event：T0 实测在 choice 顶层；delta 里也读一遍只为兼容
        JSONObject ste = c0.optJSONObject("server_tool_event");
        if (ste == null && d != null) ste = d.optJSONObject("server_tool_event");
        if (ste != null) {
            Proto.Step s = stepOf(ste, acc.steps.size());
            acc.steps.add(s);
            if (cb != null) cb.onStep(s);
        }

        if (d == null) d = new JSONObject();
        long now = System.currentTimeMillis();

        // ② 思考
        String rs = str(d, "reasoning_content");
        if (rs.length() == 0) rs = str(d, "reasoning");
        if (rs.length() > 0) {
            if (acc.thinkFrom == 0) acc.thinkFrom = now;
            acc.reasoning.append(rs);
            if (cb != null) cb.onReasoning(rs);
        }

        // ③ 正文（第一段正文到达 = 思考结束）
        String t = str(d, "content");
        if (t.length() > 0) {
            if (acc.thinkFrom > 0) {
                acc.thinkMs += now - acc.thinkFrom;
                acc.thinkFrom = 0;
                if (!acc.thinkDone) {
                    acc.thinkDone = true;
                    if (cb != null) cb.onThinkDone(acc.thinkMs);
                }
            }
            acc.content.append(t);
            if (cb != null) cb.onDelta(t);
        }

        // ④ 会话标题（与 annotations[].title 无关：那是"来源标题"）
        String ti = str(d, "title");
        if (ti.length() == 0) ti = str(c0, "title");
        if (ti.length() > 0) {
            acc.title = ti;
            if (cb != null) cb.onTitle(ti);
        }

        // ⑤ 服务端工具（原样存字符串；绝不动 signature）
        JSONArray stc = d.optJSONArray("server_tool_calls");
        if (stc == null) stc = c0.optJSONArray("server_tool_calls");
        if (stc != null && stc.length() > 0) acc.stcJson = stc.toString();

        // ⑥ 引用
        JSONArray ann = d.optJSONArray("annotations");
        if (ann == null) ann = c0.optJSONArray("annotations");
        if (ann != null && ann.length() > 0) acc.annotations = ann;

        // ⑦ 收尾
        String fin = str(c0, "finish_reason");
        if (fin.length() > 0) acc.finish = fin;

        // ⑧ 自定义工具（分片累加）
        JSONArray tc = d.optJSONArray("tool_calls");
        if (tc == null) return;
        for (int i = 0; i < tc.length(); i += 1) {
            JSONObject t1 = tc.optJSONObject(i);
            if (t1 == null) continue;
            int idx = t1.optInt("index", i);
            Call call = acc.calls.get(Integer.valueOf(idx));
            if (call == null) {
                call = new Call();
                acc.calls.put(Integer.valueOf(idx), call);
            }
            String id = str(t1, "id");
            if (id.length() > 0) call.id = id;
            JSONObject fn = t1.optJSONObject("function");
            if (fn == null) continue;
            String name = str(fn, "name");
            if (name.length() > 0) call.name = name;
            String args = str(fn, "arguments");
            if (args.length() > 0) {
                if (call.args == null) call.args = "";
                call.args += args;                  // ⚠️ 分片累加，不能覆盖
            }
        }
    }

    /**
     * `server_tool_event` → {@link Proto.Step}。
     *
     * T0 实测形状：`{type, call:{id, type, kind, action, status, sources, ...}}`。
     * 少数端点把字段摊在事件本身，所以两条路都读 —— 界面上少一个字段是"卡片空一行"，
     * 不是崩，所以这里**一律宽松**。
     */
    private static Proto.Step stepOf(JSONObject ste, int seq) {
        Proto.Step s = new Proto.Step();
        s.step = seq;
        s.at = System.currentTimeMillis();
        s.type = str(ste, "type");

        JSONObject call = ste.optJSONObject("call");
        if (call != null) {
            s.callType = str(call, "type");
            s.kind = str(call, "kind");
            s.status = str(call, "status");
            /**
             * ⚠️ `action` 是**对象**（T0 原始帧）：
             *   `{"type":"search","queries":["上海 天气","上海 下雨"]}`
             *   `{"type":"read_link","url":"https://…"}`
             * 按扁平字段读会得到空串 —— 卡片上就没有「搜了什么」、读链接那步没有 URL。
             */
            JSONObject act = call.optJSONObject("action");
            if (act != null) {
                s.action = str(act, "type");
                s.query = joinList(act, "queries");
                if (s.query.length() == 0) s.query = joinList(act, "query");
                s.url = str(act, "url");
                if (s.url.length() == 0) s.url = str(act, "link");
            }
            if (s.action.length() == 0) s.action = joinList(call, "action");
            if (s.query.length() == 0) {
                s.query = joinList(call, "query");
                JSONObject args = call.optJSONObject("arguments");
                if (s.query.length() == 0 && args != null) s.query = joinList(args, "query");
            }
            if (s.url.length() == 0) s.url = str(call, "url");
            if (s.url.length() == 0) s.url = str(call, "link");
            JSONArray src = call.optJSONArray("sources");
            s.sources = src == null ? -1 : src.length();
        } else {
            s.callType = str(ste, "call_type");
            s.kind = str(ste, "kind");
            s.status = str(ste, "status");
            JSONObject act = ste.optJSONObject("action");
            if (act != null) {
                s.action = str(act, "type");
                s.query = joinList(act, "queries");
                if (s.query.length() == 0) s.query = joinList(act, "query");
                s.url = str(act, "url");
                if (s.url.length() == 0) s.url = str(act, "link");
            }
            if (s.action.length() == 0) s.action = joinList(ste, "action");
            if (s.query.length() == 0) s.query = joinList(ste, "query");
            if (s.url.length() == 0) s.url = str(ste, "url");
            JSONArray src = ste.optJSONArray("sources");
            s.sources = src == null ? -1 : src.length();
        }
        return s;
    }

    /**
     * 取一个"可能是数组"的字段并拼成一行。
     *
     * 搜索词官方可能给 `["上海天气","上海 下雨"]`（数组），也可能给字符串。
     * 数组就按「、」拼 —— 卡片第一行要的是"它搜了什么"，不是 JSON 语法。
     */
    private static String joinList(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return "";
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i += 1) {
                Object x = a.opt(i);
                if (x == null || x == JSONObject.NULL) continue;
                if (sb.length() > 0) sb.append('、');
                sb.append(String.valueOf(x));
            }
            return sb.toString();
        }
        String s = String.valueOf(v);
        return "null".equals(s) ? "" : s;
    }

    // ---------------------------------------------------------------- 拆（非流式）

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
        String fin = str(ch, "finish_reason");
        r.truncated = "length".equals(fin);
        return r;
    }

    /**
     * 非流式整包里 v5 才有的那些字段（title / stc / annotations / usage）。
     *
     * 只在"端点不认 stream、退回整包"这条路上用 —— 正常情况下这些都在最后一帧里。
     */
    private static void absorbNonStream(StreamResult out, String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject usage = o.optJSONObject("usage");
            if (usage != null) {
                out.promptTokens = usage.optInt("prompt_tokens", 0);
                out.completionTokens = usage.optInt("completion_tokens", 0);
            }
            JSONArray ch = o.optJSONArray("choices");
            if (ch == null || ch.length() == 0) return;
            JSONObject c0 = ch.optJSONObject(0);
            if (c0 == null) return;
            out.finish = str(c0, "finish_reason");
            JSONObject msg = c0.optJSONObject("message");
            if (msg == null) return;
            out.title = str(msg, "title");
            JSONArray stc = msg.optJSONArray("server_tool_calls");
            if (stc != null && stc.length() > 0) out.stcJson = stc.toString();
            JSONArray ann = msg.optJSONArray("annotations");
            if (ann != null && ann.length() > 0) out.annotations = ann;
        } catch (Throwable ignored) {
            // 整包都解析不出来的话，上层已经拿到 parse() 的结果了，这里静默
        }
    }

    // ---------------------------------------------------------------- 上传

    /**
     * 上传一个文件到 `/files`，返回解析后的 JSON（里面有 `id` = `file_id`）。
     *
     * ## 三个必须做对的细节
     *
     * 1. **`purpose` 字段必须写在 `file` 之前**。官方 multipart 解析是流式的，
     *    字段顺序反了会 400（还不会告诉你为什么）。
     * 2. **`setFixedLengthStreamingMode`**：100MB 的文件先攒成一个 byte[] 再发，
     *    在手机上就是一次 100MB 的内存峰值 —— 低端机直接 OOM。流式写只要 64KB。
     * 3. **文件名按 UTF-8 原始字节写进 header**：中文名（"体检报告.pdf"）走
     *    `String` → `getBytes("UTF-8")` 原样进 multipart，别用 URLEncoder 编码，
     *    否则服务端看到的是 `%E4%BD%93...`。
     *
     * @param cb 可为 null；非 null 时每 64KB 回调一次进度并检查是否已取消
     */
    static JSONObject upload(Config cfg, File f, String filename, String purpose, ProgressCb cb)
            throws Exception {
        if (f == null || !f.exists()) throw new java.io.FileNotFoundException("文件不存在");
        String name = filename == null || filename.length() == 0 ? f.getName() : filename;
        // 双引号会破坏 header：换成单引号（文件名本身是给人看的，不影响 file_id）
        name = name.replace('"', '\'').replace('\r', ' ').replace('\n', ' ');
        String pur = purpose == null || purpose.length() == 0 ? "user_data" : purpose;
        String boundary = "----dsheye" + Long.toHexString(System.nanoTime());

        byte[] headPurpose = ("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"purpose\"\r\n\r\n"
            + pur + "\r\n").getBytes("UTF-8");
        byte[] headFile = ("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n").getBytes("UTF-8");
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");

        long size = f.length();
        long total = headPurpose.length + headFile.length + size + tail.length;

        aborting = false;
        HttpURLConnection c = null;
        try {
            String url = trimSlash(cfg.apiBase()) + "/files";
            c = (HttpURLConnection) new URL(url).openConnection();
            inFlight = c;
            c.setRequestMethod("POST");
            c.setConnectTimeout(15_000);
            c.setReadTimeout(120_000);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(total);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setRequestProperty("Authorization", "Bearer " + cfg.apiKey());
            c.setRequestProperty("Accept", "application/json");

            long started = System.currentTimeMillis();
            OutputStream o = c.getOutputStream();
            o.write(headPurpose);                 // ⚠️ purpose 必须在 file 之前
            o.write(headFile);
            long sent = headPurpose.length + headFile.length;
            if (cb != null) cb.onProgress(sent, total);

            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cb != null && cb.aborted()) throw new java.io.IOException("上传已取消");
                    if (System.currentTimeMillis() - started > 120_000) {
                        throw new java.io.IOException("上传超时（超过 120 秒）");
                    }
                    o.write(buf, 0, n);
                    sent += n;
                    if (cb != null) cb.onProgress(sent, total);
                }
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
            o.write(tail);
            o.flush();
            o.close();

            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new HttpError(code, resp);
            if (resp == null || resp.trim().length() == 0) throw new Exception("上传返回为空");
            return new JSONObject(resp);
        } finally {
            if (inFlight == c) inFlight = null;
            aborting = false;
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    // ---------------------------------------------------------------- 标题

    /**
     * 标题轻请求：**同步、必须在后台线程调用**，最多等 3 秒。
     *
     * 为什么不用本轮响应里的 `title`：联网模式确实会随本轮给（T0 实测），
     * 但聊天/Agent 与 `function tools` 互斥、带不了 `title`。而那两种模式恰恰是最需要
     * "会话列表里有个像样名字"的。所以这里留一条独立轻请求兜底：
     * 两条消息、24 token、不带 tools、不带思考。
     *
     * 失败返回 `null`（调用方静默 —— 标题不是功能，不能因此弹错误）。
     */
    static String title(Config cfg, String q, String a) {
        if (cfg == null) return null;
        String qq = q == null ? "" : q.trim();
        String aa = a == null ? "" : a.trim();
        if (qq.length() == 0 && aa.length() == 0) return null;
        if (qq.length() > 300) qq = qq.substring(0, 300);
        if (aa.length() > 500) aa = aa.substring(0, 500);
        try {
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system")
                .put("content", "用不超过 12 个字概括这段对话，只输出标题本身，不加引号"));
            msgs.put(new JSONObject().put("role", "user")
                .put("content", "问：" + qq + "\n答：" + aa));
            // thinking=disabled + temperature（思考开着 temperature 无效，这条要它生效）
            JSONObject body = assemble(cfg.apiModel(), 24, msgs, false,
                false, null, true, null, false);
            String resp = post(cfg.apiBase(), cfg.apiKey(), body.toString(), 3_000, 3_000);
            Resp r = parse(resp);
            return clean(r.text);
        } catch (Throwable e) {
            EyeLog.w("brain", "标题失败: " + e);
            return null;
        }
    }

    /** 把标题里可能带的引号/书名号/前缀剥掉，并夹到 24 字。 */
    static String clean(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.length() == 0) return null;
        t = t.replace("《", "").replace("》", "")
             .replace("\"", "").replace("“", "").replace("”", "")
             .replace("「", "").replace("」", "")
             .replace("标题：", "").replace("标题:", "").trim();
        if (t.endsWith("。") || t.endsWith(".")) t = t.substring(0, t.length() - 1);
        if (t.length() > 24) t = t.substring(0, 24);
        return t.length() == 0 ? null : t;
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
            JSONObject body = assemble(cfg.apiModel(), Math.max(300, maxChars * 2), msgs, false,
                false, null, true, null, false);
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

    /**
     * 安全取一个字符串字段。
     *
     * ⚠️ **必须走这里而不是 `optString`**：模型"只调工具、不说话"时，
     * `content` 是 JSON 的 `null`。`org.json` 的 `optString` 对 NULL 会返回字符串
     * `"null"`（`JSON.toString(NULL)` 的字面量），于是界面上会出现一条回答写着 "null"。
     */
    static String str(JSONObject o, String key) {
        if (o == null) return "";
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
}
