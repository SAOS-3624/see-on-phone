package com.dsh.eye;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置的唯一出口。
 *
 * v4 的原则：**用户只需要决定四件事** —— 想多深（思考）、记多少（上下文）、念不念（语音）、
 * 它是谁（记忆）。采集参数（分辨率 / 帧率 / 档位 / 节奏）不是用户该操心的东西：
 * 相机是模型的一个工具，档位由模型用 `tune` 自己调（和电脑版 see-now 一样）。
 *
 * 所以这个类只剩三类键：
 *   1. **连接**（key / base / model）—— 填一次就不动；
 *   2. **会话**（think / ctxTokens / imgWindow / summary / persona / convId）；
 *   3. **语音**（speak / rate / ttsVoice）。
 *
 * 采集那几项（`pace` / `tierStyle` / `maxFrames`）还留着，但**设置页不显示** ——
 * 它们是 `tune` 工具的白名单，模型在"你说太糊/太贵"之后自己改。
 */
final class Config {

    private static final String FILE = "eye";

    private static final String K_KEY = "apiKey";
    private static final String K_BASE = "apiBase";
    private static final String K_MODEL = "apiModel";

    /**
     * 思考档位：`auto`（自动）| `high`（高）| `max`（最高）| `off`（关）。默认 `auto`。
     *
     * 老值 `low` / `medium` 已在 {@link #migrate()} 里一次性归一，读的时候不信它们
     * （{@link Caps#normEffort} 也会再兜一次）。
     */
    private static final String K_THINK = "think";
    /** 念不念出来。 */
    private static final String K_SPEAK = "speak";
    /** 累计花费（微美元，long 存，避免 float 漂移）。 */
    private static final String K_COST = "costMicros";
    private static final String K_LAST_IN = "lastIn";
    private static final String K_LAST_OUT = "lastOut";
    /** 最近一轮输入里**命中缓存**的部分（0 = 没命中 / 端点没给）。 */
    private static final String K_LAST_HIT = "lastHit";
    /** 累计（token）：DSH 那种"总共用了多少 / 命中多少"。 */
    private static final String K_SUM_IN = "sumIn";
    private static final String K_SUM_OUT = "sumOut";
    private static final String K_SUM_HIT = "sumHit";
    private static final String K_LAST_TOOLS = "lastTools";
    private static final String K_LAST_COST = "lastCostMicros";
    private static final String K_VOICE = "ttsVoice";
    /** 通话页"自动看"（攒满就发）开着没有。默认开 —— 用户要的就是这个行为。 */
    private static final String K_AUTOW = "autoWatch";
    /** 局域网模式（HTTP 绑 0.0.0.0）。默认关。 */
    private static final String K_LAN = "lanOn";
    private static final String K_SYNCH = "syncHost";
    private static final String K_RATE = "rate";

    /** 带几轮上下文。0 = 不带。 */
    /** 历史最多吃多少 token（估算）。现在是**唯一的**上下文闸（轮数闸已删）。 */
    private static final String K_CTX = "ctxTokens";
    /** 历史里额外带几张图（0~3）。更早的换成占位文字。 */
    private static final String K_IMGW = "imgWindow";
    /** 当前会话 id（见 ConvStore）。 */
    private static final String K_CONV = "convId";
    /** 滚动摘要开关。 */
    private static final String K_SUM = "summary";
    private static final String K_SUMC = "summaryChars";
    /** 注入长期设定/文档的开关。 */
    private static final String K_MEM = "memInject";
    /** 用户手写的人设。 */
    private static final String K_PERSONA = "persona";

    /** 工具总开关（关掉就退化成纯聊天，不带 tools）。 */
    private static final String K_TOOLS = "tools";

    // ---- v5：对齐官方 App（ARCH §3.3 / §6.2）----
    /** 「联网」模式的标题轻请求开关。默认**开**（关掉就只剩本地截断标题）。 */
    private static final String K_TITLE_AUTO = "titleAuto";
    /** 对齐总闸。关掉 = 全退回 v4 的老行为（一个请求、只有聊天、没有卡片/引用/模式）。 */
    private static final String K_PARITY = "parity";
    /** `server_tool_calls` 原样回传。**必须**开，否则联网多轮的第一步就会被 400 打回。 */
    private static final String K_STC_REPLAY = "stcReplay";
    /**
     * 联网模式下是否放开文档附件。默认 **true**（T0 探针 P2 已通过）。
     *
     * 关掉之后联网 + 文档会被 {@code Caps.plan} 判成 {@code Err.DOC_IN_WEB} 直接拦住
     * （上传之前就拦，不白传 100MB）—— 这就是"官方哪天不让混了"的回滚开关。
     */
    private static final String K_DOCS_IN_WEB = "allowDocsInWeb";

    // ---- 以下三项**只给 `tune` 用**，设置页不显示（见类注释）----
    private static final String K_PACE = "pace";
    private static final String K_TIER = "tierStyle";
    private static final String K_MAXFRAMES = "maxFrames";
    /**
     * 「宫格张数」的旧键（`callN`），**已废**。
     *
     * see-now 没有"一张图放几帧"这个设置：几帧是 `checkFlush()` 判的
     * （`cellArea < flushGain` 碎格 / `maxWaitSec` 时间到 / `maxFrames:40` 保险）。
     * 手机端把它做成 2/4/6/8 的档位，等于让 `frames.length >= maxFrames`
     * 抢在 `flushGain` 前面封箱 —— 两条真正的判据全被架空。
     * 只保留常量用来在 migrate 里清掉旧值。
     */
    private static final String K_CALLN_DEAD = "callN";
    /**
     * 「宫格缩放」的旧键（`auto` / `even`），**已废**。
     *
     * 等分只在"匀速采样"下才等价于 2×2，而通话页采样本身就是匀速的 ——
     * 等于把版面锁死成一模一样，看不到"哪里变了"这个最该看的信息。
     * 现在 `tierMode` 固定 `weight`（面积 ∝ 变化量），只保留常量用来在 migrate 里清掉旧值。
     */
    private static final String K_GRID_DEAD = "gridScale";
    /** 通话页左下角那张"拼好的预览图"要不要显示（关掉连拼图都不做，省 CPU）。 */
    private static final String K_PREVIEW = "previewOn";

    private static final String K_MAXTOK = "maxTokens";
    private static final String K_TIMEOUT = "timeoutSec";

    static final String DEFAULT_BASE = "https://api.deepseek.com";
    /**
     * v5 起默认走 `deepseek-flash`（= V4.1-Flash，官方的默认档，有视觉、能读文件）。
     *
     * `deepseek-v4-flash` 是它的**旧名**（官方仍接受，但设置页不该显示一个"要自己查才知道
     * 是什么"的名字），升级时由 {@link #migrate()} 一次性换掉。
     * `deepseek-chat` / `deepseek-reasoner` 已于 2026-07-24 弃用，同样在迁移里换掉。
     */
    static final String DEFAULT_MODEL = "deepseek-flash";
    /**
     * 默认音色 = **小轩（在线）**。
     *
     * 2026-11-23 用户拍板改的：以前默认 `""`（系统 TTS，华为那三个十年前的系统嗓），
     * 想听小轩得自己去设置页选。现在装完就有人说人话。
     *
     * 三条护栏不变，所以"默认联网"这件事不会咬人：
     *   · 合成失败 / 没网 / 403 → **自动回落系统 TTS**（{@code Speaker.streamOne} 的 FAIL 分支）；
     *   · 设置页选「系统默认」就彻底不走网络（这里存的是空串，`ttsVoice()` 会返回空串）；
     *   · 常用短句有本地缓存（`DSH-Go/tts/<sha1>.mp3`），日常几乎不触网。
     */
    static final String DEFAULT_VOICE = "edge:zh-CN-XiaoxuanNeural";

    /**
     * 思考四档。顺序就是设置页的循环顺序（由浅入深 → 关）。
     *
     * `auto` = 开思考但不写 `reasoning_effort`（让服务端自己定）——这是**新的默认**：
     * 用户根本不该关心"high 还是 max"，那是官方调过的默认值。
     */
    static final String[] THINK = { "auto", "high", "max", "off" };
    static final String[] THINK_LABEL = { "自动", "高", "最高", "关" };

    /** 默认档位（`migrate` 与 `think()` 共用，避免两处写死不同的值）。 */
    static final String THINK_DEFAULT = "auto";

    private final SharedPreferences sp;

    Config(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * 一次性迁移：把 0.13.x 存下的**旧默认值**升级到 v4 的口径。
     *
     * 两件必须做，否则升级完就像"没升级"：
     *   1. `deepseek-chat` 已于 2026-07-24 弃用 —— 存的还是这个名字就一直走在兼容映射上；
     *   2. 上下文默认从 3 轮提到 10 轮。**只改"还停在 3"的**（= 用户从没自己调过），
     *      用户明明选了 3 就该是 3 —— 迁移不该覆盖人的选择。
     *
     * 用 `v4` 这个标记保证只跑一次：以后用户自己再改回 3 轮，下次启动不会被又顶回 10。
     */
    void migrate() {
        // ① 老版本留下、v4 已经不用的键。**留着不是"没影响"** —— `maxFrames` 这个名字
        //    和 v4 `tune` 用的键**撞了**：0.13 之前留下的 6 会悄悄把每张宫格压到 6 帧，
        //    而用户和模型都不知道这回事。这类"僵尸配置"必须清掉，否则同一个键在两个
        //    版本里含义不同，调半天调不动。
        if (!sp.getBoolean("v4clean", false)) {
            sp.edit()
                .remove("maxFrames").remove("maxRounds").remove("wakeBatch")
                .remove("debugSheet").remove("res").remove("fps").remove("camera")
                .remove("idleStopMin").remove("changeLog").remove("mode")
                .putBoolean("v4clean", true)
                .apply();
        }
        /**
         * ①-2 「宫格缩放」已删（见 {@link #K_GRID_DEAD}）。
         *
         * 旧值留着虽然不再被读，但**同名键以后要是被复用**，就会变成"用户和模型都不知道的
         * 暗配置" —— 和上面 `maxFrames` 的坑一模一样，趁现在清掉。
         * 单独用 `gridClean` 记，因为 `v4clean` 早就跑过了，写在它里面等于永远不执行。
         */
        if (!sp.getBoolean("gridClean", false)) {
            sp.edit().remove(K_GRID_DEAD).remove(K_CALLN_DEAD).putBoolean("gridClean", true).apply();
        }
        /**
         * ② 上下文预算：0.14.0 的默认是 16k（十轮就撞墙），0.14.1 提到 64k。
         *
         * ⚠️ 这一段**必须在 `v4` 那道 early-return 之前**：`v4` 标记在第一版迁移时就已经
         * 落盘了，写在它后面等于"这次改动永远不会执行"（踩过一次：装完还是 16000）。
         * 用 `ctxSet` 单独记"用户自己调过没有"。
         */
        if (sp.getInt(K_CTX, 16_000) == 16_000 && !sp.contains("ctxSet")) {
            sp.edit().putInt(K_CTX, DEFAULT_CTX).apply();
        }

        /**
         * ③ v5 一次性迁移（对齐官方 App）。
         *
         * 三件：
         *   1. 模型名 `deepseek-v4-flash` → `deepseek-flash`（同一个模型，只是官方改名，
         *      设置页不该显示旧名）；
         *   2. 档位老值 `medium` → `high`、`low` → `auto`（四档换了口径，老值留着就会
         *      被 `think()` 判成认不出来、悄悄回落到默认 —— 用户会以为"我的设置丢了"）；
         *   3. 记 `migratedV5`，**只跑一次**：以后用户自己再切回高/关，下次启动不会被顶回去。
         *
         * ⚠️ 必须在下面 `v4` 的 early-return **之前**：`v4` 标记早就落盘了，
         *    写在它后面等于"这段永远不执行"（`ctxSet` 那次踩过一模一样的坑）。
         */
        if (!sp.getBoolean("migratedV5", false)) {
            String m5 = or(sp.getString(K_MODEL, ""), "");
            if ("deepseek-v4-flash".equals(m5) || "deepseek-chat".equals(m5)
                || "deepseek-reasoner".equals(m5) || m5.length() == 0) {
                // 空串也补上：没填过模型的人应该拿到新默认，而不是"一直空着"。
                sp.edit().putString(K_MODEL, DEFAULT_MODEL).apply();
            }
            String t5 = or(sp.getString(K_THINK, ""), "");
            if ("medium".equals(t5)) {
                sp.edit().putString(K_THINK, "high").apply();
            } else if ("low".equals(t5)) {
                sp.edit().putString(K_THINK, "auto").apply();
            } else if (t5.length() == 0) {
                sp.edit().putString(K_THINK, THINK_DEFAULT).apply();
            }
            sp.edit().putBoolean("migratedV5", true).apply();
        }

        if (sp.getBoolean("v4", false)) return;
        String m = or(sp.getString(K_MODEL, ""), "");
        if ("deepseek-chat".equals(m) || "deepseek-reasoner".equals(m)) {
            sp.edit().putString(K_MODEL, DEFAULT_MODEL).apply();
        }
        sp.edit().putBoolean("v4", true).apply();
    }

    String apiKey() { return sp.getString(K_KEY, ""); }
    String apiBase() { return or(sp.getString(K_BASE, DEFAULT_BASE), DEFAULT_BASE); }
    String apiModel() { return or(sp.getString(K_MODEL, DEFAULT_MODEL), DEFAULT_MODEL); }

    void setKey(String v) { sp.edit().putString(K_KEY, v == null ? "" : v.trim()).apply(); }
    void setBase(String v) { sp.edit().putString(K_BASE, v == null ? "" : v.trim()).apply(); }
    void setModel(String v) { sp.edit().putString(K_MODEL, v == null ? "" : v.trim()).apply(); }

    /** 填了 key 就走直连。**没 key 什么都干不了**（v4 删掉了 dsh-go 桥那条路）。 */
    boolean direct() {
        String k = apiKey();
        return k != null && k.trim().length() > 0;
    }

    // ---------------------------------------------------------------- 思考

    /**
     * 当前档位。**恒返回 {@link #THINK} 里的一个值**（认不出来 → 默认 `auto`）。
     *
     * 这里不信老值（`low` / `medium`）：迁移已经把它们换掉了，万一有人手工改了
     * SharedPreferences，{@link Caps#normEffort} 还会再兜一次，不会把脏值发到线上。
     */
    String think() {
        String v = sp.getString(K_THINK, THINK_DEFAULT);
        for (String ok : THINK) if (ok.equals(v)) return v;
        return THINK_DEFAULT;
    }

    void setThink(String v) {
        String m = THINK_DEFAULT;
        for (String ok : THINK) if (ok.equals(v)) m = ok;
        sp.edit().putString(K_THINK, m).apply();
    }

    /**
     * 下一档（设置页点一下循环）。`自动 → 高 → 最高 → 关 → 自动`。
     *
     * 顺序是"由浅入深"：用户点这个按钮时想的是"再想深一点"，
     * 而不是"按字母序换一个"。
     */
    String nextThink() {
        String cur = think();
        for (int i = 0; i < THINK.length; i += 1) {
            if (THINK[i].equals(cur)) return THINK[(i + 1) % THINK.length];
        }
        return THINK_DEFAULT;
    }

    static String thinkLabel(String v) {
        for (int i = 0; i < THINK.length; i += 1) if (THINK[i].equals(v)) return THINK_LABEL[i];
        return THINK_LABEL[0];
    }

    String thinkLabel() { return thinkLabel(think()); }

    boolean thinking() { return !"off".equals(think()); }

    // ---------------------------------------------------------------- v5 开关

    /**
     * 「联网」模式下是否随本轮带 `title:{"enabled":true}` 之外的**独立标题轻请求**。
     *
     * 默认开：标题失败只损失"更好的标题"，不影响回答。
     * ⚠️ 与 `function tools` 互斥的那条路（聊天/Agent）**永远不会**带 title，
     *    那个判断在 {@code Caps.plan}，不是这个开关。
     */
    boolean titleAuto() { return sp.getBoolean(K_TITLE_AUTO, true); }
    void setTitleAuto(boolean v) { sp.edit().putBoolean(K_TITLE_AUTO, v).apply(); }

    /**
     * **对齐总闸**。false = 全退回 v4 老行为（单请求、只有聊天、无机型卡片/引用/模式）。
     *
     * 留着它是为了"线上一出问题就一键回到昨天"：v5 的改动面横跨请求形状、落盘、
     * 渲染三层，靠"改代码回滚"来不及，靠一个开关来得及。
     */
    boolean parity() { return sp.getBoolean(K_PARITY, true); }
    void setParity(boolean v) { sp.edit().putBoolean(K_PARITY, v).apply(); }

    /**
     * `server_tool_calls` 原样回传。**默认开且基本不该关** ——
     * 关掉之后联网多轮的第一步就会被官方 400（`cannot be used together` 那一类），
     * 开关只用于排障对比。
     */
    boolean stcReplay() { return sp.getBoolean(K_STC_REPLAY, true); }
    void setStcReplay(boolean v) { sp.edit().putBoolean(K_STC_REPLAY, v).apply(); }

    /**
     * 联网模式下是否放开文档附件。**默认 true**（T0 探针 P2 实测：`{"type":"file"}` 块与
     * `deepseek_web_search` 同请求返回 200，可以混用）。
     *
     * 闸门**仍然留着**：把这里关掉，`Caps.plan` 立刻回到 `Err.DOC_IN_WEB`
     * （联网模式选文档 → 上传前就拦住，不白传 100MB）。这样"官方哪天不让混了"只需要翻一个开关。
     */
    boolean allowDocsInWeb() { return sp.getBoolean(K_DOCS_IN_WEB, true); }
    void setAllowDocsInWeb(boolean v) { sp.edit().putBoolean(K_DOCS_IN_WEB, v).apply(); }

    // ---------------------------------------------------------------- 模式（按会话记忆）

    /**
     * 每个会话各记一个模式的键前缀：`mode:<convId>` → `"chat" | "web" | "agent"`。
     *
     * 为什么不放在会话文件里：模式是**用户当下的选择**，不是这一轮回答的属性。
     * 换到别的会话再回来，应该还是上次那个模式；而消息上那份 `Msg.mode` 记的是"那一轮
     * 实际用的模式"，两者不同义，谁也别覆盖谁。
     */
    private static final String K_MODE_PREFIX = "mode:";

    /**
     * 某个会话上次选的模式。**永不返回 null**（没记录 / 认不出 / `parity` 关掉 → `CHAT`）。
     *
     * @param convId 会话 id；null/空 → 用当前会话（{@link #convId()}），再没有就是 `CHAT`
     */
    Caps.Mode modeOf(String convId) {
        // 总闸关掉 = 全退回 v4（只有聊天）。编排层/UI 就不用各自再判一次。
        if (!parity()) return Caps.Mode.CHAT;
        String id = or(convId, convId());
        if (id.length() == 0) return Caps.Mode.CHAT;
        Caps.Mode m = Caps.Mode.of(sp.getString(K_MODE_PREFIX + id, ""));
        return m == null ? Caps.Mode.CHAT : m;
    }

    /** 记住某个会话的模式（null 当 `CHAT`）。T3 的胶囊行点 chip 时调它。 */
    void setMode(String convId, Caps.Mode m) {
        String id = or(convId, convId());
        if (id.length() == 0) return;                     // 还没有会话 id：无处可记，不落脏键
        Caps.Mode v = m == null ? Caps.Mode.CHAT : m;
        sp.edit().putString(K_MODE_PREFIX + id, v.key).apply();
    }

    /** 当前会话的模式（`modeOf(convId())` 的便捷写法，给编排层用）。 */
    Caps.Mode mode() {
        return modeOf(convId());
    }

    /** 当前会话的模式（`setMode(convId(), m)` 的便捷写法，给编排层用）。 */
    void setMode(Caps.Mode m) {
        setMode(convId(), m);
    }

    // ---------------------------------------------------------------- 联网开关（按会话）

    /**
     * 每个会话各记一个"联网开着没有"的键：`web:<convId>` → `true` / `false`。
     *
     * ## 为什么它和"模式"是两个键
     *
     * 模式是**三选一**（聊天 / 联网 / Agent），联网开关是**叠加**的：
     * ```
     * 聊天 + 联网开  → 就是原来的「联网」模式（`Caps.plan` 会归一成 WEB）
     * Agent + 联网开 → **混合**：动手工具 + 一个 `web_search` function
     * ```
     * 合成一个枚举会变成 5 个模式，而用户心里只有"要不要联网"这一件事。
     */
    private static final String K_WEB_PREFIX = "web:";

    /**
     * 某个会话的联网开关。`parity` 总闸关掉时**恒为 false**（联网一起退回 v4）。
     *
     * @param convId null/空 → 用当前会话；连当前会话都没有 → false
     */
    boolean webOf(String convId) {
        if (!parity()) return false;
        String id = or(convId, convId());
        if (id.length() == 0) return false;
        return sp.getBoolean(K_WEB_PREFIX + id, false);
    }

    /** 记住某个会话的联网开关（空 id 不落键，免得留下脏 `web:` 空键）。 */
    void setWeb(String convId, boolean v) {
        String id = or(convId, convId());
        if (id.length() == 0) return;
        sp.edit().putBoolean(K_WEB_PREFIX + id, v).apply();
    }

    /** 当前会话的联网开关。 */
    boolean web() {
        return webOf(convId());
    }

    /** 当前会话的联网开关。 */
    void setWeb(boolean v) {
        setWeb(convId(), v);
    }

    // ---------------------------------------------------------------- 语音

    boolean speak() { return sp.getBoolean(K_SPEAK, true); }
    void setSpeak(boolean v) { sp.edit().putBoolean(K_SPEAK, v).apply(); }

    // ---- 花费累计（微美元） ----
    long costMicros() { return sp.getLong(K_COST, 0L); }
    double costTotalUsd() { return costMicros() / 1_000_000.0; }
    void addCostUsd(double usd) {
        if (usd <= 0 || Double.isNaN(usd)) return;
        long add = Math.round(usd * 1_000_000.0);
        sp.edit().putLong(K_COST, costMicros() + add).apply();
    }

    /** 最近一轮的用量（重启后"本轮"还能显示，不用把每条消息都存 cost）。 */
    /**
     * 记一轮的用量。
     *
     * `hit` 是**输入里命中前缀缓存**的部分 —— DSH 的用量面板就是按
     * "输入（命中 / 未命中）+ 输出"三行拆的，因为它直接决定了这一轮多少钱
     * （命中价只有未命中的 1/50）。旧版本只存 in/out，所以界面只能显示"输入 N"，
     * 用户看不出缓存到底有没有起作用。
     */
    void setLastTurn(int in, int out, int hit, int tools, double usd) {
        sp.edit().putInt(K_LAST_IN, in).putInt(K_LAST_OUT, out).putInt(K_LAST_HIT, hit)
            .putInt(K_LAST_TOOLS, tools)
            .putLong(K_LAST_COST, Math.max(0, Math.round(usd * 1_000_000.0)))
            .putInt(K_SUM_IN, sumIn() + in)
            .putInt(K_SUM_OUT, sumOut() + out)
            .putInt(K_SUM_HIT, sumHit() + hit)
            .apply();
    }

    int lastIn() { return sp.getInt(K_LAST_IN, 0); }
    int lastOut() { return sp.getInt(K_LAST_OUT, 0); }
    int lastHit() { return sp.getInt(K_LAST_HIT, 0); }
    int lastTools() { return sp.getInt(K_LAST_TOOLS, 0); }
    double lastCostUsd() { return sp.getLong(K_LAST_COST, 0L) / 1_000_000.0; }

    /** 累计输入 / 输出 / 命中（token）。DSH 那种"总共用了多少"的统计。 */
    int sumIn() { return sp.getInt(K_SUM_IN, 0); }
    int sumOut() { return sp.getInt(K_SUM_OUT, 0); }
    int sumHit() { return sp.getInt(K_SUM_HIT, 0); }

    /**
     * 音色的键。三种形态，**靠前缀区分**（不用再加一个开关字段）：
     *
     * ```
     * ""                    系统默认（用户在系统语音设置里挑的那个）
     * "edge:zh-CN-XXX"      在线音色（小轩这些，走 EdgeTts）
     * "xiaoxuan"            系统 TTS 的 Voice.getName()
     * ```
     */
    static final String EDGE_PREFIX = "edge:";

    /** 能选的在线音色。第一个就是小轩 —— 用户要的那个。 */
    static final String[] EDGE_VOICES = {
        "zh-CN-XiaoxuanNeural", "zh-CN-XiaoxiaoNeural", "zh-CN-XiaoyiNeural",
        "zh-CN-YunxiNeural", "zh-CN-YunyangNeural",
    };

    static final String[] EDGE_LABELS = {
        "小轩 · 在线 · 女", "晓晓 · 在线 · 女", "晓伊 · 在线 · 女",
        "云希 · 在线 · 男", "云扬 · 在线 · 男",
    };

    String ttsVoice() { return or(sp.getString(K_VOICE, DEFAULT_VOICE), DEFAULT_VOICE); }

    /**
     * 通话页的"自动看"。
     *
     * ⚠️ 必须**记住**：以前进通话页就无脑置 true，用户点「关」安静一会儿、
     * 退出再进来又自己开了 —— 那个开关就等于没有。
     */
    /**
     * 局域网模式：HTTP 从 `127.0.0.1` 改绑 `0.0.0.0`，让**电脑能直接连手机**（不用数据线）。
     *
     * ⚠️ **默认关**。开了之后同 WiFi 下任何设备只要知道 IP 就能调这些接口
     * （看图、让它说话、调档位）。这是个明确的取舍，所以必须由用户自己点开。
     */
    /**
     * 电脑同步面板的地址。默认 `127.0.0.1:7788` —— 因为面板启动时会自己
     * `adb reverse tcp:7788 tcp:7788`，手机这边直接就能摸到它。
     */
    String syncHost() { return or(sp.getString(K_SYNCH, "127.0.0.1:7788"), "127.0.0.1:7788"); }

    void setSyncHost(String v) { sp.edit().putString(K_SYNCH, v == null ? "" : v.trim()).apply(); }

    boolean lanOn() { return sp.getBoolean(K_LAN, false); }

    void setLanOn(boolean v) { sp.edit().putBoolean(K_LAN, v).apply(); }

    boolean autoWatch() { return sp.getBoolean(K_AUTOW, true); }

    void setAutoWatch(boolean v) { sp.edit().putBoolean(K_AUTOW, v).apply(); }

    /** 给人看的音色名。 */
    static String voiceDisplay(String key) {
        if (key == null || key.length() == 0) return "系统默认";
        if (key.startsWith(EDGE_PREFIX)) {
            String id = key.substring(EDGE_PREFIX.length());
            for (int i = 0; i < EDGE_VOICES.length; i += 1) {
                if (EDGE_VOICES[i].equals(id)) return EDGE_LABELS[i];
            }
            return id + "（在线）";
        }
        return key;
    }
    void setTtsVoice(String v) { sp.edit().putString(K_VOICE, v == null ? "" : v).apply(); }

    float rate() {
        float v = sp.getFloat(K_RATE, 1.0f);
        return v >= 0.5f && v <= 2.0f ? v : 1.0f;
    }

    void setRate(float v) { sp.edit().putFloat(K_RATE, v >= 0.5f && v <= 2.0f ? v : 1.0f).apply(); }

    // ---------------------------------------------------------------- 上下文

    /**
     * **只按 token 预算管，不再有"带几轮"这道闸。**
     *
     * 为什么删：轮数是**刻意留的手调旋钮**（"只记最近 5 轮"），可它和预算是双闸，
     * 两个都得满足才发 —— 于是预算调到 300k 也没用（10 轮通常才两三万 token），
     * 用户看到的是"我明明给了 300k 怎么还是记不住"。而且设置页多一行就多一层要解释
     * 的东西。**留一个数就够了**：想少带就把预算调小。
     *
     * `Conv.trim` 的 `maxTurns` 参数**保留**（它是对拍基线的一部分，也有测试），
     * 只是这里恒传 {@link #TURNS_UNLIMITED}。
     */
    static final int TURNS_UNLIMITED = 1000;

    /**
     * 单次请求最多带多少 token（估算）。**含工具定义与记忆**，默认 64k。
     *
     * ⚠️ 这是个**上限，不是花费**：聊得短的时候一分钱不多花（带多少算多少）。
     * 只有真聊到那么长，每问的输入才会涨到那个量级 —— 所以调大它买的是
     * "别把旧对话丢掉"，不是"每问都烧 300K"。
     */
    int ctxTokens() {
        int v = sp.getInt(K_CTX, DEFAULT_CTX);
        return clampCtx(v);
    }

    void setCtxTokens(int v) { sp.edit().putInt(K_CTX, clampCtx(v)).putBoolean("ctxSet", true).apply(); }

    static final int DEFAULT_CTX = 64_000;
    /** 服务端窗口 1,048,576；留 15% 余量给估算误差 + 工具定义 + 这一次的回答。 */
    static final int CTX_MAX = 900_000;
    static final int CTX_MIN = 2_000;

    static int clampCtx(int v) {
        if (v < CTX_MIN) return CTX_MIN;
        return Math.min(v, CTX_MAX);
    }

    /** 预设档。最后一个不是数，是"自定义"（设置页用它开输入框）。 */
    static final int[] CTX_CHOICES = {
        16_000, 32_000, 64_000, 128_000, 300_000, 600_000, 900_000
    };

    /** 给人看的 `64k` / `1M`。 */
    static String ctxLabel(int v) {
        if (v >= 1_000_000) return "1M";
        if (v >= 1000) return (v / 1000) + "k";
        return String.valueOf(v);
    }

    /**
     * 历史里带几张图（0~3），默认 **0**（省钱）。
     *
     * 这是个**取舍**，不是 bug：
     *   · `0` = 历史图一律换成 `[图已省略 · 已存档]` 占位符。省 token，但**它记不住刚看过什么** ——
     *     自动看刚发完一张，你下一句问"刚才看到啥"，它手里真的没有图（会答"我没看到图片"）。
     *   · `1~3` = 每轮把最近 N 张图原样带上（一张宫格约 197 token），能追问上一张，但每次请求都贵。
     *
     * 默认取 0 是**钱**这一侧的取舍（2026-11-14 用户拍板）；想要"能追问"就调到 1。
     */
    int imgWindow() {
        int v = sp.getInt(K_IMGW, 0);
        return v >= 0 && v <= 3 ? v : 0;
    }

    void setImgWindow(int v) { sp.edit().putInt(K_IMGW, v >= 0 && v <= 3 ? v : 0).apply(); }

    String convId() { return or(sp.getString(K_CONV, ""), ""); }
    void setConvId(String id) { sp.edit().putString(K_CONV, id == null ? "" : id.trim()).apply(); }

    /**
     * 滚动摘要（默认**开**）。
     *
     * 长对话必然撞预算，关着就是整轮丢掉 —— 那和"没有上下文"没区别。
     * 代价是每丢一批多一次模型调用，以及摘要一变前缀缓存失效；
     * 但它在**被裁的那一刻**才跑，日常十几轮根本不触发。
     */
    boolean summary() { return sp.getBoolean(K_SUM, true); }
    void setSummary(boolean v) { sp.edit().putBoolean(K_SUM, v).apply(); }

    int summaryChars() {
        int v = sp.getInt(K_SUMC, 220);
        return v >= 80 && v <= 600 ? v : 220;
    }

    /** 记忆要不要每轮都注入（关掉 = 它只能自己 `memory_read`）。 */
    boolean memInject() { return sp.getBoolean(K_MEM, true); }

    String persona() { return or(sp.getString(K_PERSONA, ""), ""); }
    void setPersona(String v) { sp.edit().putString(K_PERSONA, v == null ? "" : v.trim()).apply(); }

    /** 工具总开关。关掉之后模型连 `look` 都调不了（退化成纯聊天）。 */
    boolean tools() { return sp.getBoolean(K_TOOLS, true); }
    void setTools(boolean v) { sp.edit().putBoolean(K_TOOLS, v).apply(); }

    // ---------------------------------------------------------------- 只给 tune 用

    /**
     * 节奏档：**直接对应电脑版的四档**（`runner.js` 的 PACES）。
     * 决定"多久算画面变了、兜底间隔多长"，不改出图口径。
     */
    String pace() {
        String v = sp.getString(K_PACE, "normal");
        return "calm".equals(v) || "dense".equals(v) || "game-economic".equals(v) ? v : "normal";
    }

    void setPace(String v) {
        sp.edit().putString(K_PACE,
            "calm".equals(v) || "dense".equals(v) || "game-economic".equals(v) ? v : "normal").apply();
    }

    /**
     * 档位映射：每帧占画布多大（`Tiers.STRATEGIES`）。
     * `native` 一格一张、`standard` 压成 2 档、`compact` 更小 —— 越小越省 token、
     * 越大越清楚。
     */
    String tierStyle() {
        String v = sp.getString(K_TIER, "standard");
        return "native".equals(v) || "full".equals(v) || "compact".equals(v) || "single".equals(v)
            ? v : "standard";
    }

    void setTierStyle(String v) {
        sp.edit().putString(K_TIER,
            "native".equals(v) || "full".equals(v) || "compact".equals(v) || "single".equals(v)
                ? v : "standard").apply();
    }

    // ---- 这两个的"人话"标签：设置页和模型 `tune` 用的是同一套取值 ----

    /** 灵敏度（= pace）：平静 / 正常 / 灵敏 / 游戏。 */
    String paceLabel() {
        String v = pace();
        if ("calm".equals(v)) return "平静";
        if ("dense".equals(v)) return "灵敏";
        if ("game-economic".equals(v)) return "游戏";
        return "正常";
    }

    /** 平静 → 正常 → 灵敏 → 游戏 → 平静。 */
    String nextPace() {
        String v = pace();
        if ("normal".equals(v)) return "dense";
        if ("dense".equals(v)) return "game-economic";
        if ("game-economic".equals(v)) return "calm";
        return "normal";
    }

    /** 档位映射（= tierStyle）：每帧占画布多大。 */
    String tierLabel() {
        String v = tierStyle();
        if ("native".equals(v)) return "原生";
        if ("full".equals(v)) return "更大";
        if ("compact".equals(v)) return "更小";
        if ("single".equals(v)) return "逐帧";
        return "标准";
    }

    /** 标准 → 原生 → 更大 → 更小 → 逐帧 → 标准。 */
    String nextTier() {
        String v = tierStyle();
        if ("standard".equals(v)) return "native";
        if ("native".equals(v)) return "full";
        if ("full".equals(v)) return "compact";
        if ("compact".equals(v)) return "single";
        return "standard";
    }

    /** 档位映射的一句话解释（值会变，解释跟着变）。 */
    String tierHint() {
        String v = tierStyle();
        if ("native".equals(v)) return "一格一张，最清楚也最贵";
        if ("full".equals(v)) return "格子再放大一档";
        if ("compact".equals(v)) return "格子更小，省 token";
        if ("single".equals(v)) return "一张图只放一帧（整幅）";
        return "每帧占多大格子";
    }

    /** 一张宫格最多几帧。0 = 用 `Pipeline.Opts` 的默认（40）。 */
    int maxFrames() {
        int v = sp.getInt(K_MAXFRAMES, 0);
        return v >= 0 && v <= 40 ? v : 0;
    }

    void setMaxFrames(int v) { sp.edit().putInt(K_MAXFRAMES, v >= 0 && v <= 40 ? v : 0).apply(); }

    // ---------------------------------------------------------------- 通话

    /**
     * 通话页的拼图预览开不开（默认开）。关掉 = 不显示、也不算，省 ~2% CPU。
     */
    boolean previewOn() { return sp.getBoolean(K_PREVIEW, true); }

    void setPreviewOn(boolean on) { sp.edit().putBoolean(K_PREVIEW, on).apply(); }

    /**
     * **攒够几张宫格才一起交给模型**（see-now 的 `wakeBatch`，1~4，默认 1）。
     *
     * ⚠️ 这是 see-now **自己就有的设置**（`dsh-see-now/src/tuning.js:56`），不是手机端
     * 新发明的旋钮 —— 加它之前/之后都别顺手扩展成别的东西。
     *
     * 省的不是图的钱（一张 798×450 才 222 token，多带两张几乎不要钱），
     * 省的是**每一轮请求都要把整个上下文重过一遍**的那笔。
     *
     * 光攒不交是不省的：叫醒了却还是一次只给一张，剩下两张它还得再 `peek` 两次，
     * 等于又变回三轮。所以 `queueDepth`（队列压几张）和 `takeMany()`（一次交几张）
     * 必须用同一个数 —— see-now `runner.js:226` 的原话，只改一个都不省。
     */
    private static final String K_BATCH = "wakeBatch";

    int wakeBatch() {
        int v = sp.getInt(K_BATCH, 1);
        return v >= 1 && v <= 4 ? v : 1;
    }

    void setWakeBatch(int v) { sp.edit().putInt(K_BATCH, v >= 1 && v <= 4 ? v : 1).apply(); }

    /** 设置页点一下循环：1 → 2 → 3 → 4 → 1。 */
    int nextWakeBatch() {
        int v = wakeBatch() + 1;
        return v > 4 ? 1 : v;
    }

    // ---------------------------------------------------------------- 请求

    /**
     * 单次回答的 token 上限。**0 = 自动（默认）**，由 {@link Brain#autoMaxTokens} 现算。
     *
     * ## 为什么默认从"800"改成"自动"
     *
     * 800 的来历是 v4 的"手机上一句话别太长"，2000 是 11-22 的半修 —— 两者都错在
     * **把省钱放在了质量前面**：思考 token 也算在输出里，开"高"思考先花 1500~3000，
     * 正文刚开头就被切。用户的原话是"我不明白你为什么要设置这个上限"。
     *
     * 真正的约束不是"我想让它写多少"，而是**窗口还剩多少**：
     * `1M − 这次输入 − 余量`，上限再夹到官方的 384K。所以自动模式下
     * "思考再长也不会被截断"，同时也不会因为 `max_tokens` 大于剩余窗口被服务端 400。
     *
     * 想省钱的人仍然可以手选一个固定值（设置 → 回答长度）。
     */
    int maxTokens() {
        int v = sp.getInt(K_MAXTOK, 0);
        if (v <= 0) return 0;                    // 自动
        return v >= 1000 && v <= 384_000 ? v : 0;
    }

    void setMaxTokens(int v) {
        if (v <= 0) { sp.edit().putInt(K_MAXTOK, 0).apply(); return; }
        sp.edit().putInt(K_MAXTOK, Math.max(1000, Math.min(384_000, v))).apply();
    }

    /**
     * 给"这次回答"预留多少上下文（只用于 `contextFor()` 的预算，**不是发给服务端的值**）。
     *
     * 自动模式下不能按 384K 预留：那会把历史压到 50 万以下，正常聊天根本用不掉，
     * 白白丢掉旧对话。留 16K 就够装下绝大多数回答；真写到更长，是赚的。
     */
    int answerReserve() {
        int v = maxTokens();
        return v > 0 ? v : 16_384;
    }

    /** 回答长度的档位。0 = 自动（推荐）。 */
    static final int[] MAXTOK_CHOICES = { 0, 8_000, 16_000, 32_000, 65_536, 131_072 };

    int nextMaxTokens() {
        int cur = maxTokens();
        for (int i = 0; i < MAXTOK_CHOICES.length; i += 1) {
            if (MAXTOK_CHOICES[i] == cur) {
                return MAXTOK_CHOICES[(i + 1) % MAXTOK_CHOICES.length];
            }
        }
        return 0;
    }

    /** `自动（不设限）` / `32000 tok（约 2.1 万字）`。 */
    static String maxTokensLabel(int v) {
        if (v <= 0) return "自动（不设限）";
        return v + " tok（约 " + (v * 2 / 3 / 1000) + " 千字）";
    }

    /**
     * 一次 HTTP 请求最多等多久（秒）。**默认 180**（以前 60）。
     *
     * 这是 **socket 读超时**（"多久没有新字节就断"），不是总时长：流式有 token 进来
     * 就一直续期。所以它挡的是"服务端卡死"，不是"回答太长"。
     * 但长思考 + 大上下文时，服务端预填阶段可能几十秒不吐字节 —— 60 秒会在那时误杀。
     */
    int timeoutSec() {
        int v = sp.getInt(K_TIMEOUT, 180);
        return v >= 10 && v <= 300 ? v : 180;
    }

    /** 排障：把所有键原样吐出来（`/?peek=1`）。key 脱敏。 */
    String dump() {
        StringBuilder sb = new StringBuilder();
        java.util.Map<String, ?> all = sp.getAll();
        java.util.List<String> keys = new java.util.ArrayList<>(all.keySet());
        java.util.Collections.sort(keys);
        for (String k : keys) {
            Object v = all.get(k);
            if (K_KEY.equals(k)) v = "***";
            sb.append(k).append('=').append(v).append('\n');
        }
        return sb.toString();
    }

    /** 给人看/给回包用的状态，key 永远只露头尾。 */
    String describe() {
        String key = apiKey();
        String masked;
        if (key.length() == 0) masked = "(空)";
        else if (key.length() <= 10) masked = "***";
        else masked = key.substring(0, 6) + "…" + key.substring(key.length() - 4);
        return "key=" + masked
            + "\nbase=" + apiBase()
            + "\nmodel=" + apiModel()
            + "\nthink=" + think()
            + "\nctx=" + ctxLabel(ctxTokens()) + " tok（上限）"
            + "\n";
    }

    private static String or(String v, String fallback) {
        return v == null || v.trim().length() == 0 ? fallback : v.trim();
    }
}
