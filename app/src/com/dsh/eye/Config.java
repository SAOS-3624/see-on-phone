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

    /** 思考强度：off | low | medium | high。默认 low（够用又不至于等太久）。 */
    private static final String K_THINK = "think";
    /** 念不念出来。 */
    private static final String K_SPEAK = "speak";
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
    /** v4 起默认走 flash：`deepseek-chat` 已于 2026-07-24 弃用（映射到它的非思考模式）。 */
    static final String DEFAULT_MODEL = "deepseek-v4-flash";
    static final String DEFAULT_VOICE = "";

    /** 思考四档。顺序就是设置页的循环顺序。 */
    static final String[] THINK = { "off", "low", "medium", "high" };
    static final String[] THINK_LABEL = { "关", "低", "中", "高" };

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
         * ③ 在线音色已删（2.0）。旧值长这样：`edge:zh-CN-XiaoxuanNeural`。
         *
         * 不清掉的话：`voiceDisplay` 会显示成"系统默认"（人话对了），但 `Speaker.setVoice`
         * 拿到这个 key 在系统音色里找不到 —— 静默退回默认嗓子，`voiceErr` 还挂着一条红字，
         * 用户看到的是"设置里写着小轩、声音是别的人"。清空 = 干净的系统默认。
         */
        String oldVoice = or(sp.getString(K_VOICE, ""), "");
        if (oldVoice.startsWith(LEGACY_EDGE_PREFIX)) sp.edit().putString(K_VOICE, "").apply();

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

    String think() {
        String v = sp.getString(K_THINK, "low");
        for (String ok : THINK) if (ok.equals(v)) return v;
        return "low";
    }

    void setThink(String v) {
        String m = "low";
        for (String ok : THINK) if (ok.equals(v)) m = ok;
        sp.edit().putString(K_THINK, m).apply();
    }

    /**
     * 下一档（设置页点一下循环）。`off → low → medium → high → off`。
     *
     * 顺序是"由浅入深"：用户点这个按钮时想的是"再想深一点"，
     * 而不是"按字母序换一个"。
     */
    String nextThink() {
        String cur = think();
        for (int i = 0; i < THINK.length; i += 1) {
            if (THINK[i].equals(cur)) return THINK[(i + 1) % THINK.length];
        }
        return "low";
    }

    static String thinkLabel(String v) {
        for (int i = 0; i < THINK.length; i += 1) if (THINK[i].equals(v)) return THINK_LABEL[i];
        return "低";
    }

    String thinkLabel() { return thinkLabel(think()); }

    boolean thinking() { return !"off".equals(think()); }

    // ---------------------------------------------------------------- 语音

    boolean speak() { return sp.getBoolean(K_SPEAK, true); }
    void setSpeak(boolean v) { sp.edit().putBoolean(K_SPEAK, v).apply(); }

    /**
     * 音色的键就是**系统 TTS 的 `Voice.getName()`**（华为上长这样：`xiaoxuan`）。
     * 空字符串 = 系统默认（用户在系统语音设置里挑的那个）。
     *
     * ⚠️ 1.x 这里还有第二档 `"edge:zh-CN-XXX"`（内置的 Edge 在线音色）。2.0 删了：
     * 那套 `Sec-MS-GEC` 令牌是从 GPL-3.0 的 `edge-tts` 逐位移植来的，会把整个 APK
     * 拖进 GPL。下面这个前缀只留给 `migrate()` 认旧值，**不要再拿它拼新值**。
     */
    static final String LEGACY_EDGE_PREFIX = "edge:";

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
        // 旧版本存下来的在线音色，现在已经用不了了 —— 显示成"系统默认"才是真的
        if (key.startsWith(LEGACY_EDGE_PREFIX)) return "系统默认";
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

    /** 单次回答的 token 上限。v4 统一 800 —— 240 会把答案截在半句。 */
    int maxTokens() {
        int v = sp.getInt(K_MAXTOK, 800);
        return v >= 200 && v <= 4000 ? v : 800;
    }

    /**
     * 一次 HTTP 请求最多等多久（秒）。
     *
     * v4 默认 **60**（以前 30）：开"高"思考时一次要想 10~20 秒，而工具轮还要再来一次。
     * 30 秒会在最该等的时候先失败。
     */
    int timeoutSec() {
        int v = sp.getInt(K_TIMEOUT, 60);
        return v >= 10 && v <= 180 ? v : 60;
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
