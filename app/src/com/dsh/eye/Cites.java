package com.dsh.eye;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 引用（角标）的全部纯计算。
 *
 * ## 为什么不是"把 `[1]` 塞进正文"
 *
 * 官方 `content` 里**没有** `[1]`，它只在最后一帧给 `annotations[]`：`start_index` /
 * `end_index` 是**码点**下标（探针 5 已实测确认，见 {@link #IS_UTF16}），指向原始正文。
 *
 * 而界面渲染要过 `Markdown.render`：`**` 被吃掉、`[](url)` 被裁掉 —— 渲染后的
 * char 偏移和原文**完全对不上**。所以位置映射只能在渲染**之前**做，用哨兵：
 *
 * ```
 * ① inject()          干净正文 → 指定码点位置插入 \u0001<序号>\u0002（哨兵）
 * ② Markdown.render() 哨兵原样穿过（它只特殊处理 * ` [ 三个字符）
 * ③ materialize()     （T3：CiteSpans）扫 spannable 把哨兵换成可点 span
 * ```
 *
 * 磁盘上的正文**永远是干净的**（没有 `[1]`、没有哨兵）。复制时才用
 * {@link #plainWithMarkers} 现算 `[1]` / `[1,2]`。
 *
 * ## 纯 Java
 *
 * 不 import android.* / org.json，可在电脑 JVM 上直接测（`T1Test`）。
 * T3 的 `CiteSpans`（Spannable）与 T2 的 `Cites.build(JSONArray)` **都不在这个类里**。
 */
final class Cites {

    private Cites() { }

    /** 哨兵：`\u0001` + 序号（`1` 或 `1,2`）+ `\u0002`。两个都是 C0 控制符，正文里不该出现。 */
    static final char MARK = '\u0001';
    static final char END = '\u0002';

    /**
     * 探针 5 的结论开关。**已实测：是码点**（8 个 emoji 之后引用切片仍逐句对齐），
     * 所以这里保持 {@code false}，{@link #cpToChar} 走代理对折算。
     *
     * 留这个开关是因为"官方哪天改成 UTF-16 单元"只需要翻一行：翻开后 {@code cpToChar}
     * 退化成恒等映射，其余代码一个字都不用动。
     */
    static final boolean IS_UTF16 = false;

    /** 一个引用。`cpStart/cpEnd` 是服务端口径的**码点**下标，落盘原样保留。 */
    static final class Cite {
        int cpStart;
        int cpEnd;
        /** 角标数字（来源列表里的序号，1-based）。 */
        int ordinal;
        String url;
        String title;
        String snippet;

        Cite() { }

        static Cite of(int cpStart, int cpEnd, int ordinal, String url, String title, String snippet) {
            Cite c = new Cite();
            c.cpStart = cpStart;
            c.cpEnd = cpEnd;
            c.ordinal = ordinal;
            c.url = url;
            c.title = title;
            c.snippet = snippet;
            return c;
        }

        public String toString() {
            return "[" + ordinal + " " + cpStart + ".." + cpEnd + " " + url + "]";
        }
    }

    // ---------------------------------------------------------------- 码点 → char

    /**
     * 码点下标 → Java String 的 char 偏移（代理对占两个 char）。
     *
     * 越界一律夹到 `s.length()`（**不抛**）—— `end_index == len` 是合法的（引用到文末），
     * 而多算一位的代价只是"角标跑到末尾"，比崩掉好一万倍。
     */
    static int cpToChar(String s, int cp) {
        if (s == null || cp <= 0) return 0;
        if (IS_UTF16) return Math.min(cp, s.length());
        int n = s.length();
        int i = 0;
        int k = 0;
        while (i < n && k < cp) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < n
                && Character.isLowSurrogate(s.charAt(i + 1))) {
                i += 2;
            } else {
                i += 1;
            }
            k += 1;
        }
        return i;
    }

    /** 这段文字有几个码点（测试与日志用）。 */
    static int cpCount(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    // ---------------------------------------------------------------- 哨兵注入

    /**
     * 在**干净正文**里插入哨兵，供渲染期物化。
     *
     * 三条规矩：
     *   1. 先把正文里可能存在的 `\u0001` / `\u0002` 全删掉（防"提示注入伪造角标"）；
     *   2. 同一位置的多个引用合并成一个哨兵（`[1,2]`）；
     *   3. 越界 / 序号非法的那一条**跳过**，绝不抛。
     */
    static String inject(String text, List<Cite> cites) {
        return place(sanitize(text), cites, true);
    }

    /**
     * 复制用的降级文本：把引用位置换成 `[1]` / `[1,2]` 纯文本。
     *
     * 入参是**磁盘上的干净正文**（没有角标），所以"复制"永远是"读磁盘 + 现算角标"。
     */
    static String plainWithMarkers(String text, List<Cite> cites) {
        return place(sanitize(text), cites, false);
    }

    /**
     * 把 `\u0001` / `\u0002` 从正文里清掉（用户/网页内容里可能带，不能让它伪造角标）。
     */
    static String sanitize(String s) {
        if (s == null) return "";
        if (s.indexOf(MARK) < 0 && s.indexOf(END) < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i += 1) {
            char c = s.charAt(i);
            if (c != MARK && c != END) b.append(c);
        }
        return b.toString();
    }

    /**
     * 共用落点算法：按码点位置分组 → 升序插入标记。
     *
     * @param clean    已经 sanitize 过的正文
     * @param sentinel true = 插哨兵；false = 插 `[n]`
     */
    private static String place(String clean, List<Cite> cites, boolean sentinel) {
        String s = clean == null ? "" : clean;
        if (cites == null || cites.isEmpty()) return s;

        int cpLen = cpCount(s);
        TreeMap<Integer, TreeSet<Integer>> at = new TreeMap<Integer, TreeSet<Integer>>();
        for (Cite c : cites) {
            if (c == null) continue;
            if (c.ordinal <= 0) continue;                  // 序号非法：跳过（宁可没角标，不要 [0]）
            if (c.cpEnd < 0 || c.cpEnd > cpLen) continue;   // 越界：跳过，绝不抛
            int pos = cpToChar(s, c.cpEnd);
            TreeSet<Integer> ords = at.get(Integer.valueOf(pos));
            if (ords == null) {
                ords = new TreeSet<Integer>();
                at.put(Integer.valueOf(pos), ords);
            }
            ords.add(Integer.valueOf(c.ordinal));
        }
        if (at.isEmpty()) return s;

        StringBuilder b = new StringBuilder(s.length() + at.size() * 6);
        int last = 0;
        for (Map.Entry<Integer, TreeSet<Integer>> e : at.entrySet()) {
            int pos = e.getKey().intValue();
            if (pos > s.length()) pos = s.length();       // 防御：TreeMap 升序，理论上到不了
            b.append(s, last, pos);
            b.append(mark(e.getValue(), sentinel));
            last = pos;
        }
        b.append(s, last, s.length());
        return b.toString();
    }

    /** `\u0001"1,2"\u0002` 或 `[1,2]`。 */
    private static String mark(TreeSet<Integer> ords, boolean sentinel) {
        StringBuilder sb = new StringBuilder(8);
        sb.append(sentinel ? MARK : '[');
        boolean first = true;
        for (Integer o : ords) {
            if (!first) sb.append(',');
            sb.append(o.intValue());
            first = false;
        }
        sb.append(sentinel ? END : ']');
        return sb.toString();
    }

    // ---------------------------------------------------------------- 来源列表

    /**
     * 按**出现顺序**去重（给 T2 编号用）。
     *
     * 编号口径（ARCH §5.3）：来源列表 = `server_tool_calls` 里所有 step 的
     * `calls[].sources[]` 按出现顺序展开、按 url 去重；`ordinal` = 该 url 在这里的序号。
     * 代价是正文里第一次出现的角标可能不是 1（跳号）—— 允许，因为"来源 2 / 4"里的 2
     * 必须和角标 2 指向同一个 url。
     */
    static List<String> dedupeSources(List<String> urls) {
        List<String> out = new ArrayList<String>();
        if (urls == null) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String u : urls) {
            if (u == null) continue;
            String t = u.trim();
            if (t.length() == 0) continue;
            if (seen.add(t)) out.add(t);
        }
        return out;
    }

    /** 该 url 在来源列表里的序号（1-based）；不在列表里返回 0。 */
    static int ordinalOf(List<String> sources, String url) {
        if (sources == null || url == null) return 0;
        String t = url.trim();
        for (int i = 0; i < sources.size(); i += 1) {
            if (t.equals(sources.get(i))) return i + 1;
        }
        return 0;
    }

    // ---------------------------------------------------------------- 待 T2 / T3

    /*
     * TODO(T2)：`Cites.build(String text, JSONArray annotations, List<Cite> sources)` 不在这里 ——
     *   它要读 org.json（服务端返回的 `annotations[]`），而本类刻意保持纯 Java 以便离线测。
     *   落点：`Asker` 在 `streamChat` 返回后调（ARCH §4.3 的 onText 之前那一步），
     *   拿到 `ann` + `stcRaw` 里的 sources 之后组装 List<Cite>，再交给本类的 inject/plainWithMarkers。
     *   ⚠️ 只许读 `annotations[].start_index/end_index/url/title` 与 `sources[].url/title/snippet`，
     *   **绝不许**把 `annotations[].title` 当成会话标题（那是"来源标题"，见 ARCH §4.1 / §5.3）。
     *
     * TODO(T3)：`materialize(CharSequence, List<Cite>)` 不在 T1 —— 它要产 Spannable
     *   （ClickableSpan / SuperscriptSpan），是 UI 层的事。T3 另建 `CiteSpans`。
     */
}
