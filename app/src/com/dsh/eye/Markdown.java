package com.dsh.eye;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/**
 * 极简 Markdown —— 只认模型回答里**真的会出现**的六种东西。
 *
 * ## 为什么手写
 *
 * 项目是零依赖的（APK 113KB）。引 Markwon 要 +800KB、还带 AndroidX；而模型的回答
 * 只用得到：粗体 / 斜体 / 行内代码 / 代码块 / 列表 / 标题。手写一个够用的约 130 行。
 *
 * **认不出来的东西原样输出** —— 这是刻意的：解析器坏掉的正确表现是"显示得像原文"，
 * 不是"吞掉一段"。所以这里没有"严格模式"，任何没配对的 `**` 都按字面保留。
 *
 * ## 一个已知取舍
 *
 * 代码块不做语法高亮（那是另一个 300 行），也不做横向滚动容器（TextView 里放不了），
 * 只用等宽 + 深色底 + 左侧竖线表示"这是一段代码"。手机屏幕窄，这样就够读了。
 */
final class Markdown {

    private static final int CODE_BG = 0xFF1B222B;
    private static final int CODE_FG = 0xFF9FD3A7;
    private static final int QUOTE_FG = 0xFF8B98A5;

    private Markdown() { }

    /**
     * 把一段模型输出渲染成 TextView 能吃的东西。
     *
     * @param src 原始文本（可以含 Markdown）
     * @return 带 span 的 CharSequence；`src` 为空时返回空串
     */
    static CharSequence render(String src) {
        if (src == null || src.length() == 0) return "";
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = src.split("\n", -1);
        boolean inCode = false;

        for (int i = 0; i < lines.length; i += 1) {
            String line = lines[i];
            String trimmed = line.trim();

            // 代码围栏：``` 或 ```java
            if (trimmed.startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                int st = out.length();
                out.append("│ ");
                out.append(line);
                out.setSpan(new TypefaceSpan("monospace"), st, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(CODE_BG), st, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(CODE_FG), st, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }

            // 标题：### xxx
            if (trimmed.startsWith("#")) {
                int n = 0;
                while (n < trimmed.length() && trimmed.charAt(n) == '#') n += 1;
                String body = trimmed.substring(Math.min(n, trimmed.length())).trim();
                int st = out.length();
                appendInline(out, body);
                out.setSpan(new StyleSpan(Typeface.BOLD), st, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (n <= 2) {
                    out.setSpan(new android.text.style.RelativeSizeSpan(1.15f), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (i < lines.length - 1) out.append('\n');
                continue;
            }

            // 引用：> xxx
            if (trimmed.startsWith(">")) {
                int st = out.length();
                out.append("▎ ");
                appendInline(out, trimmed.substring(1).trim());
                out.setSpan(new ForegroundColorSpan(QUOTE_FG), st, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }

            // 无序列表：- / * / •
            if (isBullet(trimmed)) {
                out.append("• ");
                appendInline(out, trimmed.substring(1).trim());
                if (i < lines.length - 1) out.append('\n');
                continue;
            }

            // 有序列表：1. / 1)
            int ord = orderedMark(trimmed);
            if (ord > 0) {
                out.append(trimmed, 0, ord);          // 保留 "1. "
                appendInline(out, trimmed.substring(ord).trim());
                if (i < lines.length - 1) out.append('\n');
                continue;
            }

            appendInline(out, line);
            if (i < lines.length - 1) out.append('\n');
        }
        return out;
    }

    private static boolean isBullet(String s) {
        if (s.length() < 2) return false;
        char c = s.charAt(0);
        if (c != '-' && c != '*' && c != '•') return false;
        // `---` 是分隔线，不是列表；`**粗体**` 开头也不是列表
        return s.charAt(1) == ' ' || s.charAt(1) == '\t';
    }

    /** `1. ` / `12) ` 这类前缀的长度；不是有序列表返回 0。 */
    private static int orderedMark(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i += 1;
        if (i == 0 || i > 3) return 0;
        if (i < s.length() && (s.charAt(i) == '.' || s.charAt(i) == ')' || s.charAt(i) == '、')) {
            int m = i + 1;
            if (m < s.length() && s.charAt(m) == ' ') m += 1;
            return m;
        }
        return 0;
    }

    /**
     * 行内元素：`**粗**` / `*斜*` / `` `代码` `` / `[文字](链接)`。
     *
     * 单趟扫描，遇到配不上的标记就**当成普通字符吐出去**（不吞内容）。
     */
    private static void appendInline(SpannableStringBuilder out, String s) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);

            if (c == '*' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("**", i + 2);
                if (end > i + 2) {
                    int st = out.length();
                    out.append(s, i + 2, end);
                    out.setSpan(new StyleSpan(Typeface.BOLD), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (c == '*' && (i + 1 >= n || s.charAt(i + 1) != '*')) {
                int end = s.indexOf('*', i + 1);
                if (end > i + 1 && end - i <= 40) {          // 别把跨段落的星号配成斜体
                    int st = out.length();
                    out.append(s, i + 1, end);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i) {
                    int st = out.length();
                    out.append(s, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(CODE_BG), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '[') {
                int rb = s.indexOf(']', i);
                if (rb > i && rb + 1 < n && s.charAt(rb + 1) == '(') {
                    int rp = s.indexOf(')', rb + 1);
                    if (rp > rb) {
                        // 只留文字，链接丢掉（手机上点不开也不该显示一长串 URL）
                        out.append(s, i + 1, rb);
                        i = rp + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i += 1;
        }
    }
}
