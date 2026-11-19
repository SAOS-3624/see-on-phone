package com.dsh.eye;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 气泡渲染器：**消息区长什么样全在这个文件里**。
 *
 * 从 `MainActivity` 拆出来的（原来那个 `addBubble` 有一百多行，散在 1350 行的界面代码中间）。
 * 界面只负责"什么时候加一条"，这个类负责"一条长什么样"。
 *
 * ## 三条不能碰的规矩（交接文档第 4 节）
 *
 * 1. **气泡宽度不许交给 `WRAP_CONTENT`**。交给测量的话，`ScrollView → LinearLayout(WRAP_CONTENT)`
 *    这条链上算不出可用宽度，长句子会被折成一行八个字（实测过：两百字的中文，卡片正好
 *    等于里面那张缩略图的宽度）。所以左边/右边**必须放一个带权重的弹簧**占住剩余宽度，
 *    再自己夹到 {@link #MAX_RATIO}。
 * 2. **图必须和 card 是兄弟节点**，不能塞进 card 里 —— 塞进去 card 的 `WRAP_CONTENT`
 *    会塌成"缩略图宽度 + 内边距"。
 * 3. 助手气泡的**思考 chip 在 card 里面**（它是文字的一部分），图在外面。
 */
final class Bubbles {

    /** 气泡最宽占屏幕的比例。超过这条就分不清谁说的话了。 */
    static final float MAX_RATIO = 0.84f;

    /** 界面要处理的三类"用户动作"。 */
    interface Cb {
        /** 长按气泡（文字）。`at` 是这条消息的时间戳，0 = 还没落盘（"正在想"那种）。 */
        void onBubbleLong(boolean mine, String text, long at);
        /** 点图看大图。 */
        void onImageTap(String file);
        /** 长按图（"再问一句 / 复制文字 / 保存到相册"）。`text` = 这张图所在气泡的正文。 */
        void onImageLong(String file, String text);
        /** 展开思考过程。 */
        void onReasoning(String reasoning);
        /** 失败气泡下面那个「重试」。 */
        void onRetry(long at);
    }

    /**
     * 「这条消息要不要在前面插一张过程卡」。
     *
     * <p>从磁盘重铺时由界面提供（{@link Cards#history}），这样 `Bubbles` 不需要知道
     * 搜索卡 / 步骤条长什么样 —— 它只负责"卡片在气泡上方"这个位置关系。
     */
    interface CardFor {
        /** @return 卡片视图；null = 这条没有卡片 */
        View card(Conv.Msg m);
    }

    private final Activity a;
    private final Cb cb;
    private final float density;

    /** 角标点击的去处（由界面装进来；历史消息与实时消息共用同一个）。 */
    private CiteSpans.Cb citeCb;

    /** 装角标点击回调。**必须在使用前调**，否则角标点了没反应。 */
    void citeCb(CiteSpans.Cb c) {
        this.citeCb = c;
    }

    /**
     * 缩略图缓存。**必须缓存 + 后台解码**：以前每次重铺都在主线程把每条消息的图解一遍
     * （200 条 × 一次 JPEG 解码），进页面要卡一秒多。
     */
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(4096) {
        protected int sizeOf(String k, Bitmap b) {
            return Math.max(1, b.getByteCount() / 1024);
        }
    };
    private final java.util.concurrent.ExecutorService q =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    /** 正在解的那些：避免同一张图排两次队。 */
    private final java.util.Set<String> loading =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    Bubbles(Activity a, Cb cb) {
        this.a = a;
        this.cb = cb;
        this.density = a.getResources().getDisplayMetrics().density;
    }

    /** 界面销毁时调。 */
    void shutdown() {
        q.shutdownNow();
        // 不清空 LruCache 里的位图：可能还有 ImageView 正拿着它们画，回收会直接崩
        thumbs.evictAll();
    }

    // ------------------------------------------------------------ 对外：三种气泡

    /**
     * 用户气泡的把手。
     *
     * **为什么要有它**：图可能比字晚到 —— 通话页那一张是服务端现拼（40~80ms 拼图）
     * 再落盘的，界面先画出"我说了什么"，图到了再补进**同一个气泡**。
     *
     * 以前是反过来：回答那条气泡带图（`onReply` 里按"没显示过"筛一遍再挂上去），
     * 于是同一张图在磁盘上属于 user、在界面上却挂在 assistant 下面，
     * 重进会话就"多出一张"。现在**图只属于用户气泡**，AI 气泡一张都不带。
     */
    static final class UserBubble {
        final View view;
        private final LinearLayout stack;
        private final String text;
        /** 已经挂了几张（0 = 还没有图，服务补来的那批才认）。 */
        int shots;

        UserBubble(View view, LinearLayout stack, String text, int shots) {
            this.view = view;
            this.stack = stack;
            this.text = text;
            this.shots = shots;
        }
    }

    /** 我说的。渐变底 + 白字 + 右下尖角。 */
    UserBubble userBubble(String text, List<String> shots, long at) {
        final String body = text == null ? "" : text;

        LinearLayout stack = stack(Gravity.END);
        // 只发图不带字：不画一个空的彩色卡片，直接摆图
        if (body.length() > 0) {
            LinearLayout card = card(true);
            TextView tv = textView(body, true);
            card.addView(tv);
            card.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) { cb.onBubbleLong(true, body, at); return true; }
            });
            stack.addView(card, wrap());
        }
        int n = addShots(stack, shots, body);
        return new UserBubble(row(true, stack), stack, body, n);
    }

    /**
     * 图晚到了：补进**已经画出来的**用户气泡。
     *
     * 只在气泡里一张图都没有时补 —— 有图说明是相册发的（发的时候就已经在气泡里了），
     * 再补一遍就成了同一张图挂两次。
     */
    void addShots(UserBubble b, List<String> shots) {
        if (b == null || b.shots > 0) return;
        b.shots = addShots(b.stack, shots, b.text);
    }

    /**
     * 它说的。
     *
     * @param failed true = 这条是失败提示，下面挂一个「重试」
     */
    View ai(String text, String reasoning, long thinkMs, List<String> shots, long at,
            boolean failed) {
        return ai(text, reasoning, thinkMs, null, false, shots, at, failed, null);
    }

    /**
     * 它说的（**带引用角标 / 档位 / 停止态**的完整版）。
     *
     * <p>老签名转发到这里，`cites` / `effort` / `stopped` 全给空值 —— 于是
     * "没联网的老消息"和以前画出来的东西完全一样。
     *
     * @param effort  档位（`auto` 等）；null = 老消息，思考条不带「· 自动」这一截
     * @param stopped 这一轮被按了停止（思考条写「已停止」，正文里已经有「（已停止）」）
     * @param cites   引用；非空时正文里出现可点角标，长按复制仍是干净正文（见 `MainActivity.copy`）
     */
    View ai(String text, String reasoning, long thinkMs, String effort, boolean stopped,
            List<String> shots, long at, boolean failed, List<Cites.Cite> cites) {
        final String body = text == null ? "" : text;

        LinearLayout stack = stack(Gravity.START);
        LinearLayout card = card(false);
        // 只有它说的话渲染 Markdown —— 我自己打的字不该被当成格式
        TextView tv = textView(Markdown.render(body, cites), false);
        // 角标要能点：setText 之后把回调与手势装上去（没有角标时 bind 什么都不做）
        if (cites != null && !cites.isEmpty()) CiteSpans.bind(tv, citeCb);
        card.addView(tv);

        boolean hasReason = reasoning != null && reasoning.length() > 0;
        // 有思考过程就必须让用户能点开 —— 哪怕不知道它想了多久（老会话文件没记时长）
        if (thinkMs > 0 || hasReason) card.addView(thinkBar(thinkMs, effort, reasoning, stopped));

        card.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { cb.onBubbleLong(false, body, at); return true; }
        });
        stack.addView(card, wrap());
        addShots(stack, shots, body);

        if (failed) stack.addView(retryRow(at));
        return row(false, stack);
    }

    /** 「正在想…」那条。拿到回答后由界面 `removeView` 掉。 */
    LinearLayout thinking(String label) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(Theme.S2 + 2);
        row.setLayoutParams(lp);

        TextView tv = new TextView(a);
        tv.setText(label == null ? "正在想…" : label);
        tv.setTextColor(Theme.TEXT_DIM);
        tv.setTextSize(Theme.T_SUB + 1);
        tv.setPadding(dp(14), dp(9), dp(14), dp(9));
        tv.setBackground(Theme.pill(Theme.SURFACE));
        row.addView(tv);
        row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        return row;
    }

    /**
     * 一个"正在长"的回答气泡的完整生命周期：`begin → think* / append* → finish → end`。
     *
     * 抽出来是因为这套状态机（row / tv / buf 三个字段 + 三处互斥判断）原本横躺在
     * `MainActivity` 里，和"消息区该滚到哪"混在一起。现在界面只管调这几个方法。
     *
     * ## `finish(text)` 为什么必须存在（P0，真机踩到的）
     *
     * 真机上出现过"气泡停在中途、没有省略号"：流结束时最后一段没补上，而磁盘上那条
     * 消息是完整的（重进 App 就正常）。流式那条路的最后一步不可靠 —— 增量是一段段补的，
     * 只要**最后一次刷新没落到屏幕上**（视图先被摘掉 / 消息队列里那条没跑完），
     * 用户看到的就是半截话，而且**没有任何迹象**说明它被截断了。
     *
     * 所以收尾改成"**先用权威全文同步覆盖一次，再拆**"：
     * 服务端 `onReply` 拿到的 `text` 就是落盘那一份，把它同步写进正在显示的这条气泡，
     * 即使紧接着的移除/重建出了任何岔子，屏幕上那行字也已经是对的。
     * 验收口径就变成了结构性的：**气泡里的字 == 磁盘里那行的字**。
     */
    final class Stream {
        private final LinearLayout box;
        private View row;
        private TextView tv;
        private final StringBuilder buf = new StringBuilder();
        /** 收尾之后不再接受增量：迟到的 onDelta 不许把气泡"复活"成一条半截话。 */
        private boolean closed;

        // ---- 思考（流式）：折叠条 + 一行滚动灰字（PRD §6.6）----
        private LinearLayout thinkWrap;
        private TextView thinkHead;
        private TextView thinkLine;
        private final StringBuilder thinkBuf = new StringBuilder();
        private long thinkAt;
        private boolean thinkStopped;

        Stream(LinearLayout box) { this.box = box; }

        boolean active() { return row != null; }

        /** 流式过程中累计收到的字数（和 `onReply` 的权威全文对账用，见 MainActivity）。 */
        int chars() { return buf.length(); }

        void begin() {
            if (row != null) return;
            closed = false;

            LinearLayout st = stack(Gravity.START);
            LinearLayout cd = card(false);

            /**
             * 思考条**建出来就藏着**（GONE）。没有 reasoning 的那些轮次一个字都不该多
             * —— 留一个空框比不显示更糟（用户会以为"它是不是想了一下没想出来"）。
             */
            thinkWrap = new LinearLayout(a);
            thinkWrap.setOrientation(LinearLayout.VERTICAL);
            thinkWrap.setVisibility(View.GONE);
            thinkWrap.setPadding(0, 0, 0, dp(4));

            thinkHead = new TextView(a);
            thinkHead.setTextSize(Theme.T_SUB);
            thinkHead.setTextColor(Theme.TEXT_DIM);
            thinkHead.setSingleLine(true);
            thinkHead.setEllipsize(TextUtils.TruncateAt.END);

            thinkLine = new TextView(a);
            thinkLine.setTextSize(Theme.T_TINY);
            thinkLine.setTextColor(Theme.TEXT_DIM);
            thinkLine.setSingleLine(true);
            // 跑马灯：一行里横向滚动最新的思考（不换行、不撑高，PRD §6.6 的"滚动灰字"）
            thinkLine.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            thinkLine.setMarqueeRepeatLimit(-1);
            thinkLine.setSelected(true);
            thinkLine.setMaxWidth(maxBodyWidth());

            thinkWrap.addView(thinkHead, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(2);
            thinkWrap.addView(thinkLine, llp);

            cd.addView(thinkWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView body = textView("", false);
            cd.addView(body);
            st.addView(cd, wrap());

            row = row(false, st);
            tv = body;
            buf.setLength(0);
            thinkBuf.setLength(0);
            thinkAt = 0;
            thinkStopped = false;
            box.addView(row);
        }

        /**
         * 思考增量（`reasoning_content`）。
         *
         * <p>只保留**最后一行**画出来：思考正文可能几千字，全画出来会把答案挤到屏幕外，
         * 而用户此刻要看的是"它在动"，不是"它在想什么"（想看完可以点开折叠条）。
         */
        void think(String delta) {
            if (closed) return;
            if (row == null) begin();
            if (thinkWrap == null || delta == null || delta.length() == 0) return;
            if (thinkAt == 0) thinkAt = System.currentTimeMillis();
            thinkBuf.append(delta);
            thinkWrap.setVisibility(View.VISIBLE);
            thinkLine.setVisibility(View.VISIBLE);
            long ms = System.currentTimeMillis() - thinkAt;
            thinkHead.setText("▾ 思考中… " + Cards.secs(ms));
            thinkLine.setText(lastLine(thinkBuf.toString()));
        }

        /**
         * 思考结束（首个正文 token 到达）：折叠条定型。
         *
         * @param ms     想了多久（服务端/编排层给的；0 = 用本地首末差兜一个）
         * @param effort 档位（写进「· 自动」那一截；null = 不写）
         */
        void thinkDone(long ms, String effort) {
            if (thinkWrap == null || thinkWrap.getVisibility() != View.VISIBLE) return;
            if (thinkStopped) return;              // 已经按过停止：不再被迟到的收尾覆盖
            long real = ms > 0 ? ms : (thinkAt > 0 ? System.currentTimeMillis() - thinkAt : 0);
            thinkLine.setVisibility(View.GONE);
            thinkHead.setText(thinkTitle(real, effort, false, true));
        }

        /** 用户按了停止：思考条保留已产生的部分并标注「已停止」（PRD P0-5 验收第 3 条）。 */
        void thinkStopped() {
            if (thinkWrap == null || thinkWrap.getVisibility() != View.VISIBLE) return;
            thinkStopped = true;
            long real = thinkAt > 0 ? System.currentTimeMillis() - thinkAt : 0;
            thinkLine.setVisibility(View.GONE);
            thinkHead.setText(thinkTitle(real, null, true, true));
        }

        /** 追加一段增量；**每段都重新渲染 Markdown**（否则用户会看到满屏 `**`）。 */
        void append(String delta) {
            if (closed) return;                 // 收尾了：迟到的增量丢掉
            if (row == null) begin();
            if (delta != null) buf.append(delta);
            if (tv == null) return;
            tv.setText(Markdown.render(buf.toString()));
            fitWidth(tv, tv.getText());
        }

        /**
         * 用**权威全文**收尾（`onReply` 的 text，和落盘那份逐字节一致）。
         *
         * 必须在 UI 线程调用，而且要在 {@link #end()} **之前** —— 它是"宁可多刷一次、
         * 也不能少一个字"的那一次。
         */
        void finish(String full) {
            finish(full, null);
        }

        /**
         * 收尾（**带引用角标**）。
         *
         * <p>为什么联网那一轮必须走这个重载：`annotations[]` 只在**最后一帧**到，
         * 流式期间那条气泡是**没有角标**的。收尾必须用权威全文 + 角标同步覆盖一次，
         * 否则用户看到的是"文字对、但引用全丢"的答案，而磁盘上其实是全的。
         */
        void finish(String full, List<Cites.Cite> cites) {
            if (full != null && full.length() > 0 && tv != null) {
                CharSequence cs = Markdown.render(full, cites);
                tv.setText(cs);
                if (cites != null && !cites.isEmpty()) CiteSpans.bind(tv, citeCb);
                fitWidth(tv, cs);
                tv.requestLayout();
            }
            closed = true;
        }

        void end() {
            closed = true;
            if (row == null) return;
            // parent 检查：视图可能已经被 reload() 的 removeAllViews() 摘走了，
            // 这时候再 removeView 是空操作，但 row 引用必须清掉，否则下次 begin() 会错乱
            try {
                if (row.getParent() == box) box.removeView(row);
            } catch (Throwable ignored) { }
            row = null;
            tv = null;
            thinkWrap = null;
            thinkHead = null;
            thinkLine = null;
            buf.setLength(0);
            thinkBuf.setLength(0);
            thinkAt = 0;
        }

        /** 思考正文的"最后一行"（跑马灯只画这一行）。 */
        private String lastLine(String s) {
            if (s == null) return "";
            int end = s.length();
            while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end -= 1;
            int start = end;
            while (start > 0) {
                char c = s.charAt(start - 1);
                if (c == '\n' || c == '\r') break;
                start -= 1;
            }
            String line = s.substring(start, end).trim();
            return line.length() > 240 ? line.substring(line.length() - 240) : line;
        }
    }

    Stream stream(LinearLayout box) { return new Stream(box); }

    /**
     * 从磁盘重铺：把 `msgs` 里最后 {@code max} 条铺进 `box`。
     *
     * **图挂在"回答"上，不挂在"提问"上**：磁盘上那张图是挂在 user 那条上的
     * （发请求时图必须在 user 的 content 里，否则接口不认），但显示的时候它是
     * "它看到的画面"，摆在回答下面才对。所以这里往前看一眼：上一条 user 带了图，
     * 就把它交给这条 assistant。
     */
    void render(LinearLayout box, List<Conv.Msg> msgs, int max) {
        render(box, msgs, max, null);
    }

    /**
     * 从磁盘重铺（**带历史卡片**）。
     *
     * @param cf 每条消息要不要在前面插一张卡（搜索卡 / 步骤条）；null = 不插
     */
    void render(LinearLayout box, List<Conv.Msg> msgs, int max, CardFor cf) {
        if (msgs == null || msgs.isEmpty()) return;
        int from = Math.max(0, msgs.size() - max);
        if (from > 0) {
            TextView more = new TextView(a);
            more.setText("（更早的 " + from + " 条不在这一屏）");
            more.setTextColor(Theme.TEXT_DIM);
            more.setTextSize(Theme.T_TINY);
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, dp(6), 0, dp(2));
            box.addView(more);
        }
        for (int i = from; i < msgs.size(); i += 1) {
            Conv.Msg m = msgs.get(i);
            List<String> files = shotsOf(m);
            /**
             * **图只挂在"发出去"那一条上**（磁盘也是这么存的：`Msg.shots` 属于 user）。
             *
             * 以前这里把上一条 user 的图也递给 ai 气泡（`shotFiles`），于是同一张图
             * 在"我的"和"它的"下面各出现一次；live 还有一份等价的补丁（`shownShots`）。
             * 两处都删掉了 —— 图属于谁，看 `Msg.shots` 就够了。
             */
            if ("user".equals(m.role)) {
                box.addView(userBubble(m.text, files, m.at).view);
            } else {
                // 卡片在气泡**上方**（PRD §6.1 的线框：用户消息 → 卡片 → 思考条 → 答案）
                if (cf != null) {
                    View card = cf.card(m);
                    if (card != null) box.addView(card);
                }
                /**
                 * 历史消息只读重建：角标（`Msg.cites`）、档位（`Msg.effort`）、
                 * 停止态（正文尾巴上的「（已停止）」）都是从磁盘读出来的**原样**，
                 * 一个字都不重算 —— 重算就会算出跟当时不一样的耗时和编号。
                 */
                boolean stopped = m.text != null && m.text.indexOf("（已停止）") >= 0;
                box.addView(ai(m.text, m.reasoning, m.thinkMs, m.effort, stopped,
                    null, m.at, false, m.cites));
            }
        }
    }

    private static List<String> shotsOf(Conv.Msg m) {
        List<String> out = new ArrayList<String>();
        if (m == null || m.shots == null) return out;
        for (Conv.Shot sh : m.shots) if (sh.file != null) out.add(sh.file);
        return out;
    }

    /**
     * 流式/一次性文字的统一渲染（宽度自己夹，见类注释第 1 条）。
     *
     * ⚠️ 这里走的是**显式 LayoutParams 宽度**，不是 `TextView.setWidth()`。
     * 真机上踩过：`setWidth()` 只设了 `maxWidth/minWidth`，而父容器（`WRAP_CONTENT` 的 card）
     * 在某些测量顺序下会先给一个 `AT_MOST(小值)`，`resolveSizeAndState` 就把它夹成了 432px ——
     * 于是 838 的 maxWidth 摆在那儿、气泡却只有半屏宽，长段落被折成一行九个字。
     * 显式 LP 宽度会变成 `EXACTLY(w)`，父容器再怎么量都改不了它。
     */
    void fitWidth(TextView tv, CharSequence text) {
        if (tv == null) return;
        int w = bodyWidth(tv, text);
        ViewGroup.LayoutParams lp = tv.getLayoutParams();
        if (lp == null || !(lp instanceof LinearLayout.LayoutParams)) {
            tv.setLayoutParams(new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else if (lp.width != w) {
            lp.width = w;
            tv.setLayoutParams(lp);
        }
    }

    /** 这段文字该占多宽：不小于 20dp，不超过 {@link #MAX_RATIO} 的屏宽。 */
    int bodyWidth(TextView tv, CharSequence text) {
        int maxBody = maxBodyWidth();
        float want = Layout.getDesiredWidth(text == null ? "" : text, tv.getPaint());
        return Math.max(dp(20), (int) Math.ceil(Math.min(want, maxBody)));
    }

    int maxBodyWidth() {
        return (int) (a.getResources().getDisplayMetrics().widthPixels * MAX_RATIO) - dp(24);
    }

    // ------------------------------------------------------------ 缩略图

    /** 先看缓存，没有再丢后台解；解完确认这个 ImageView 还挂着才敢设。 */
    void attach(final ImageView iv, final String file) {
        if (iv == null || file == null) return;
        iv.setTag(file);
        Bitmap cached = thumbs.get(file);
        if (cached != null) { iv.setImageBitmap(cached); return; }
        if (!loading.add(file)) return;
        q.submit(new Runnable() {
            public void run() {
                final Bitmap b = loadThumb(file);
                loading.remove(file);
                if (b == null) return;
                thumbs.put(file, b);
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (iv.getParent() != null && file.equals(iv.getTag())) iv.setImageBitmap(b);
                    }
                });
            }
        });
    }

    /** 同步解码（大图、全屏查看用；**别在主线程对一堆图调**）。 */
    Bitmap loadThumb(String file) {
        try {
            byte[] b = ConvStore.readImage(file);
            if (b == null) return null;
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, probe);
            int sample = 1;
            while (probe.outWidth / (sample * 2) >= 240) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(b, 0, b.length, o);
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 内部部件

    private LinearLayout row(boolean mine, View stack) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);          // 气泡间距 10dp（设计稿口径）
        row.setLayoutParams(rlp);
        if (mine) {
            row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(stack, wrap());
        } else {
            row.addView(stack, wrap());
            row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        return row;
    }

    private LinearLayout stack(int gravity) {
        LinearLayout s = new LinearLayout(a);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setGravity(gravity);
        return s;
    }

    private LinearLayout card(boolean mine) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.bubbleBg(mine, dp(Theme.R_BUBBLE), dp(Theme.R_TAIL), dp(1)));
        card.setPadding(dp(13), dp(9), dp(13), dp(9));
        return card;
    }

    private TextView textView(CharSequence text, boolean mine) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(mine ? Theme.WHITE : Theme.TEXT);
        tv.setTextSize(Theme.T_BODY);
        tv.setLineSpacing(dp(3), 1f);
        tv.setTextIsSelectable(false);
        fitWidth(tv, text);
        return tv;
    }

    /**
     * 思考折叠条的**定型文案**（PRD §6.6）。
     *
     * <table>
     * <tr><td>想完了 + 有档位</td><td>{@code ▸ 思考 6.2 秒 · 自动}</td></tr>
     * <tr><td>想完了 + 老消息（没记档位）</td><td>{@code ▸ 思考 6.2 秒 · 点开看过程}</td></tr>
     * <tr><td>被按了停止</td><td>{@code ▸ 思考 4.8 秒 · 已停止}</td></tr>
     * <tr><td>只知道有思考、不知道多久</td><td>{@code ▸ 思考过程}</td></tr>
     * </table>
     *
     * <p>档位为"自动"时**只写「自动」**，不去猜服务端实际选了哪一档 ——
     * 那是服务端的事，界面假装知道就是在编（PRD §4 的最后一句话）。
     */
    static String thinkTitle(long thinkMs, String effort, boolean stopped, boolean hasReason) {
        String head = thinkMs > 0
            ? String.format(Locale.ROOT, "思考 %.1f 秒", thinkMs / 1000.0)
            : "思考过程";
        String tail = null;
        if (stopped) tail = "已停止";
        else if (effort != null && effort.length() > 0) tail = Caps.effortLabel(effort);
        else if (hasReason) tail = "点开看过程";
        return tail == null ? "▸ " + head : "▸ " + head + " · " + tail;
    }

    /** 「▸ 思考 6.2 秒 · 自动」—— 28dp 高、12sp、次要色的折叠条（点开看原文）。 */
    private TextView thinkBar(long thinkMs, String effort, final String reasoning,
                             boolean stopped) {
        boolean hasReason = reasoning != null && reasoning.length() > 0;
        TextView t = new TextView(a);
        t.setText(thinkTitle(thinkMs, effort, stopped, hasReason));
        t.setTextSize(Theme.T_SUB);
        t.setTextColor(Theme.TEXT_DIM);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(28));
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setBackground(Theme.pill(Theme.SURFACE_2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        if (hasReason) {
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { cb.onReasoning(reasoning); }
            });
        }
        return t;
    }

    /** 失败气泡下面那个「⟳ 重试」。 */
    private View retryRow(final long at) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, 0);
        ImageView icon = new ImageView(a);
        icon.setImageDrawable(Glyph.of(a, "refresh", 13, Theme.DANGER));
        r.addView(icon, new LinearLayout.LayoutParams(dp(13), dp(13)));
        TextView t = new TextView(a);
        t.setText("重试");
        t.setTextSize(Theme.T_SUB);
        t.setTextColor(Theme.DANGER);
        t.setPadding(dp(5), 0, 0, 0);
        r.addView(t);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cb.onRetry(at); }
        });
        return r;
    }

    /**
     * 气泡下面的图（**card 的兄弟**，不是它儿子 —— 见类注释第 2 条）。
     *
     * 一张 = 120×160 的单图；多张 = 96×96 的横向条（相册多选，最多 4 张）。
     */
    /** @return 真挂上去几张（0 = 没有图 / 全是不存在的路径） */
    private int addShots(LinearLayout stack, List<String> shots, final String body) {
        if (shots == null || shots.isEmpty()) return 0;
        int n = Math.min(shots.size(), 4);
        boolean single = n == 1;
        int w = single ? 120 : 96;
        int h = single ? 160 : 96;

        LinearLayout strip = new LinearLayout(a);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < n; i += 1) {
            final String file = shots.get(i);
            if (file == null || file.length() == 0) continue;
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable bg = Theme.round(Theme.SURFACE_2, dp(Theme.R_IMAGE));
            iv.setBackground(bg);
            iv.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), dp(h));
            if (i > 0) lp.leftMargin = dp(6);
            iv.setLayoutParams(lp);
            iv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { cb.onImageTap(file); }
            });
            iv.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) { cb.onImageLong(file, body); return true; }
            });
            attach(iv, file);
            strip.addView(iv);
        }
        if (strip.getChildCount() == 0) return 0;

        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(strip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        hs.setLayoutParams(lp);
        stack.addView(hs);
        return strip.getChildCount();
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
