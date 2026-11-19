package com.dsh.eye;

import android.graphics.Color;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link Cites#inject} 埋下的**哨兵**物化成可点的上标角标。
 *
 * ## 为什么需要一个单独的类
 *
 * `Cites` 是纯 Java（不 import android），它只负责"在正确的位置埋 `\u0001 1,2 \u0002`"；
 * 而"把哨兵换成 span"必须碰 `Spannable` / `ClickableSpan` / `MotionEvent`，是界面层的事。
 * 中间隔着 {@link Markdown#render(String, List)} 这一层渲染 —— 哨兵能原样穿过去
 * （`Markdown` 只特殊处理 `*` `` ` `` `[` 三个字符），所以渲染后的位置就是原文的位置。
 *
 * ## 三道防御
 *
 * 1. **坏哨兵一律跳过，绝不抛**：只有 `MARK ... END` 成对、中间全是数字与逗号时才物化，
 *    否则只把那两个控制符删掉（宁可少一个角标，也不能让一条回答崩掉整个界面）。
 * 2. **从后往前替换**：`SpannableStringBuilder.replace()` 会让后面所有 span 的下标平移，
 *    倒着处理就不会把自己踩歪。
 * 3. **点击只落在角标上**：`Touch` 只有在下手那一刻**真的点到角标**时才接管手势，
 *    否则立刻返回 false —— 这样"长按气泡弹菜单"和其它文本行为一点都没变。
 *
 * ## 长按为什么不用 `OnLongClickListener`
 *
 * 角标是字符级的东西，长按要判"落在哪个 span 上"，只能在 `MovementMethod` 里做。
 * `onLongClick` 的粒度是整个 TextView，判不出是第几个角标。
 */
final class CiteSpans {

    private CiteSpans() { }

    /**
     * 角标被点 / 长按时要做什么。
     *
     * <p>`all` 是**这条消息的完整来源列表** —— 来源面板要显示「来源 2 / 4」，
     * 那个 4 只能从列表长度来，不能从 Span 里推。所以让 Span 自己抱着列表，
     * 界面层就不需要维护"哪条消息用哪份引用"的映射表（历史消息也一样能点开）。
     */
    interface Cb {
        /**
         * 点了角标。
         *
         * @param c   被点的那个引用（`ordinal` 是它在来源列表里的序号）
         * @param all 这条消息的全部引用（可能为 null）
         */
        void onCite(Cites.Cite c, List<Cites.Cite> all);

        /** 长按角标：按 PRD 是"直接复制 URL"。默认什么都不做。 */
        default void onCiteLong(Cites.Cite c) { }
    }

    // ---------------------------------------------------------------- 物化

    /**
     * 扫渲染结果里的哨兵，换成上标 + 强调色 + 可点的角标。
     *
     * @param rendered {@link Markdown#render(String)} 的产物（哨兵原样在里面）
     * @param cites    这条消息的引用（用来查 URL / 标题；`ordinal` 要和哨兵里的数字对上）
     * @param cb       点击回调（可以传 null，之后再 {@link #bind} 补上）
     * @return 新的 CharSequence；**没有哨兵时原样返回入参**（不复制、不丢原来的 span）
     */
    static CharSequence materialize(CharSequence rendered, List<Cites.Cite> cites, Cb cb) {
        if (rendered == null) return "";
        if (cites == null || cites.isEmpty()) return rendered;

        SpannableStringBuilder out = rendered instanceof Spanned
            ? new SpannableStringBuilder(rendered)
            : new SpannableStringBuilder(rendered.toString());

        // 先把所有哨兵区间找出来（这时还没动过 out，下标都是准的）
        List<int[]> found = new ArrayList<int[]>();
        int i = 0;
        while (i < out.length()) {
            if (out.charAt(i) != Cites.MARK) { i += 1; continue; }
            int end = i + 1;
            while (end < out.length() && out.charAt(end) != Cites.END) end += 1;
            if (end < out.length()) found.add(new int[] { i, end });
            i = end + 1;
        }
        if (found.isEmpty()) return out;

        // 倒着替换：先动后面的，前面的下标就不受影响
        for (int k = found.size() - 1; k >= 0; k -= 1) {
            int st = found.get(k)[0];
            int end = found.get(k)[1];                    // END 的位置
            if (st < 0 || end >= out.length() || end <= st) continue;

            String nums = out.subSequence(st + 1, end).toString();
            int head = firstOrdinal(nums);
            Cites.Cite cite = findByOrdinal(cites, head);

            // 坏哨兵：只删控制符，不物化（正文里出现 \u0001 的唯一合理来源是被注入了）
            if (head <= 0) {
                out.delete(end, end + 1);
                out.delete(st, st + 1);
                continue;
            }

            String label = "[" + nums + "]";
            out.replace(st, end + 1, label);
            int ls = st;
            int le = st + label.length();
            if (le <= ls) continue;
            out.setSpan(new SuperscriptSpan(), ls, le, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new RelativeSizeSpan(0.7f), ls, le, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(Theme.ACCENT), ls, le,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new CiteSpan(cite, cites, cb), ls, le, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /**
     * 把点击行为装到 TextView 上（**每次 setText 之后都要调一次**）。
     *
     * <p>Markdown 那一层拿不到 TextView，所以 span 建出来时 `cb` 可能是 null；
     * 这里是"补上回调 + 装手势"的唯一入口。没有角标时什么都不做（不动原来的长按/选择）。
     */
    static void bind(TextView tv, Cb cb) {
        if (tv == null) return;
        CharSequence cs = tv.getText();
        if (!(cs instanceof Spannable)) return;
        Spannable sp = (Spannable) cs;
        CiteSpan[] spans = sp.getSpans(0, sp.length(), CiteSpan.class);
        if (spans == null || spans.length == 0) return;
        for (int i = 0; i < spans.length; i += 1) {
            if (spans[i] != null) spans[i].cb = cb;
        }
        tv.setHighlightColor(Color.TRANSPARENT);   // 点击时不要那块蓝底（太亮）
        tv.setLinksClickable(true);
        tv.setFocusable(false);
        tv.setMovementMethod(new Touch());
    }

    /** 这条 CharSequence 里有没有角标（决定要不要装手势）。 */
    static boolean has(CharSequence cs) {
        if (!(cs instanceof Spannable)) return false;
        Spannable sp = (Spannable) cs;
        CiteSpan[] spans = sp.getSpans(0, sp.length(), CiteSpan.class);
        return spans != null && spans.length > 0;
    }

    // ---------------------------------------------------------------- 内部

    /** `"1,2"` → 1；`""` / `"a"` → 0（坏哨兵）。 */
    private static int firstOrdinal(String nums) {
        if (nums == null || nums.length() == 0 || nums.length() > 24) return 0;
        int v = 0;
        boolean any = false;
        for (int i = 0; i < nums.length(); i += 1) {
            char c = nums.charAt(i);
            if (c == ',') {
                if (!any) return 0;               // 以逗号开头
                break;                            // 只取第一段
            }
            if (c < '0' || c > '9') return 0;      // 非数字 = 坏哨兵
            v = v * 10 + (c - '0');
            any = true;
            if (v > 999) return 0;
        }
        return any ? v : 0;
    }

    private static Cites.Cite findByOrdinal(List<Cites.Cite> cites, int ordinal) {
        if (cites == null || ordinal <= 0) return null;
        for (int i = 0; i < cites.size(); i += 1) {
            Cites.Cite c = cites.get(i);
            if (c != null && c.ordinal == ordinal) return c;
        }
        return null;
    }

    /** 一个可点的角标。点击 → {@link Cb#onCite}。 */
    static final class CiteSpan extends ClickableSpan {
        final Cites.Cite cite;
        final List<Cites.Cite> all;
        Cb cb;

        CiteSpan(Cites.Cite cite, List<Cites.Cite> all, Cb cb) {
            this.cite = cite;
            this.all = all;
            this.cb = cb;
        }

        /** 强调色、不上划线（下划线在深色正文里像一条亮线，比角标本身还显眼）。 */
        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setColor(Theme.ACCENT);
            ds.setUnderlineText(false);
        }

        @Override
        public void onClick(View w) {
            if (cb != null) cb.onCite(cite, all);
        }
    }

    /**
     * 只对角标生效的 `MovementMethod`。
     *
     * <p>两种手势：**点** → 开来源面板；**长按 420ms** → 复制 URL。
     *
     * <p>下手位置不在角标上时**立刻返回 false**，把整个手势交回 TextView 自己 ——
     * 否则"长按气泡弹菜单"会被这里吃掉（`LinkMovementMethod` 默认就吃）。
     */
    private static final class Touch extends LinkMovementMethod {

        private static final long LONG_MS = 420;
        private static final int SLOP_DP = 10;

        private Runnable pending;
        private CiteSpan down;
        private boolean fired;
        private float downX;
        private float downY;

        @Override
        public boolean onTouchEvent(final TextView w, final Spannable buf, MotionEvent e) {
            int act = e.getActionMasked();
            try {
                if (act == MotionEvent.ACTION_DOWN) {
                    fired = false;
                    down = hit(w, buf, e.getX(), e.getY());
                    if (down == null) {
                        drop(w);
                        return false;              // 没点到角标：整个手势都不归我管
                    }
                    downX = e.getX();
                    downY = e.getY();
                    pending = new Runnable() {
                        public void run() {
                            pending = null;
                            fired = true;
                            CiteSpan s = down;
                            if (s != null && s.cb != null) s.cb.onCiteLong(s.cite);
                        }
                    };
                    w.postDelayed(pending, LONG_MS);

                } else if (act == MotionEvent.ACTION_UP) {
                    drop(w);
                    if (fired) { fired = false; down = null; return true; }

                } else if (act == MotionEvent.ACTION_CANCEL) {
                    drop(w);
                    if (fired) { fired = false; down = null; return true; }

                } else if (act == MotionEvent.ACTION_MOVE) {
                    if (down == null) return false;
                    if (Math.abs(e.getX() - downX) > dp(w, SLOP_DP)
                        || Math.abs(e.getY() - downY) > dp(w, SLOP_DP)) {
                        drop(w);                   // 手滑走了：不是长按，也不是点击
                        down = null;
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // 手势里任何异常都不能把界面拖死：退化成"没这个手势"
                return false;
            }
            return super.onTouchEvent(w, buf, e);
        }

        private void drop(TextView w) {
            if (pending != null) {
                w.removeCallbacks(pending);
                pending = null;
            }
        }

        /** 屏幕坐标 → 字符下标 → 那个位置上的 CiteSpan（没有就 null）。 */
        private CiteSpan hit(TextView w, Spannable buf, float ex, float ey) {
            Layout l = w.getLayout();
            if (l == null) return null;
            int x = (int) ex - w.getTotalPaddingLeft() + w.getScrollX();
            int y = (int) ey - w.getTotalPaddingTop() + w.getScrollY();
            if (y < 0 || y > l.getHeight()) return null;
            int line = l.getLineForVertical(y);
            if (line < 0 || line >= l.getLineCount()) return null;
            // 行外横向留白不算命中（否则点到行尾空白也会弹出面板）
            if (x < l.getLineLeft(line) - dp(w, 2) || x > l.getLineRight(line) + dp(w, 2)) return null;
            int off = l.getOffsetForHorizontal(line, x);
            if (off < 0 || off > buf.length()) return null;
            CiteSpan[] spans = buf.getSpans(off, off, CiteSpan.class);
            if (spans == null || spans.length == 0) return null;
            return spans[0];
        }

        private static int dp(TextView w, int v) {
            return Math.round(v * w.getResources().getDisplayMetrics().density);
        }
    }
}
