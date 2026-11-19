package com.dsh.eye;

import java.util.ArrayList;
import java.util.List;

/**
 * 一段对话里的一条消息 + 「这一次请求到底发什么」的裁剪规则。
 *
 * ## v4 加了什么
 *
 * 两条新列：
 *   - `reasoning`：assistant 这条回答的**思考过程**（`reasoning_content`）。
 *     ⚠️ 带 `tools` 时必须原样回传，漏了直接 400，所以它必须跟消息一起落盘、
 *     一起被裁（整轮被裁就一起走）。
 *   - `origin`：这条消息是从哪来的（`phone` / `dsh:<session>#<seq>`）。
 *     现在只写 `phone`，是给双向迁移留的幂等键。
 *
 * ## offload 在手机上是自己实现的
 *
 * 电脑端（DSH 宿主）有正规机制：往会话日志追加 `image/offload` 事件。**手机没有宿主**，
 * 它是自己拼 JSON 直连 API，接口无状态。所以这里的等价做法是：
 *
 *   - 图**始终留在磁盘**（`conv/<id>/`），随时能回看；
 *   - 发请求时只有最近 {@code imgWindow} 张以 base64 附上；
 *   - 更早的图**换成一行占位文字**（见 {@link Out#offloaded}）。
 */
final class Conv {

    private Conv() { }

    /** 磁盘上的一条消息。字段顺序就是 {@link ConvStore} 的行格式，改动要两边一起改。 */
    static final class Msg {
        long at;                 // 毫秒时间戳
        String role;             // "user" | "assistant"
        String text;
        List<Shot> shots = new ArrayList<>(1);
        /** 思考过程（只有 assistant 有）。空 = 那一轮没开思考。 */
        String reasoning;
        /** 来源：`phone`（本机）或 `dsh:<session>#<seq>`（将来从电脑导入的）。 */
        String origin;
        /** 这一轮想了多久（毫秒，只给界面显示"想过 3.2 秒"用，不进请求）。 */
        long thinkMs;

        // ── v5 新增（全部可空；7 列老文件读出来就是空/null，不需要迁移脚本）──────
        /**
         * 这一轮用的模式。**null = 老消息**（按聊天渲染：无卡片、无角标）。
         * 别自作聪明默认成 CHAT —— 老消息确实没这个信息。
         */
        Caps.Mode mode;
        /** 档位（`auto`/`high`/`max`/`off`）。界面显示「思考 6.2 秒 · 自动」要用。 */
        String effort;
        /**
         * 这一轮**已经发过**自动标题轻请求。防"重试 / 继续生成 / 切模式重发"重复付费。
         * （"用户手改过名"是另一件事，走会话级 `<id>.tlock`，见 {@link #titleLocked}。）
         */
        boolean titleGen;
        /**
         * 会话级标题锁的**镜像**：`<id>.tlock` 存在 = 用户手改过名，自动标题永不覆盖。
         * 真值只在文件上；这里镜像一份是为了渲染列表时零 IO（{@link ConvStore#load} 时填）。
         */
        boolean titleLocked;
        /** 这一轮带出去的文档附件（`file_id` 已落盘 → 多轮复用不再上传）。 */
        List<Att> atts;
        /** 引用（渲染用）。**不进请求** —— 回传引用没有意义，只会白烧 token。 */
        List<Cites.Cite> cites;
        /**
         * 卡片的一句话摘要（"3 步 · 2.4s · 4 源"），折叠态直接显示，不用重算。
         * T1 先按一行 String 存；结构化的 {@code Steps} 是 T3 的事。
         */
        String steps;
        /** `server_tool_calls` 的 sidecar 文件名（null = 这一轮没有）。**不进 TSV**，见 ConvStore。 */
        String stcRef;

        Msg() { }

        static Msg user(long at, String text) {
            Msg m = new Msg();
            m.at = at;
            m.role = "user";
            m.text = text == null ? "" : text;
            m.origin = "phone";
            return m;
        }

        static Msg ai(long at, String text) {
            Msg m = new Msg();
            m.at = at;
            m.role = "assistant";
            m.text = text == null ? "" : text;
            m.origin = "phone";
            return m;
        }

        Msg with(Shot s) {
            if (s != null) shots.add(s);
            return this;
        }

        /** 记得越久越占预算，但它是"它怎么想的"的唯一证据 —— 界面要能点开看。 */
        Msg withReasoning(String r) {
            reasoning = r == null || r.trim().length() == 0 ? null : r.trim();
            return this;
        }

        /** 挂一个文档附件（上传成功后才有 fileId）。 */
        Msg withAtt(Att a) {
            if (a == null) return this;
            if (atts == null) atts = new ArrayList<>(1);
            atts.add(a);
            return this;
        }
    }

    /**
     * 一轮里带出去的**文档**附件（`/files` 上传的产物）。
     *
     * 为什么只存 `fileId`、不存原文件：官方 file 不设过期时间，"会话里的历史附件一直可用"，
     * 所以 100MB 的 PDF 在磁盘上只占几十字节 —— 这也是"附件缓存泄漏"风险的根治办法。
     * 图片不走这里（走 `image_url` base64，仍存在 `conv/<id>/*.jpg`）。
     */
    static final class Att {
        static final int ST_PENDING = 0;     // 排队
        static final int ST_UPLOADING = 1;   // 上传中
        static final int ST_OK = 2;          // 成功（只有 2 / 3 会落盘）
        static final int ST_FAIL = 3;        // 失败（可重试）

        /** `file-api-…`（上传成功才有）。 */
        String fileId;
        /** 原名（中文原样，不信服务端回显）。 */
        String name;
        long size;
        /** `text_recognition` | `rich_layout_recognition` | `user_data`。 */
        String purpose;
        /** {@link #ST_PENDING} … {@link #ST_FAIL}。 */
        int state;

        Att() { }

        static Att of(String fileId, String name, long size, String purpose, int state) {
            Att a = new Att();
            a.fileId = fileId;
            a.name = name;
            a.size = size;
            a.purpose = purpose;
            a.state = state;
            return a;
        }

        /** 能不能真的发出去（只有成功上传的才算）。 */
        boolean usable() {
            return state == ST_OK && fileId != null && fileId.length() > 0;
        }

        /**
         * 一个 file 块大概要多少 token。
         *
         * 估算口径：服务端解析出的文字量 ≈ 文件字节的 1/3000~1/5000，取 {@code size/4000}，
         * 再夹到 200~20000（防 0 字节文件算成 0、防 100MB PDF 估成 25 万把预算一次吃光）。
         * 客户端拿不到真实值，这个数唯一用途是"别把请求撑爆"，所以宁可估大。
         */
        int tokens() {
            long n = size / 4000L;
            if (n < 200L) return 200;
            if (n > 20000L) return 20000;
            return (int) n;
        }
    }

    /**
     * 一张随消息发出去的图。
     *
     * {@code file} 是落盘路径 —— offload 之后它照样在；{@code frames}/{@code why} 用来
     * 生成那句占位文字，让模型知道"那时候看的是一张 3 帧的宫格，封箱原因是到时间"。
     */
    static final class Shot {
        String file;
        int frames;
        String why;
        /** 计费 token：宫格 197、原始帧 210。存下来是为了裁剪时不用再猜。 */
        int tokens;

        Shot() { }

        static Shot of(String file, int frames, String why, int tokens) {
            Shot s = new Shot();
            s.file = file;
            s.frames = frames;
            s.why = why;
            s.tokens = tokens;
            return s;
        }

        /**
         * 老图被裁掉时留在上下文里的那一行。
         *
         * 只说"这里本来有张图、已存档"，**不报帧数、不报原因** —— 报"5 帧"会让模型
         * 张口就是"第 3 帧"（用户要的是描述画面，不是数帧）。
         */
        String mark() {
            return frames <= 0 ? "[图已省略 · 相册图 · 已存档]" : "[图已省略 · 已存档]";
        }
    }

    /**
     * **一次请求里的一条消息**（裁剪后的产物）。
     *
     * 和 {@link Msg} 的区别：Msg 是磁盘上的真相，Out 是"这一次到底发什么"。
     */
    static final class Out {
        final String role;
        final String text;
        /** 这次真要发出去的图（已读成字节）。null/空 = 不带图。 */
        List<byte[]> images;
        /** 被省略的图 → 占位文字。按原顺序排在文字后面。 */
        List<String> offloaded;
        /** assistant 的思考过程（带 tools 时要回传）。 */
        String reasoning;

        // ── v5 新增 ────────────────────────────────────────────────────
        /**
         * `server_tool_calls` 的**原始 JSON 字符串**（原样回传，绝不重新序列化 ——
         * 重排会把 `signature` 里的转义细节改掉，官方要求"逐字保留"）。
         * 由 `EyeService` 从 sidecar 读出来填（`Conv` 不碰磁盘）。
         */
        String stcJson;
        /** 这一轮要发的文档块（已上传成功的那些）。 */
        List<Att> atts;
        /** 这一轮的模式（日志画像 + 组请求时区分）。 */
        Caps.Mode mode;

        Out(String role, String text) {
            this.role = role;
            this.text = text == null ? "" : text;
        }

        void addImage(byte[] jpeg) {
            if (images == null) images = new ArrayList<>(1);
            images.add(jpeg);
        }

        void addMark(String mark) {
            if (offloaded == null) offloaded = new ArrayList<>(1);
            offloaded.add(mark);
        }

        /** 真能发出去的文档块（`null` / 全失败 → 空表，调用方不用判 null）。 */
        List<Att> usableAtts() {
            List<Att> out = new ArrayList<Att>(1);
            if (atts != null) for (Att a : atts) if (a != null && a.usable()) out.add(a);
            return out;
        }
    }

    // ---------------------------------------------------------------- token 估算

    /**
     * 文字 → token 的**估算**。
     *
     * ⚠️ 手机上**没有 tokenizer**，这只是估算：中文按 1.5 字/token、ASCII 按 4 字符/token，
     * 每条消息再加 4（角色/分隔开销）。误差大概 ±20%。
     *
     * 为什么敢用估算：这个数唯一用途是"别把请求撑爆"，不是报价。所以宁可**保守**（估大）。
     */
    static int textTokens(String s) {
        if (s == null || s.length() == 0) return 4;
        int cjk = 0, other = 0;
        for (int i = 0; i < s.length(); i += 1) {
            if (s.charAt(i) >= 0x2E80) cjk += 1; else other += 1;
        }
        return (int) Math.ceil(cjk / 1.5 + other / 4.0) + 4;
    }

    static int tokensOf(Msg m, boolean withImages) {
        if (m == null) return 0;
        int n = textTokens(m.text);
        // reasoning 是真要发出去的 token（带 tools 时），必须算进预算，否则"设了 8k 实际发 12k"
        if (m.reasoning != null) n += textTokens(m.reasoning);
        // ① server_tool_calls 回传成本：signature 是加密页面正文，客户端不可能知道多少 token，
        //    按固定 STC_SIG_TOKENS/call 估（宁可估大 —— 这个数只用来"别把请求撑爆"）。
        //    TODO(T2)：拿到 sidecar 正文后按 sources/action 实算文字部分，signature 仍走这个常量。
        if (m.stcRef != null) n += STC_SIG_TOKENS;
        // ② 文档附件：服务端解析出的文字量只能估（见 Att.tokens）
        if (m.atts != null) {
            for (Att a : m.atts) if (a != null) n += a.tokens();
        }
        // ③ 图的估算不变
        if (m.shots != null) {
            for (Shot s : m.shots) n += withImages ? Math.max(1, s.tokens) : textTokens(s.mark());
        }
        return n;
    }

    /**
     * 一轮 `server_tool_calls` 回传的**粗估** token（按 1 个 call 算）。
     *
     * 为什么是常量：`signature` 是服务端加密过的页面正文，**解出来多少 token 客户端不可能知道**。
     * T0 实测（探针 6）：一个 `read_link` 的 `signature` 就有 **51KB** —— 按 1200 估会把预算算爆
     * （一次 3 页搜索实际吃掉十几 k，估成 3.6k，于是"预算 64k"实际发了 80k）。
     * 6000 = "单页正文保守估"（51KB 密文解出来约一两万字，取 6k 并**宁可估大**）：
     * 这个数唯一用途是"别把请求撑爆"，不是报价。
     *
     * TODO(T2)：拿到 sidecar 正文后可按"这次搜了几页"乘页数微调文字部分，
     *   但 `signature` 那部分仍然只能靠这个常量。
     */
    static final int STC_SIG_TOKENS = 6000;

    /**
     * **stc 优先降级**：预算超了先把"非最近一轮"的 `stcRef` 摘掉，摘完还不够再交给
     * {@link #trim(List, int, int)} 去丢整轮。
     *
     * 为什么单独一步：`server_tool_calls` 会随轮数线性膨胀（每轮读 N 页的加密正文），
     * 一旦超预算就整轮丢掉，等于"刚问的第 3 条突然记不住了"。顺序是
     * "先降引用 → 再降图（现有 imgWindow）→ 最后丢轮"。
     *
     * ⚠️ 只改**最近一轮之外**的消息（最近一个 `user` 及其之后的 assistant 一律不动），
     * 而且只改内存里的这一份 `Msg`：磁盘上的行没动，下一轮 `load()` 还是全的。
     *
     * @param kept   已经过 {@code trim} 的列表（原地修改）
     * @param budget token 预算（<=0 = 不设预算，直接返回）
     */
    static void trimStc(List<Msg> kept, int budget) {
        if (kept == null || kept.isEmpty() || budget <= 0) return;

        int sum = 0;
        for (Msg m : kept) sum += tokensOf(m, true);
        if (sum <= budget) return;

        // 最近一轮的起点：最后一个 user 的下标（没有 user 就整段都不动）
        int lastUser = kept.size();
        for (int i = kept.size() - 1; i >= 0; i -= 1) {
            if ("user".equals(kept.get(i).role)) { lastUser = i; break; }
        }

        for (int i = 0; i < lastUser && sum > budget; i += 1) {
            Msg m = kept.get(i);
            if (m != null && m.stcRef != null) {
                m.stcRef = null;
                sum -= STC_SIG_TOKENS;
            }
        }
    }

    // ---------------------------------------------------------------- 裁剪

    /**
     * 按**双闸**裁剪：先按轮数、再按 token 预算，从最老的整轮开始丢。
     *
     * 一条 user 后面跟着它的 assistant 回答才算一轮。只丢 user 会留下"无源之答"，
     * 模型会把它当成自己随口说的话，追问链就断了。所以切点永远落在 user 上。
     */
    static List<Msg> trim(List<Msg> all, int maxTurns, int budget) {
        List<Msg> out = new ArrayList<>();
        if (all == null || all.isEmpty()) return out;

        int start = 0;
        if (maxTurns > 0) {
            int seen = 0;
            for (int i = all.size() - 1; i >= 0; i -= 1) {
                if ("user".equals(all.get(i).role)) {
                    seen += 1;
                    if (seen == maxTurns) { start = i; break; }
                }
            }
        }
        for (int i = start; i < all.size(); i += 1) out.add(all.get(i));

        if (budget > 0) {
            int sum = 0;
            int cut = 0;
            for (int i = out.size() - 1; i >= 0; i -= 1) {
                int t = tokensOf(out.get(i), true);   // 按最坏情况算（图全带）
                if (sum + t > budget && i < out.size() - 1) { cut = i + 1; break; }
                sum += t;
            }
            if (cut > 0) {
                while (cut < out.size() && !"user".equals(out.get(cut).role)) cut += 1;
                out = new ArrayList<>(out.subList(Math.min(cut, out.size()), out.size()));
            }
        }
        return out;
    }

    /**
     * 把裁好的消息渲染成"这次真发什么"。
     *
     * @param imgWindow 只有**最近 N 张**图真的发出去，更早的换成占位文字（0 = 一张都不发）
     */
    static List<Out> render(List<Msg> msgs, int imgWindow, Reader read) {
        List<Out> res = new ArrayList<>(msgs.size());
        if (msgs == null) return res;

        int total = 0;
        for (Msg m : msgs) total += m.shots == null ? 0 : m.shots.size();
        int keepFrom = Math.max(0, total - Math.max(0, imgWindow));
        int idx = 0;

        for (Msg m : msgs) {
            Out o = new Out(m.role, m.text);
            o.reasoning = m.reasoning;
            // 模式与附件直接带过去；stcJson 由调用方（EyeService）从 sidecar 读后填 ——
            // Conv 不碰磁盘，这样这一段在电脑 JVM 上也能测。
            o.mode = m.mode;
            o.atts = m.atts;
            if (m.shots != null) {
                for (Shot s : m.shots) {
                    boolean keep = idx >= keepFrom;
                    idx += 1;
                    byte[] bytes = keep ? read.read(s.file) : null;
                    if (bytes != null && bytes.length > 0) o.addImage(bytes);
                    else o.addMark(s.mark());
                }
            }
            res.add(o);
        }
        return res;
    }

    interface Reader {
        byte[] read(String file);
    }

    /** 该拿去写摘要的是哪几条：**被裁掉的、且还没压过的**。 */
    static List<Msg> toSummarize(List<Msg> all, List<Msg> kept, long upTo) {
        List<Msg> out = new ArrayList<>();
        if (all == null || all.isEmpty()) return out;
        long newestKeptAt = (kept == null || kept.isEmpty()) ? Long.MAX_VALUE : kept.get(0).at;
        for (Msg m : all) {
            if (m.at < newestKeptAt && m.at > upTo) out.add(m);
        }
        return out;
    }

    /**
     * 把要压的几条拼成给模型看的原文。
     *
     * **只取正文，不取 reasoning** —— 思考过程体积大、对后文几乎无用（DSH 自己也只在
     * 同一 turn 内用它），压进摘要等于拿钱换噪音。
     */
    static String digest(List<Msg> msgs) {
        StringBuilder sb = new StringBuilder();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT);
        if (msgs != null) {
            for (Msg m : msgs) {
                sb.append("assistant".equals(m.role) ? "它：" : "我：")
                  .append(f.format(new java.util.Date(m.at))).append(' ')
                  .append(m.text).append('\n');
            }
        }
        return sb.toString();
    }
}
