package com.dsh.eye;

import java.util.Locale;

/**
 * 能力矩阵与**请求形状的唯一出口**。
 *
 * ## 一条规矩
 *
 * 任何"要不要带 tools / title / thinking / temperature / 文档"的决定，只能来自
 * {@link #plan}。`Asker` / `Brain` / UI 里**不许**再写 {@code if (mode == WEB)} ——
 * 一旦同一个判断散在三处，就一定会出现"界面允许、请求不让"或者反过来的情况。
 *
 * ## 为什么是纯静态、纯 Java
 *
 * 这一段是整个改造里最容易错的部分（12 个布尔值的组合），所以它必须能在**电脑 JVM 上
 * 离线穷举测**（见 `T1Test`：3 模式 × 4 档 × 2 模型 × 有/无文档 × 有/无链接 = 96 组）。
 * 因此这里**不 import android.* / org.json**。
 *
 * ## 三模式的三条结构性保证
 *
 * | 模式 | kind | 自定义工具 | 服务端工具 |
 * |---|---|---|---|
 * | 聊天 | `CLIENT` | ✔（5 个 function） | ✘ |
 * | Agent | `CLIENT` | ✔（同一套） | ✘ |
 * | 联网 | `SERVER` | ✘ | ✔（web_search，有链接才加 read_url） |
 *
 * "自定义工具与服务端工具互斥"是官方的硬 400。这里用 {@link #mixed} 做不变式，
 * 并在 {@link #plan} 末尾兜一层：万一未来改出了混用，直接 {@code blocked=MIXED_TOOLS}
 * （一个字节都不发），而不是等 1~2 秒后拿一个说不清缘由的 400。
 */
final class Caps {

    private Caps() { }

    /**
     * 三个模式。**同一个 HTTP 客户端、同一套流式解析**，差别只在"带什么工具、几步、可见性"。
     *
     * 为什么嵌在 {@code Caps} 里（`Caps.Mode`，而不是包级独立类型）：跨层契约
     * {@code Proto.Reply.mode} 用的就是这个写法，UI 侧只 import 一个 Caps 就够。
     *
     * 落盘用的是 {@link #key}（`chat` / `web` / `agent`），界面用的是 {@link #label}。
     */
    enum Mode {
        /** 聊天：5 个自定义工具，≤3 步，静默（只有一行 ToolChip）。 */
        CHAT("chat", "聊天"),
        /** 联网：服务端工具（web_search / read_url），引用 + 搜索卡。 */
        WEB("web", "联网"),
        /** Agent：与聊天同一套工具，≤8 步，步骤条 + 可停。 */
        AGENT("agent", "Agent");

        /** 落盘 / 日志用的稳定小写名（`ConvStore` 的 metaJson `m` 键就是它）。 */
        final String key;
        /** 给人看的短名（胶囊行 / 顶栏）。 */
        final String label;

        Mode(String key, String label) {
            this.key = key;
            this.label = label;
        }

        /** 从落盘字符串解析。**认不出来返回 null**（老消息 = 没有模式），不要瞎猜成 CHAT。 */
        static Mode of(String key) {
            if (key == null) return null;
            String k = key.trim();
            for (Mode m : values()) if (m.key.equalsIgnoreCase(k)) return m;
            return null;
        }
    }

    // ---------------------------------------------------------------- 档位（思考）

    /** 自动：开思考，但**不写** `reasoning_effort`（让服务端自己定）。 */
    static final String EFF_AUTO = "auto";
    /** 高：开思考 + `reasoning_effort=high`。 */
    static final String EFF_HIGH = "high";
    /** 最高：开思考 + `reasoning_effort=max`。 */
    static final String EFF_MAX = "max";
    /** 关：关思考 + 写 `temperature`（思考开着发 temperature 是白发，官方明说无效）。 */
    static final String EFF_OFF = "off";

    /** 四档的标准顺序（设置页 / 档位气泡都按这个顺序）。 */
    static final String[] EFFORTS = { EFF_AUTO, EFF_HIGH, EFF_MAX, EFF_OFF };
    static final String[] EFFORT_LABEL = { "自动", "高", "最高", "关" };

    // ---------------------------------------------------------------- 模型

    /** 默认模型（有视觉、能读文件）。 */
    static final String MODEL_FLASH = "deepseek-flash";
    /** 旧名，官方仍接受（已路由到 V4.1-Flash）。只用于迁移。 */
    static final String MODEL_FLASH_OLD = "deepseek-v4-flash";
    /** 不看图、不读文件的那个。 */
    static final String MODEL_PRO = "deepseek-v4-pro";

    // ---------------------------------------------------------------- 能力位

    static final int CAP_VISION = 1;          // image_url 块 / 相册图 / look 回传
    static final int CAP_FILES = 1 << 1;      // /files 上传 + {"type":"file"} 引用
    static final int CAP_SEARCH = 1 << 2;     // deepseek_web_search
    static final int CAP_READ_URL = 1 << 3;   // deepseek_read_url
    static final int CAP_TOOLS = 1 << 4;      // 自定义 function
    static final int CAP_TITLE = 1 << 5;      // title.enabled

    /**
     * 这个模型能不能干这件事。**未知模型一律放行**（宽松）——
     * 用户在设置里手填一个模型名，最坏结果是官方报错（有分类文案），
     * 而不是 App 自己先把功能锁死、还不解释。
     */
    static boolean can(String model, int cap) {
        if (isPro(model)) {
            // pro：没有视觉、不能读文件；搜索 / 读网页 / 工具 / 标题都还在
            return !(cap == CAP_VISION || cap == CAP_FILES);
        }
        return true;
    }

    /** 是 pro 那一档吗（按 `-pro` 后缀放宽匹配，将来 v5-pro 自动继承限制）。 */
    static boolean isPro(String model) {
        if (model == null) return false;
        String m = model.trim().toLowerCase(Locale.ROOT);
        return m.equals(MODEL_PRO) || m.endsWith("-pro");
    }

    /** 能力被锁时的一句话（中文、无技术词、说清"怎么才能用"）。 */
    static String degradeHint(int cap) {
        switch (cap) {
            case CAP_VISION:   return "当前模型看不了图，切到 flash 才能看图。";
            case CAP_FILES:    return "当前模型读不了文件，切到 flash 才能发文件。";
            case CAP_SEARCH:   return "当前模型不能联网搜索，切到 flash 才能搜。";
            case CAP_READ_URL: return "当前模型不能读网页，切到 flash 才能读。";
            case CAP_TOOLS:    return "当前模型不能用工具，切到 flash 才能看画面。";
            case CAP_TITLE:    return "当前模型不能自动起标题。";
            default:           return "当前模型做不了这件事。";
        }
    }

    // ---------------------------------------------------------------- 档位归一

    /**
     * 老值兼容 + 归一。**所有"档位字符串"进门都必须过这一道。**
     *
     * ```
     * auto            → auto
     * high / max      → 原样
     * off             → off
     * medium（老值）  → high
     * low（老值）     → auto
     * null / 认不出   → auto
     * ```
     */
    static String normEffort(String e) {
        if (e == null) return EFF_AUTO;
        String v = e.trim().toLowerCase(Locale.ROOT);
        if (EFF_AUTO.equals(v) || EFF_HIGH.equals(v) || EFF_MAX.equals(v) || EFF_OFF.equals(v)) {
            return v;
        }
        if ("medium".equals(v)) return EFF_HIGH;
        if ("low".equals(v)) return EFF_AUTO;
        return EFF_AUTO;
    }

    /** 档位的中文名（认不出来按"自动"）。 */
    static String effortLabel(String effort) {
        String v = normEffort(effort);
        for (int i = 0; i < EFFORTS.length; i += 1) {
            if (EFFORTS[i].equals(v)) return EFFORT_LABEL[i];
        }
        return EFFORT_LABEL[0];
    }

    // ---------------------------------------------------------------- 计划

    /**
     * **这一次请求到底长什么样**。`Brain` 只接受它，不再接受裸的 `tools` 数组 ——
     * 于是"混用工具"在结构上就写不出来（ARCH §2.5 的 L1）。
     */
    static final class Plan {

        /** 工具来源。`NONE` = 一个工具都不带。 */
        enum Kind { NONE, CLIENT, SERVER }

        /**
         * 别名：让 ARCH §2.5 里那句 {@code plan.kind == Plan.CLIENT} 直接编译得动。
         * （等价于 {@code Plan.Kind.CLIENT}，两种写法都行。）
         */
        static final Kind NONE = Kind.NONE;
        static final Kind CLIENT = Kind.CLIENT;
        static final Kind SERVER = Kind.SERVER;

        Mode mode = Mode.CHAT;
        Kind kind = Kind.NONE;

        /** 写 `thinking:{"type":"enabled"}` 吗。 */
        boolean thinking;
        /**
         * `reasoning_effort` 的值。**null = 这个键根本不写**（自动 / 关都不写）——
         * 关思考还带 effort 是官方没定义的组合，不发最安全。
         */
        String effort;
        /** 写 `temperature` 吗（只在"关"的时候才有意义）。 */
        boolean sendTemperature;

        /** 随本轮带 `title:{"enabled":true}` 吗（只有联网模式可能为 true）。 */
        boolean title;

        /** 带自定义 function 工具（聊天 / Agent）。 */
        boolean allowToolsFunction;
        /** 带 `deepseek_web_search`（联网）。 */
        boolean allowWebSearch;
        /** 带 `deepseek_read_url`（联网 + 本轮消息里有链接）。 */
        boolean allowReadUrl;
        /** 允许把文档块发出去（模型与模式都允许才为 true）。 */
        boolean allowDocs;
        /** 允许把图片块发出去（模型有视觉才为 true）。 */
        boolean allowImages;

        /**
         * 工具循环的步数上限。
         * 聊天 3 / Agent 16 / 联网 1（联网的内部调用由服务端自己管，我们只"看"不"控"）。
         */
        int maxRounds;

        /**
         * **混合模式**：Agent + 联网同时开。
         *
         * 官方的硬限制是 `deepseek_web_search` 与 `function` 工具**不能同请求**
         * （400 `cannot be used together`）。所以混合模式不是"两个都塞进 tools"，
         * 而是：走 CLIENT（function 工具），额外多发一个自定义 function `web_search`，
         * 模型调它时由 `Asker` 拦下来、内部再发一次"只有服务端搜索"的独立请求。
         *
         * 三条不变式（`{@link Caps#mixed}` 仍然为 false）：
         *   - `kind = CLIENT`（`allowToolsFunction = true`）
         *   - `allowWebSearch = false`（**绝不能**同时又带服务端搜索）
         *   - `maxRounds = 16`（`web_search` 也算一步）
         */
        boolean hybrid;

        /** 非 null = **直接拒绝，一个字节都不发**（`Asker` 看到它就给文案）。 */
        Err.Code blocked;

        boolean blocked() { return blocked != null; }

        /** 日志画像。排障时"到底发了什么"看这一行就够。 */
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("plan{m=").append(mode == null ? "null" : mode.key)
              .append(" kind=").append(kind)
              .append(" think=").append(thinking)
              .append(" eff=").append(effort == null ? "-" : effort)
              .append(" temp=").append(sendTemperature)
              .append(" title=").append(title)
              .append(" fTool=").append(allowToolsFunction)
              .append(" search=").append(allowWebSearch)
              .append(" readUrl=").append(allowReadUrl)
              .append(" docs=").append(allowDocs)
              .append(" img=").append(allowImages)
              .append(" rounds=").append(maxRounds)
              .append(" hybrid=").append(hybrid);
            if (blocked != null) sb.append(" BLOCKED=").append(Err.key(blocked));
            return sb.append('}').toString();
        }
    }

    /**
     * 造一次请求的形状。**这是"要不要发什么"的唯一决定点。**
     *
     * @param m              模式（null 按聊天）
     * @param effort         档位原始值（老值 `low` / `medium` 会自动归一）
     * @param model          模型名
     * @param hasDocs        这一轮带文档附件吗
     * @param hasUrl         最后一条 user 消息里有链接吗（只有联网看这个）
     * @param titleSupported 当前端点/配置允许随本轮带 `title` 吗（T0 探针 1 的结论；
     *                       探针没跑完时传 {@code false} —— 失败只是"标题走独立轻请求"）
     * @param allowDocsInWeb 联网模式下是否放开文档（T0 探针 P2 已通过，
     *                       {@code Config.allowDocsInWeb} 默认 true；传 false 就是回滚那一道闸）
     */
    static Plan plan(Mode m, String effort, String model,
                     boolean hasDocs, boolean hasUrl,
                     boolean titleSupported, boolean allowDocsInWeb) {
        // 老 7 参：联网与否由模式本身决定（= 历史行为，T1Test 的 96 组走这条）
        return plan(m, effort, model, hasDocs, hasUrl, titleSupported, allowDocsInWeb,
            m == Mode.WEB);
    }

    /**
     * 造一次请求的形状（**带"联网开关"的完整版**）。
     *
     * @param webOn 联网开着吗。它和模式组合出三种形态：
     *              ```
     *              聊天 + 关 → 聊天（CLIENT，5 个 function）
     *              聊天 + 开 → 联网（SERVER，等价于 m=WEB）
     *              联网      → 联网（SERVER）
     *              Agent+ 关 → Agent（CLIENT，5 个 function）
     *              Agent+ 开 → **混合**（CLIENT + 多发一个 web_search function，
     *                          模型调它时内部再发一次纯服务端搜索请求）
     *              ```
     */
    static Plan plan(Mode m, String effort, String model,
                     boolean hasDocs, boolean hasUrl,
                     boolean titleSupported, boolean allowDocsInWeb, boolean webOn) {

        Plan p = new Plan();
        p.mode = m == null ? Mode.CHAT : m;
        // 「聊天 + 联网」就是原来的联网模式：归一在这里做，调用方漏归一也不会静默失效
        if (p.mode == Mode.CHAT && webOn) p.mode = Mode.WEB;

        // ① 档位：一次归一，后面全用它，别在多处各判一次
        String eff = normEffort(effort);
        p.thinking = !EFF_OFF.equals(eff);
        p.effort = (EFF_AUTO.equals(eff) || EFF_OFF.equals(eff)) ? null : eff;
        p.sendTemperature = !p.thinking;

        // ② 模型能力（一次问清，别在分支里反复 can()）
        boolean vision = can(model, CAP_VISION);
        boolean files = can(model, CAP_FILES);
        p.allowImages = vision;

        // ③ 模式
        if (p.mode == Mode.WEB) {
            p.kind = Plan.Kind.SERVER;
            p.allowWebSearch = true;
            p.allowReadUrl = hasUrl;                  // 官方自己只扫最后一条消息
            p.title = titleSupported && can(model, CAP_TITLE);
            p.maxRounds = 1;                          // 服务端自管内部调用，我们只有 1 轮
            p.allowDocs = allowDocsInWeb && files;
            p.hybrid = false;
            // 模式闸：联网 + 文档 —— 在**上传之前**就拦住，不白传 100MB，也不等 400
            if (hasDocs && files && !p.allowDocs) p.blocked = Err.Code.DOC_IN_WEB;
        } else {
            p.kind = Plan.Kind.CLIENT;
            p.allowToolsFunction = true;
            p.allowWebSearch = false;
            p.title = false;                          // 与 function tools 互斥，绝不带
            p.maxRounds = p.mode == Mode.AGENT ? 16 : 3;
            p.allowDocs = files;
            p.allowReadUrl = false;
            /**
             * 混合：**只有 Agent 有**。
             *
             * 聊天模式（3 步、静默）不适合"搜一轮再动手"，而 Agent 本来就有步骤条和
             * 8 步预算，多一步搜索是自然的。注意 `allowWebSearch` 仍然是 false ——
             * 联网靠的是我们额外塞的那个 `web_search` **function**，不是服务端工具。
             */
            p.hybrid = p.mode == Mode.AGENT && webOn;
        }

        // ④ 不变式：自定义工具与服务端工具**永不混用**。真被写混了就直接拒绝发请求。
        if (mixed(p)) p.blocked = Err.Code.MIXED_TOOLS;
        return p;
    }

    /** 不变式检查：同时允许两套工具 = 官方硬 400。测试会穷举断言它永远为 false。 */
    static boolean mixed(Plan p) {
        return p != null && p.allowToolsFunction && p.allowWebSearch;
    }

    /**
     * 「工具」总开关关掉之后的形状：**一个工具都不带**。
     *
     * 为什么要专门一个方法：`Config.tools()` 从 0.x 起就存在，但**请求端从来没读过它**
     * （只在设置页和 `/status` 里显示）。用户关掉以为"它不会动手了"，实际工具照发。
     * 这类"界面允许、请求不让"（或反过来）正是本类注释里禁止的东西。
     *
     * 不动 `allowDocs` / `allowImages`：文档和图片是**素材**，不是工具。
     */
    static void mute(Plan p) {
        if (p == null) return;
        p.kind = Plan.Kind.NONE;
        p.allowToolsFunction = false;
        p.allowWebSearch = false;
        p.allowReadUrl = false;
        p.hybrid = false;
        p.maxRounds = 1;
        p.title = false;
    }

    // ---------------------------------------------------------------- 待 T2

    /*
     * 已完成（T0）：探针 P2 实测 `{"type":"file"}` 块 + `deepseek_web_search` 同请求 HTTP 200
     *   → `Config.allowDocsInWeb` 默认已翻成 true。`DOC_IN_WEB` 那道闸**仍然在**：
     *   开关关掉 / 官方哪天不让混了，联网 + 文档会在上传前被拦住，一行开关即可回滚。
     *   探针 5 实测 `annotations` 下标是**码点** → `Cites.IS_UTF16` 保持 false，无需折算开关。
     *
     * TODO(T2)：ARCH §2.2 的 `Caps.dropImages(JSONArray)` **没有**落在这里 ——
     *   它要改请求体（org.json），而本类刻意保持纯 Java 以便离线穷举测试。
     *   落点改为 `Asker.compose()` 里对每条历史消息做降级，占位文字
     *   `[这一轮的图片在当前模型下不发送 · 换 flash 可看]`。
     *
     * TODO(T2)：ARCH §2.2 的 `Caps.thinking(effort)`（吐 JSONObject）同样在 T2 的 Brain.build
     *   里按 Plan.thinking / Plan.effort / Plan.sendTemperature 三个布尔拼，本类不碰 org.json。
     */
}
