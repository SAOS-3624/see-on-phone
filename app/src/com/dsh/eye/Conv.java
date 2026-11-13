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

        String mark() {
            String w = why == null || why.length() == 0 ? "画面" : why;
            return "[图已省略 · " + w + " · " + frames + " 帧 · 已存档]";
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
        int n = textTokens(m.text);
        // reasoning 是真要发出去的 token（带 tools 时），必须算进预算，否则"设了 8k 实际发 12k"
        if (m.reasoning != null) n += textTokens(m.reasoning);
        if (m.shots != null) {
            for (Shot s : m.shots) n += withImages ? Math.max(1, s.tokens) : textTokens(s.mark());
        }
        return n;
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
