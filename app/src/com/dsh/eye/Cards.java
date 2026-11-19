package com.dsh.eye;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 消息区里的两张**过程卡**：🔍 搜索卡 与 ⚙ Agent 步骤条。
 *
 * ## 它们回答的是同一个问题
 *
 * "它到底干了什么。" 官方 App 的辨识度有一半在这里：搜索不是黑盒、多步不是玄学。
 * 所以两张卡共用一套外壳（{@link #chrome}）与一套折叠规则，只在头部文案 /
 * 行的内容 / 有没有「停止」上不同。
 *
 * ## 三条规矩
 *
 * 1. **行只增不减**。流式过程中每来一条事件就追加一行，绝不预置空行、绝不回退 ——
 *    用户看到的进度必须单调，否则会怀疑"刚才那条是不是没了"。
 * 2. **进行中不可折叠**。`▾` 只在结束后出现：折叠了还怎么看进度（PRD §6.4）。
 * 3. **折叠偏好按会话记，写在**自己**的 SharedPreferences 文件里**（`eye_cards`）。
 *    不碰 `Config`（那是"我这个人的偏好"），也不碰会话文件（那是服务端的落盘地盘）。
 *    键里带 `convId`，所以"这个会话我收起过"不会传染到别的会话。
 *
 * ## 文案
 *
 * 全中文、句号结尾、无感叹号、不出现工具内部名（`look` / `files` 这种）。
 * 人话映射见 {@link #describe}。
 */
final class Cards {

    /** 折叠偏好用的独立文件（**不是** `Config` 的那个 `eye`）。 */
    static final String PREF = "eye_cards";

    private Cards() { }

    /** Agent 步骤条上那个「停止」。 */
    interface Cb {
        /** 用户按了停：编排层负责"下一步不发起"，这里只负责把按钮变成"停止中…"。 */
        void onStop();
    }

    // ---------------------------------------------------------------- 外壳与零件

    /** 卡片的公共外壳：圆角 + 1dp 描边 + 内边距（和 `Bubbles.card()` 同款观感）。 */
    private static LinearLayout chrome(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Theme.roundStroke(Theme.SURFACE, dp(a, Theme.R_CARD),
            Theme.LINE, dp(a, 1)));
        box.setPadding(dp(a, Theme.S3), dp(a, Theme.S2 + 2), dp(a, Theme.S3),
            dp(a, Theme.S2 + 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, Theme.S2);
        box.setLayoutParams(lp);
        return box;
    }

    /** 头部那一行：左文案（占满）+ 右侧那点东西（收起 / 停止）。 */
    private static LinearLayout headRow(Activity a, TextView left, View right) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(left, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) row.addView(right);
        return row;
    }

    /** 明细行（12sp、次要色、老一档的行距）。 */
    private static TextView detail(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text == null ? "" : text);
        t.setTextSize(Theme.T_SUB);
        t.setTextColor(Theme.TEXT_DIM);
        t.setLineSpacing(dp(a, 2), 1f);
        t.setPadding(0, dp(a, 4), 0, 0);
        return t;
    }

    /** `2.4s` / `12.3s`（一位小数，PRD 线框就是一位）。 */
    static String secs(long ms) {
        if (ms < 0) ms = 0;
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    private static SharedPreferences prefs(Activity a) {
        return a.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static boolean collapsedOf(Activity a, String key, boolean def) {
        if (key == null || key.length() == 0) return def;
        try {
            return prefs(a).getBoolean("c." + key, def);
        } catch (Throwable e) {
            return def;
        }
    }

    private static void rememberCollapsed(Activity a, String key, boolean v) {
        if (key == null || key.length() == 0) return;
        try {
            prefs(a).edit().putBoolean("c." + key, v).apply();
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- 搜索卡

    /**
     * 「🔍 搜索 3 步 · 2.4s」。
     *
     * ```
     * ┌────────────────────────────────────────┐
     * │ 🔍 搜索 · 3 步 · 2.4s         [收起 ▴] │
     * │   已搜「上海 今天 天气」                 │
     * │   已读 2 个页面 · 找到 4 个来源          │
     * └────────────────────────────────────────┘
     * ```
     */
    static final class SearchCard extends LinearLayout {

        private final Activity a;
        private final String key;
        private final TextView head;
        private final TextView tail;
        private final LinearLayout body;
        private final long t0 = System.currentTimeMillis();

        private long t1;
        private int rows;
        private boolean live = true;
        private boolean interrupted;
        private boolean collapsed;

        /**
         * @param convId 会话 id（折叠偏好按它记；null = 不记偏好，一直用默认）
         */
        SearchCard(Activity a, String convId) {
            super(a);
            this.a = a;
            this.key = convId == null ? "" : ("sc." + convId);
            setOrientation(VERTICAL);
            setBackground(Theme.roundStroke(Theme.SURFACE, dp(a, Theme.R_CARD),
                Theme.LINE, dp(a, 1)));
            setPadding(dp(a, Theme.S3), dp(a, Theme.S2 + 2), dp(a, Theme.S3),
                dp(a, Theme.S2 + 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(a, Theme.S2);
            setLayoutParams(lp);

            head = new TextView(a);
            head.setTextSize(Theme.T_SUB + 1);
            head.setTextColor(Theme.TEXT);
            head.setSingleLine(true);
            head.setText("🔍 搜索");

            tail = new TextView(a);
            tail.setTextSize(Theme.T_TINY);
            tail.setTextColor(Theme.TEXT_DIM);
            tail.setPadding(dp(a, Theme.S2), 0, 0, 0);
            tail.setVisibility(GONE);

            body = new LinearLayout(a);
            body.setOrientation(VERTICAL);
            body.setVisibility(GONE);

            addView(headRow(a, head, tail), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            collapsed = collapsedOf(a, key, false);
            View.OnClickListener toggle = new View.OnClickListener() {
                public void onClick(View v) { toggle(); }
            };
            head.setOnClickListener(toggle);
            tail.setOnClickListener(toggle);
            repaint();
        }

        /**
         * 追加一行明细。**只增不减**，每行淡入 150ms（PRD §6.4）。
         *
         * <p>空白行直接丢掉 —— 服务端偶尔给空 query，画一行空行会让卡片看起来在抽动。
         */
        void row(String line) {
            if (line == null || line.trim().length() == 0) return;
            rows += 1;
            TextView t = detail(a, line);
            body.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            body.setVisibility(VISIBLE);
            Anim.fadeInUp(t, 0);
            repaint();
        }

        /**
         * 收尾：从此可折叠，头部换耗时。
         *
         * @param interrupted 中途断了（网络/停止）→ 头部写「搜索中断」
         */
        void settle(boolean interrupted) {
            if (!live && t1 > 0) return;         // 已经收过尾：不重复改（迟到的事件不许回退状态）
            live = false;
            this.interrupted = interrupted;
            t1 = System.currentTimeMillis();
            repaint();
        }

        /** 折叠 / 展开（进行中点了没反应）。 */
        void toggle() {
            if (live) return;
            collapsed = !collapsed;
            rememberCollapsed(a, key, collapsed);
            repaint();
        }

        boolean collapsed() { return collapsed; }

        int rows() { return rows; }

        private void repaint() {
            if (live) {
                head.setText(rows > 0 ? "🔍 搜索 · " + rows + " 步 · 进行中" : "🔍 搜索 · 进行中");
                head.setTextColor(Theme.TEXT);
                tail.setVisibility(GONE);
                body.setVisibility(VISIBLE);
                return;
            }
            if (interrupted) {
                head.setText(rows > 0 ? "🔍 搜索 · 搜索中断（" + rows + " 步）" : "🔍 搜索 · 搜索中断");
                head.setTextColor(Theme.BUSY);
                tail.setVisibility(GONE);
                body.setVisibility(VISIBLE);      // 断了也要把已经发生的摆出来
                return;
            }
            long ms = t1 > 0 ? t1 - t0 : 0;
            head.setText(collapsed
                ? "🔍 搜索 " + rows + " 步 · " + secs(ms)
                : "🔍 搜索 · " + rows + " 步 · " + secs(ms));
            head.setTextColor(Theme.TEXT);
            tail.setText(collapsed ? "展开 ▾" : "收起 ▴");
            tail.setVisibility(VISIBLE);
            body.setVisibility(collapsed ? GONE : VISIBLE);
        }
    }

    // ---------------------------------------------------------------- Agent 步骤条

    /**
     * 「⚙ Agent · 第 2/8 步 · 进行中」+ 每步一行 + 「停止」。
     *
     * ```
     * ┌──────────────────────────────────────────┐
     * │ ⚙ Agent · 第 2/8 步 · 进行中      [停止] │
     * │ ✓ 1  搜索「杭州 明天 天气」      1.2s     │
     * │ ▸ 2  写入工作区文件              进行中   │
     * └──────────────────────────────────────────┘
     * ```
     */
    static final class Steps extends LinearLayout {

        /** 一步在屏幕上的三个零件。 */
        private static final class Row {
            LinearLayout view;
            TextView main;
            TextView ms;
            /** 这一步开始的时间（算每步耗时用；事件里带了 `at` 就用它）。 */
            long startAt;
        }

        private final Activity a;
        private final String key;
        private final int max;
        private final TextView head;
        private final TextView tail;
        private final TextView stop;
        private final LinearLayout body;
        private final List<Row> rows = new ArrayList<Row>();
        private final long t0 = System.currentTimeMillis();

        private long lastAt;
        private boolean live = true;
        private boolean collapsed;
        private String endReason;

        /**
         * @param max  步数上限（Agent 是 8；只用来写「第 2/8 步」）
         * @param convId 会话 id（折叠偏好按它记）
         * @param cb    「停止」的接收方（null = 不显示停止按钮，历史消息用）
         */
        Steps(Activity a, int max, String convId, final Cb cb) {
            super(a);
            this.a = a;
            this.max = max <= 0 ? 8 : max;
            this.key = convId == null ? "" : ("st." + convId);
            setOrientation(VERTICAL);
            setBackground(Theme.roundStroke(Theme.SURFACE, dp(a, Theme.R_CARD),
                Theme.LINE, dp(a, 1)));
            setPadding(dp(a, Theme.S3), dp(a, Theme.S2 + 2), dp(a, Theme.S3),
                dp(a, Theme.S2 + 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(a, Theme.S2);
            setLayoutParams(lp);

            head = new TextView(a);
            head.setTextSize(Theme.T_SUB + 1);
            head.setTextColor(Theme.TEXT);
            head.setSingleLine(true);

            tail = new TextView(a);
            tail.setTextSize(Theme.T_TINY);
            tail.setTextColor(Theme.TEXT_DIM);
            tail.setPadding(dp(a, Theme.S2), 0, 0, 0);
            tail.setVisibility(GONE);

            stop = new TextView(a);
            stop.setText("停止");
            stop.setTextSize(Theme.T_SUB);
            stop.setTextColor(Theme.DANGER);
            stop.setPadding(dp(a, Theme.S2), dp(a, 2), 0, dp(a, 2));
            if (cb == null) {
                stop.setVisibility(GONE);
            } else {
                stop.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        stop.setText("停止中…");
                        stop.setTextColor(Theme.TEXT_DIM);
                        stop.setEnabled(false);
                        cb.onStop();
                    }
                });
            }

            // 头部一行里同时要有「停止」和「收起」：停止在前（它是动作，收起是视图开关）
            LinearLayout right = new LinearLayout(a);
            right.setOrientation(HORIZONTAL);
            right.setGravity(Gravity.CENTER_VERTICAL);
            right.addView(stop);
            right.addView(tail);

            body = new LinearLayout(a);
            body.setOrientation(VERTICAL);

            addView(headRow(a, head, right), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            collapsed = collapsedOf(a, key, false);
            View.OnClickListener toggle = new View.OnClickListener() {
                public void onClick(View v) { toggle(); }
            };
            head.setOnClickListener(toggle);
            tail.setOnClickListener(toggle);
            repaint();
        }

        /**
         * 喂一条步骤事件（`call_started` / `call_completed` / `failed` 都走这里）。
         *
         * <p>同一个 `step` 序号来两次 = 更新同一行（先 `▸` 再 `✓`），**不新增行** ——
         * 否则一次工具调用会在屏幕上占两行，用户会以为它做了两件事。
         */
        void step(Proto.Step s) {
            if (s == null || !live) return;
            if (s.at > 0) lastAt = Math.max(lastAt, s.at);

            int idx = s.step < 0 ? rows.size() : s.step;
            if (idx > 64) idx = 64;                     // 防御：脏数据不许把列表撑成几千个 null
            while (rows.size() <= idx) rows.add(null);

            Row r = rows.get(idx);
            if (r == null) {
                r = newRow();
                r.startAt = s.at > 0 ? s.at : System.currentTimeMillis();
                rows.set(idx, r);
                body.addView(r.view, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                Anim.fadeInUp(r.view, 0);
            }

            boolean done = "call_completed".equals(s.type);
            boolean failed = "failed".equals(s.status);
            String mark = failed ? "✕" : (done ? "✓" : "▸");
            r.main.setText(mark + " " + (idx + 1) + "　" + describe(s));
            r.main.setTextColor(failed ? Theme.DANGER : (done ? Theme.TEXT_SOFT : Theme.TEXT));
            if (done || failed) {
                long at = s.at > 0 ? s.at : System.currentTimeMillis();
                r.ms.setText(secs(Math.max(0, at - r.startAt)));
            } else {
                r.ms.setText("进行中");
            }
            r.ms.setTextColor(failed ? Theme.DANGER : Theme.TEXT_DIM);
            repaint();
        }

        /** 已在屏幕上画了几步（`Reply.steps` 回放时用来去重）。 */
        int count() {
            int n = 0;
            for (int i = 0; i < rows.size(); i += 1) if (rows.get(i) != null) n += 1;
            return n;
        }

        /** 步数上限（界面判"是不是到顶停了"用它，别在别处再写死一个 8）。 */
        int max() { return max; }

        /**
         * 收尾。
         *
         * @param reason null = 正常完成（头部写「完成 · 4 步 · 12.3s」）；
         *               否则写这句话（例如「已停止（达最大步数 8）」）
         */
        void finish(String reason) {
            if (!live && reason == null) return;
            live = false;
            endReason = reason;
            stop.setVisibility(GONE);
            repaint();
        }

        void toggle() {
            if (live) return;
            collapsed = !collapsed;
            rememberCollapsed(a, key, collapsed);
            repaint();
        }

        private Row newRow() {
            Row r = new Row();
            r.view = new LinearLayout(a);
            r.view.setOrientation(HORIZONTAL);
            r.view.setGravity(Gravity.CENTER_VERTICAL);
            r.view.setPadding(0, dp(a, 4), 0, 0);

            r.main = new TextView(a);
            r.main.setTextSize(Theme.T_SUB + 1);
            r.main.setTextColor(Theme.TEXT);
            r.main.setSingleLine(true);
            r.main.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.view.addView(r.main, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            r.ms = new TextView(a);
            r.ms.setTextSize(Theme.T_TINY);
            r.ms.setTextColor(Theme.TEXT_DIM);
            r.ms.setPadding(dp(a, Theme.S2), 0, 0, 0);
            r.view.addView(r.ms);
            return r;
        }

        private void repaint() {
            if (live) {
                head.setText("⚙ Agent · 第 " + Math.max(1, count()) + "/" + max + " 步 · 进行中");
                head.setTextColor(Theme.TEXT);
                tail.setVisibility(GONE);
                body.setVisibility(VISIBLE);
                return;
            }
            long ms = lastAt > t0 ? lastAt - t0 : 0;
            if (endReason != null) {
                head.setText("⚙ Agent · " + endReason);
                head.setTextColor(Theme.BUSY);
            } else {
                head.setText(collapsed
                    ? "⚙ Agent · 完成 " + count() + " 步 · " + secs(ms)
                    : "⚙ Agent · 完成 · " + count() + " 步 · " + secs(ms));
                head.setTextColor(Theme.TEXT);
            }
            tail.setText(collapsed ? "展开 ▾" : "收起 ▴");
            tail.setVisibility(VISIBLE);
            body.setVisibility(collapsed ? GONE : VISIBLE);
        }
    }

    // ---------------------------------------------------------------- 人话 / 历史卡

    /**
     * 一步的**人话描述**。**绝不把工具内部名露给用户**（PRD P0-8 验收第 1 条）。
     *
     * <p>判据优先用 `action`（服务端给的 `search` / `open_page` / `find_in_page` /
     * `read_link`），没有再用 `callType`。
     */
    static String describe(Proto.Step s) {
        if (s == null) return "动手";
        String act = s.action == null ? "" : s.action.trim();
        String q = s.query == null ? "" : s.query.trim();
        String url = s.url == null ? "" : s.url.trim();

        if ("search".equals(act) || "deep_search".equals(act)) {
            return q.length() == 0 ? "搜索" : "搜索「" + q + "」";
        }
        if ("image_search".equals(act) || "deep_image_search".equals(act)) {
            return "按图搜";
        }
        if ("read_link".equals(act)) {
            String d = domain(url);
            return d.length() == 0 ? "读链接" : "读链接 " + d;
        }
        if ("open_page".equals(act)) {
            String d = domain(url);
            return d.length() == 0 ? "打开页面" : "打开 " + d;
        }
        if ("find_in_page".equals(act)) return "在页面里找答案";

        String type = s.callType == null ? "" : s.callType.trim();
        if ("deepseek_web_search".equals(type)) {
            return q.length() == 0 ? "搜索" : "搜索「" + q + "」";
        }
        if ("deepseek_read_url".equals(type)) {
            String d = domain(url);
            return d.length() == 0 ? "读链接" : "读链接 " + d;
        }
        // 混合模式（Agent + 联网）：搜索是**自定义 function**，`action` 可能不填
        if ("web_search".equals(type)) {
            return q.length() == 0 ? "联网搜索" : "联网搜索「" + q + "」";
        }
        if ("read_url".equals(type)) {
            String d = domain(url);
            return d.length() == 0 ? "读网页" : "读网页 " + d;
        }
        return toolName(type);
    }

    /** 自定义工具 → 人话。认不出来就说"动手"，不露内部名。 */
    private static String toolName(String name) {
        if (name == null) return "动手";
        if ("look".equals(name)) return "看一眼画面";
        // 混合模式（联网 + Agent）里搜索是**自定义工具**，不是服务端那套
        if ("web_search".equals(name)) return "联网搜索";
        if ("read_url".equals(name)) return "读网页";
        if ("read_url".equals(name)) return "读网页";
        if ("files".equals(name)) return "翻工作区文件";
        if ("memory_read".equals(name)) return "翻自己的记忆";
        if ("memory_write".equals(name)) return "记一笔";
        if ("tune".equals(name)) return "调一下画面档位";
        return "动手";
    }

    /** `https://a.b.com/x?y=1` → `a.b.com`。 */
    static String domain(String url) {
        if (url == null) return "";
        String s = url.trim();
        int p = s.indexOf("://");
        if (p >= 0) s = s.substring(p + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        return s;
    }

    /**
     * 历史消息的卡片：**只读、折叠、不重算**。
     *
     * <p>落盘里只存了一行摘要（`Msg.steps`），所以历史卡片就是"那一行 + 一个图标"。
     * 想再看到逐行明细就得重新算 —— 那是重算，不是重建，PRD 明确不要。
     *
     * @param mode web → 🔍；agent → ⚙；其它（老消息 / 聊天）→ null（不出卡片）
     */
    static View history(Activity a, Caps.Mode mode, String summary) {
        if (mode != Caps.Mode.WEB && mode != Caps.Mode.AGENT) return null;
        String body = summary == null ? "" : summary.trim();
        if (body.length() == 0) return null;

        LinearLayout box = chrome(a);
        TextView head = new TextView(a);
        head.setTextSize(Theme.T_SUB + 1);
        head.setTextColor(Theme.TEXT_DIM);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.setText(mode == Caps.Mode.WEB ? "🔍 搜索 · " + body : "⚙ Agent · " + body);
        box.addView(head);
        return box;
    }

    private static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
