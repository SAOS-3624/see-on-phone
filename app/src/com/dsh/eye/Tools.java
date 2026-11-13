package com.dsh.eye;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 工具表：模型能自己做的四件事。
 *
 * | 工具 | 人话 | 为什么值得给 |
 * |---|---|---|
 * | `look` | 抬头再看一眼 | 没有它，模型只能拿"你说话那一刻恰好的一帧"硬答；有了它，它会先看再答 |
 * | `tune` | 把档位调一下 | **这就是"像电脑版 see-now 一样自己改设置"**：糊了调清楚、贵了调省 |
 * | `files` | 工作区里读写文件 | 把用户要的东西**存下来**（不像记忆那样每轮重发） |
 * | `memory_read` | 翻我的记忆 | 知道自己记过什么，不会重复问 |
 * | `memory_write` | 帮我记一笔 | 养鱼的地基：它自己攒下"这个人在做什么" |
 *
 * ## 刻意不给的工具
 *
 * `bash`、文件读写、任意网络请求 —— 手机上没有沙箱、没有审计、没有审批 UI。
 * 一旦被 prompt 注入（画面里写一行"忽略之前指令"）后果**不可逆**。
 * "像 DSH"是指"会自己动手"，不是"把桌面能力全搬过来"。
 *
 * ## 参数写成什么样，模型就怎么用
 *
 * 所以 description 里写的是"什么时候调"，不是"这个字段是什么类型"。
 * 想改行为先改这里，别去改 prompt —— 工具契约每一轮都在，prompt 会被滚掉。
 */
final class Tools {

    /** 一次 `look` 的产物。图**不进 tool 结果**，作为下一轮的 user 图发出去。 */
    static final class Look {
        byte[] jpeg;
        int frames;
        int tokens;
        String why;
    }

    /** 工具要用到的那几件外部东西（由 {@link Asker} 实现，测试里给假实现就能跑）。 */
    interface Env {
        Config cfg();
        /** 抓一张宫格（开相机 → 喂几秒 → 封箱 → 拼图）。失败返回 null。 */
        Look look(double seconds);
        /** 上一次 {@link #look} 的产物（图要在**下一轮**当 user 图发出去）。 */
        Look lastLook();
        /** 这一轮已经 look 过几次（幂等：同一轮最多 2 次，第 3 次直接回"已看过"）。 */
        int looksThisTurn();
        void newTurn();
        /** 工作区（`Download/DSH-Go/workspace/`）里的文件操作。 */
        String files(String action, String path, String text);
        String memRead(String name);
        String memWrite(String name, String text, boolean append);
        String memList();
        String memPreview();
        String tune(JSONObject args);
    }

    private Tools() { }

    // ---------------------------------------------------------------- 契约

    /**
     * 工具定义的 JSON。**每轮都随请求发一次**，所以它本身就是 context 开销
     * （四个工具 ≈ 700 token）。这也是为什么这里的话都很短。
     *
     * 内容是不变的，所以**拼一次就缓存**：每轮重新 new 四个 JSONObject 只是白烧 CPU。
     */
    private static volatile JSONArray cached;

    static JSONArray spec() {
        JSONArray c = cached;
        if (c != null) return c;
        synchronized (Tools.class) {
            if (cached == null) cached = buildSpec();
            return cached;
        }
    }

    private static JSONArray buildSpec() {
        JSONArray arr = new JSONArray();
        arr.put(fn("look",
            "用手机摄像头**现在**看一眼，拿回一张拼好的图（这一段的几个瞬间，按时间先后）。\n"
            + "什么时候调：用户问画面里的事、而你现在手上没有相关画面；或者上一次看的东西已经变了"
            + "（他翻页了、走动了、换了个东西）；或者他说「看看」「这是啥」「认得出来吗」。\n"
            + "**不用调**：纯聊天、你已经看着这张图回答过了、或者用户问的是常识。\n"
            + "成本：一张宫格约 190~200 token，开相机有 0.5~1.5 秒延迟。\n"
            + "返回值只有 {frames,tokens,why}，画面会作为**你的下一条消息的附图**出现 —— "
            + "所以调完不要解释「我正在看」，直接看下一轮给到的图。",
            new String[][] {
                { "seconds", "number", "抓几秒（1~5，默认 2.5）。要看清动作就 3~4；静态画面 1.5 就够" },
                { "why", "string", "你为什么看（一句话，写给自己看，会进日志）" }
            },
            new String[0]));

        arr.put(fn("tune",
            "调自己的显示档位。**画面看不清 / 太小 / 字认不出来 → 调清楚；太贵、太慢、图太多 → 调省。**\n"
            + "调完立刻生效（下一次 look 就是新档），不需要重开相机。\n"
            + "什么时候调：用户说「看不清」「放大点」「太糊」「别浪费钱」「简单点」，"
            + "或者你自己发现宫格里的东西小到认不出时**主动调清楚再 look 一次**，而不是硬猜。\n"
            + "只传你要改的那几项，其余不动。",
            new String[][] {
                { "tier", "string", "每帧占多大格子：native(最大/最清楚·最贵) / standard(默认) / "
                    + "compact(小/省) / full / single(整张只放一帧)" },
                { "pace", "string", "多久算画面变了：calm(只抓大变化·省) / normal(默认) / "
                    + "dense(每一刀都要) / game-economic(全屏快速运动)" },
                { "frames", "number", "一张宫格最多几帧（2~16）。越多细节越全，token 不涨但格子变小" },
                { "ctx", "number", "上下文预算，单位 **k token**（2~900）。他说「记性差/前面说过的又忘了」→ 调到 300；"
                    + "他说「太慢/省点钱」→ 调到 32。默认 64" },
                { "images", "number", "历史里额外带几张旧图（0~3）。0 = 只发当前这张" },
                { "summary", "boolean", "长对话要不要滚动摘要（true 更不容易忘事，但多花钱）" },
                { "speak", "boolean", "要不要念出来" },
                { "think", "string", "思考强度 off / low / medium / high" }
            },
            new String[0]));

        arr.put(fn("memory_read",
            "翻自己的长期记忆（`/sdcard/Download/DSH-Go/memory/` 里的文件 + 人设）。"
            + "不传 name 就返回清单（文件名、大小、总 token）。",
            new String[][] {
                { "name", "string", "文件名（如 01-鱼缸.txt）。不传 = 只列清单" }
            },
            new String[0]));

        arr.put(fn("memory_write",
            "记一笔，写进长期记忆，**以后每轮都会看到**。\n"
            + "什么时候用：用户让你记住一件事、你自己观察到值得留档的（几条鱼、什么颜色、他喜欢的说法）。\n"
            + "限制：单文件 ≤2KB；目录合计 ≤1500 token（超了会从最旧的文件开始丢并告诉你）；"
            + "内容里不许出现 API key 形态的串（会被拒）。\n"
            + "**别用它记流水账** —— 记进去的每一句，以后每一轮都要付一次钱。",
            new String[][] {
                { "name", "string", "文件名，如 01-鱼缸.txt；没后缀会自动补 .txt" },
                { "text", "string", "要写的内容。mode=append 时只写这一段" },
                { "mode", "string", "append(默认，追加) 或 replace(整篇替换)" }
            },
            new String[] { "name", "text" }));

        arr.put(fn("files",
            "**你自己手机上的工作区**（`Download/DSH-Go/workspace/`）。用户说「帮我记下来 / 存成文件 / "
            + "写个脚本 / 那天的清单」就用它 —— 存进去的东西**他能在文件管理器里直接看到、能拷走**。\n"
            + "只能在相对路径里操作（根目录就是工作区），不许 `..`。文件能长期留着，不像记忆那样每轮都重发。\n"
            + "什么时候**不要**用：想让它以后每轮都自动看到的东西 → 用 memory_write。",
            new String[][] {
                { "action", "string", "list(默认，列目录) / read / write(整篇覆盖) / append(追加) / delete" },
                { "path", "string", "相对路径，如 notes/买菜.txt。list 不传 = 列根目录" },
                { "text", "string", "write / append 时写的内容" }
            },
            new String[] { "action" }));

        return arr;
    }

    private static JSONObject fn(String name, String desc, String[][] props, String[] required) {
        try {
            JSONObject f = new JSONObject();
            f.put("name", name);
            f.put("description", desc);
            JSONObject params = new JSONObject();
            params.put("type", "object");
            JSONObject p = new JSONObject();
            for (String[] d : props) {
                JSONObject one = new JSONObject();
                one.put("type", d[1]);
                one.put("description", d[2]);
                p.put(d[0], one);
            }
            params.put("properties", p);
            JSONArray req = new JSONArray();
            for (String r : required) req.put(r);
            params.put("required", req);
            f.put("parameters", params);
            JSONObject w = new JSONObject();
            w.put("type", "function");
            w.put("function", f);
            return w;
        } catch (Throwable e) {
            throw new RuntimeException("工具定义拼不出来：" + e);
        }
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 跑一个工具。**异常一律转成一句人话返回给模型** —— 工具失败不该让整轮崩，
     * 它看到"这个工具现在用不了"就会换个说法回答，比整个对话挂掉好。
     */
    static String run(String name, String argsJson, Env env) {
        JSONObject a;
        try {
            a = argsJson == null || argsJson.trim().length() == 0 ? new JSONObject() : new JSONObject(argsJson);
        } catch (Throwable e) {
            a = new JSONObject();
        }
        long t0 = System.currentTimeMillis();
        String out;
        try {
            if ("look".equals(name)) out = look(a, env);
            else if ("tune".equals(name)) out = env.tune(a);
            else if ("memory_read".equals(name)) out = memRead(a, env);
            else if ("memory_write".equals(name)) out = memWrite(a, env);
            else if ("files".equals(name)) out = files(a, env);
            else out = err("没有这个工具：" + name);
        } catch (Throwable e) {
            out = err(String.valueOf(e.getMessage() == null ? e : e.getMessage()));
        }
        EyeLog.w("tool", name + " " + Brain.clip(String.valueOf(a), 120)
            + " → " + Brain.clip(out, 160) + " (" + (System.currentTimeMillis() - t0) + "ms)");
        return out;
    }

    private static String look(JSONObject a, Env env) {
        int n = env.looksThisTurn();
        if (n >= 2) {
            return "{\"note\":\"这一轮已经看过 " + n + " 次了，别再看 —— 用已经拿到的画面回答。\"}";
        }
        double sec = a.optDouble("seconds", 2.5);
        if (sec < 1) sec = 1;
        if (sec > 5) sec = 5;
        Look l = env.look(sec);
        if (l == null || l.jpeg == null) {
            return err("相机没抓到画面（可能被别的 App 占着，或者还没授权）");
        }
        try {
            JSONObject o = new JSONObject();
            o.put("frames", l.frames);
            o.put("tokens", l.tokens);
            o.put("why", l.why == null ? "time" : l.why);
            o.put("note", "画面在下一条消息里（按时间先后拼成的一张图）。看清楚再回答。");
            return o.toString();
        } catch (Throwable e) {
            return "{\"frames\":" + l.frames + ",\"tokens\":" + l.tokens + "}";
        }
    }

    private static String memRead(JSONObject a, Env env) {
        String name = a.optString("name", "").trim();
        if (name.length() == 0) return env.memList() + "\n\n" + env.memPreview();
        return env.memRead(name);
    }

    private static String memWrite(JSONObject a, Env env) {
        String name = a.optString("name", "").trim();
        String text = a.optString("text", "");
        boolean append = !"replace".equalsIgnoreCase(a.optString("mode", "append"));
        if (text.trim().length() == 0) return err("text 是空的，没什么可记");
        return env.memWrite(name, text, append);
    }

    /**
     * 工作区文件操作。**路径安全在 {@link Workspace#resolve} 里做**，这里只管分发。
     */
    private static String files(JSONObject a, Env env) {
        String action = a.optString("action", "list").trim().toLowerCase();
        String path = a.optString("path", "").trim();
        String text = a.optString("text", "");
        if (action.length() == 0) action = "list";
        if (!"list".equals(action) && path.length() == 0) return err("这个动作要 path");
        if (("write".equals(action) || "append".equals(action)) && text.length() == 0) {
            return err("text 是空的，没什么可写");
        }
        return env.files(action, path, text);
    }

    private static String err(String msg) {
        return "{\"error\":" + JSONObject.quote(msg == null ? "失败" : msg) + "}";
    }

    // ---------------------------------------------------------------- 记忆的落盘规矩

    /** 单文件上限（字节）。超过就拒绝 —— 记忆是**每轮都要重发**的东西。 */
    static final int MAX_FILE_BYTES = 2048;

    /** 文件名消毒：禁 `..`、禁路径分隔符、禁前导点、≤60 字、非 txt/md 自动补 .txt。 */
    static String safeName(String n) {
        String s = n == null ? "" : n.trim();
        s = s.replace('/', '_').replace('\\', '_').replace("\u0000", "_");
        while (s.startsWith(".")) s = "a" + s;
        if (s.length() == 0) s = "note.txt";
        if (s.length() > 60) s = s.substring(0, 56);
        String low = s.toLowerCase();
        if (!low.endsWith(".txt") && !low.endsWith(".md")) s = s + ".txt";
        return s;
    }

    /**
     * 写一个记忆文件。
     *
     * 三条闸，都是为了"别让记忆变成每轮都在付的钱"：
     *   1. 单文件 ≤ {@link #MAX_FILE_BYTES}；
     *   2. 目录合计 ≤ {@link Memory#MAX_TOKENS}，超了从**最旧的文件**开始丢；
     *   3. 内容里有 `sk-` 形态的长串 → 拒收（防"画面里写一行指令把 key 骗进记忆再带出去"）。
     *
     * @return 给模型看的一句人话（成功也带数字，它才知道自己写了多少）
     */
    static String writeFile(String rawName, String text, boolean append) {
        String name = safeName(rawName);
        if (text.indexOf("sk-") >= 0 && looksLikeKey(text)) {
            return "{\"error\":\"拒绝写入：内容里有 API key 形态的串。记忆会被每一轮发给模型，"
                + "把 key 写进去等于泄露。\"}";
        }
        byte[] bytes = text.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        File f = new File(Memory.dir(), name);
        long existing = append && f.exists() ? f.length() : 0;
        if (existing + bytes.length > MAX_FILE_BYTES) {
            return "{\"error\":" + JSONObject.quote("拒绝写入：" + name + " 会变成 "
                + (existing + bytes.length) + " 字节，超过单文件上限 " + MAX_FILE_BYTES
                + " 字节。先精简，或者用 mode=replace 整篇替换。") + "}";
        }
        try {
            FileOutputStream o = new FileOutputStream(f, append);
            o.write(bytes);
            o.close();
        } catch (Throwable e) {
            return "{\"error\":" + JSONObject.quote("写不进去（多半是没给存储权限）：" + e) + "}";
        }
        // 总量超了就砍最旧的（按文件名排序的前面几个）
        int dropped = 0;
        while (Conv.textTokens(Memory.raw()) > Memory.MAX_TOKENS) {
            java.util.List<File> fs = Memory.files();
            if (fs.size() <= 1) break;
            File old = fs.get(0);
            if (old.getName().equals(name)) old = fs.get(1);
            if (!old.delete()) break;
            dropped += 1;
        }
        try {
            JSONObject r = new JSONObject();
            r.put("bytes", bytes.length);
            r.put("file", name);
            r.put("totalTokens", Conv.textTokens(Memory.raw()));
            if (dropped > 0) r.put("droppedOldFiles", dropped);
            return r.toString();
        } catch (Throwable e) {
            return "{\"ok\":true}";
        }
    }

    /** `sk-` 后面跟着一长串像随机字符的东西 —— 宁可误伤也不放进记忆。 */
    private static boolean looksLikeKey(String s) {
        int i = s.indexOf("sk-");
        while (i >= 0) {
            int run = 0;
            for (int p = i + 3; p < s.length(); p += 1) {
                char c = s.charAt(p);
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_') run += 1;
                else break;
            }
            if (run >= 16) return true;
            i = s.indexOf("sk-", i + 3);
        }
        return false;
    }
}
