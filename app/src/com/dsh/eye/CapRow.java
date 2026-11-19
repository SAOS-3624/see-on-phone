package com.dsh.eye;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * 输入框正上方那一排**能力胶囊**：`🌐 联网   🧠 自动   ⚙ Agent`。
 *
 * ## 两个独立开关（早期版本是"三选一"，混合模式之后改了）
 *
 * 联网和 Agent **可以同时开** —— 那时 Agent 手里多一个联网搜索工具（后端 hybrid）。
 * 之前"必须互斥"的原因是服务端工具与自定义 function 工具同请求会 400；
 * 混合模式由后端用原子搜索接口包成自定义工具绕开了，所以界面不再需要替它挡。
 *
 * 请求形状仍然只有一个出口（`Caps.plan(..., webOn)`），界面只负责"所见即所发"。
 *
 * ## 唯一会改文案的胶囊是「🧠」
 *
 * 因为档位是它的状态：`自动 / 高 / 最高 / 关`。其余两个只改颜色。
 * 而文案一变宽度就变 —— 一行三个胶囊会互相挤，看起来像"点了一下整个界面抖了"。
 * 所以每个胶囊都有**固定最小宽度**，短文案只是居中，不再重排。
 *
 * ## 档位气泡
 *
 * 用框架的 `PopupWindow`（不是 Material 的 BottomSheet，也不是 `PopupMenu` ——
 * 沉浸式全屏下 `PopupMenu` 的位置算不准）。从思考胶囊**上方**弹出，避免盖住输入框；
 * 底部那句解释是 PRD §4 的落地，**必须有**（用户专门问过"它真的会自己选吗"）。
 *
 * ## 两根**独立**的轴（不是"三选一"）
 *
 * | 联网 | Agent | 实际模式 |
 * |---|---|---|
 * | 关 | 关 | 聊天 |
 * | 开 | 关 | 联网（服务端搜索 + 引用） |
 * | 关 | 开 | Agent（多步、可停） |
 * | 开 | 开 | **混合**：Agent 里多一个联网搜索工具 |
 *
 * 所以本类内部只有两个布尔（`webOn` / `agentOn`），**没有"互斥"这回事**了。
 * `mode()` 是给"请求形状"用的降级视图：两者都开时返回 `AGENT`（后端靠 `webOn`
 * 判断 hybrid，不靠 mode —— 一个枚举表达不了"两个都开"）。
 *
 * ## 档位与开关存哪
 *
 * 本类**不碰 `Config`**：它只是个视图，状态由界面推进来（{@link #setCaps} /
 * {@link #setMode} / {@link #setEffort}），用户改了再回调出去（{@link Cb}）。
 * 这样它能在没有 SharedPreferences 的地方被单独测，也不会出现"视图自己偷偷存盘"的双写。
 */
final class CapRow extends HorizontalScrollView {

    /**
     * 用户改了开关或档位。
     *
     * <p>回调出去的就是**最终状态**（两个布尔一起给），界面不需要再回读：
     * "点联网"和"点 Agent"改的是同一个二元组，分两次通知就会有一次是过期的。
     */
    interface Cb {
        /** 联网 / Agent 的开关变了（两者可以同时为 true）。 */
        void onCaps(boolean web, boolean agent);
        /** 档位变了（气泡里选一档，或双击在「自动 ↔ 关」之间切）。 */
        void onEffort(String effort);
    }

    /** 胶囊行高（PRD §6.1：36dp）。 */
    private static final int H = 36;
    /** 胶囊最小宽度：档位从「自动」变「关」时不重排（PRD §6.1 的"防止抖动"）。 */
    private static final int MIN_W = 84;
    /** 双击判定窗口。 */
    private static final long DOUBLE_MS = 320;

    private final Activity a;
    private final Cb cb;

    private final TextView web;
    private final TextView think;
    private final TextView agent;

    /** 联网开关（独立轴 ①）。 */
    private boolean webOn;
    /** Agent 开关（独立轴 ②）。两者都开 = 混合模式。 */
    private boolean agentOn;
    private String effort = Caps.EFF_AUTO;
    /** 这一轮正在发：点了不改行为，只给一句轻提示（PRD P0-1 验收第 5 条）。 */
    private boolean enabled = true;
    /** 被模型能力挡住的胶囊（{@link Caps#CAP_SEARCH} / {@link Caps#CAP_TOOLS}），0 = 没有。 */
    private int gated = 0;
    private String gatedHint;

    /** 上一次点思考胶囊的时刻（双击判定）。 */
    private long lastThinkTap;
    /** 延时弹出气泡的句柄（双击时把它撤掉）。 */
    private Runnable popupLater;

    CapRow(Activity a, Cb cb) {
        super(a);
        this.a = a;
        this.cb = cb;

        setHorizontalScrollBarEnabled(false);
        setFillViewport(false);
        setClipToPadding(false);
        setPadding(dp(Theme.S4), dp(Theme.S1), dp(Theme.S4), dp(Theme.S1));
        setBackgroundColor(Theme.NONE);
        setOverScrollMode(OVER_SCROLL_NEVER);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        web = chip("🌐 联网");
        think = chip("🧠 自动");
        agent = chip("⚙ Agent");

        row.addView(web, chipLp(0));
        row.addView(think, chipLp(Theme.S2));
        row.addView(agent, chipLp(Theme.S2));
        addView(row, new LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!usable(Caps.CAP_SEARCH, gatedHint)) return;
                // 独立开关：只翻自己，**不动 Agent**（两者都开 = 混合模式）
                webOn = !webOn;
                paint();
                fireCaps();
            }
        });

        agent.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!usable(Caps.CAP_TOOLS, gatedHint)) return;
                // 独立开关：只翻自己，**不动联网**（联网关着就是纯 Agent）
                agentOn = !agentOn;
                paint();
                fireCaps();
            }
        });

        /**
         * 思考胶囊：**单击开气泡，双击在「自动 ↔ 关」之间切**（PRD P0-4 的高频对比操作）。
         *
         * 单体点击必须延后 320ms 才能确定"不是双击"，所以第一击不当场弹 ——
         * 否则双击的第一下会先弹出一个气泡再被第二下关掉，看起来像闪了一下。
         */
        think.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!enabled) { hint("这一轮正在回答，下一条才生效。"); return; }
                long now = SystemClock.uptimeMillis();
                if (now - lastThinkTap < DOUBLE_MS && lastThinkTap > 0) {
                    lastThinkTap = 0;
                    cancelPopup();
                    setEffort(Caps.EFF_OFF.equals(effort) ? Caps.EFF_AUTO : Caps.EFF_OFF);
                    if (cb != null) cb.onEffort(effort);
                    return;
                }
                lastThinkTap = now;
                cancelPopup();
                popupLater = new Runnable() {
                    public void run() {
                        popupLater = null;
                        showEffortBubble(think);
                    }
                };
                think.postDelayed(popupLater, DOUBLE_MS);
            }
        });

        setCaps(false, false);
        setEffort(Caps.EFF_AUTO);
    }

    // ---------------------------------------------------------------- 对外状态

    /** 联网开着吗（混合模式 = 它和 {@link #isAgent()} 同时为 true）。 */
    boolean isWeb() { return webOn; }

    /** Agent 开着吗。 */
    boolean isAgent() { return agentOn; }

    /**
     * 当前模式（**请求形状**的降级视图）。
     *
     * <p>两者都开时返回 `AGENT`：一个枚举表达不了"两个都开"，真正的 hybrid 由
     * 后端看 `webOn` 决定（`Caps.plan(..., webOn)`）。
     */
    Caps.Mode mode() {
        if (agentOn) return Caps.Mode.AGENT;
        return webOn ? Caps.Mode.WEB : Caps.Mode.CHAT;
    }

    /** 当前档位（`auto` / `high` / `max` / `off`）。 */
    String effort() { return effort; }

    /** 两个开关一起设（初始化 / 从配置恢复用，**不发回调**）。 */
    void setCaps(boolean web, boolean agent) {
        webOn = web;
        agentOn = agent;
        paint();
    }

    /**
     * 按模式设开关（兼容老调用点）。
     *
     * <p>`WEB` → 联网开、Agent 关；`AGENT` → Agent 开、联网关；`CHAT` → 都关。
     * 注意它**表达不出混合模式** —— 要混合请用 {@link #setCaps(boolean, boolean)}。
     *
     * <p>不发回调：自己调（初始化、从配置恢复）不该反过来再存一次盘。
     */
    void setMode(Caps.Mode m) {
        Caps.Mode v = m == null ? Caps.Mode.CHAT : m;
        webOn = v == Caps.Mode.WEB;
        agentOn = v == Caps.Mode.AGENT;
        paint();
    }

    /** 设置档位（同样不发回调）。认不出来的值按「自动」。 */
    void setEffort(String e) {
        effort = Caps.normEffort(e);
        paint();
    }

    /**
     * 发送中：胶囊全部置灰，点了只给一句轻提示、不改状态。
     *
     * <p>⚠️ **故意不叫 `setEnabled`**：`CapRow extends HorizontalScrollView`，而
     * `View.setEnabled(boolean)` 是 public —— 同名同参会变成"覆盖"，一旦可见性写窄
     * 就直接编译失败；真覆盖了语义也不对（那会连带把手势/无障碍关掉，用户在等答案时
     * 连这排胶囊都滚不动了）。这里要的只是"别改这一轮的行为"。
     */
    void setChipEnabled(boolean sending) {
        this.enabled = sending;
        paint();
    }

    /**
     * 模型能力门禁。
     *
     * @param cap  {@link Caps#CAP_SEARCH} → 禁「联网」；{@link Caps#CAP_TOOLS} → 禁「Agent」
     * @param why  点它时说的话（null 就用 {@link Caps#degradeHint(int)}）
     */
    void setGate(int cap, String why) {
        gated = cap;
        gatedHint = why;
        paint();
    }

    /** 清掉门禁（切了模型之后调）。 */
    void clearGate() {
        gated = 0;
        gatedHint = null;
        paint();
    }

    // ---------------------------------------------------------------- 绘制

    private void paint() {
        boolean webDead = isGated(Caps.CAP_SEARCH);
        boolean agentDead = isGated(Caps.CAP_TOOLS);

        // 两个开关各画各的：可以同时亮（混合模式），没有任何"互相置灰"
        paintChip(web, webOn, false, webDead);
        paintChip(agent, agentOn, false, agentDead);

        think.setText("🧠 " + Caps.effortLabel(effort));
        boolean off = Caps.EFF_OFF.equals(effort);
        paintChip(think, !off, false, false);
    }

    /**
     * 一个胶囊的三种样子。
     *
     * @param on      开启：强调色 20% 填充 + 亮字；关闭：透明 + 1dp 描边 + 次要字色
     * @param marking 预留（"开启 + 小圆点"那一态；当前按 team-lead 口径不带点）
     * @param dead    不可用：整体压暗，点它只提示
     */
    private void paintChip(TextView t, boolean on, boolean marking, boolean dead) {
        if (on) {
            t.setBackground(Theme.pillStroke(fade(Theme.ACCENT, 0x33), fade(Theme.ACCENT, 0x66),
                dp(1)));
            t.setTextColor(dead ? Theme.TEXT_DIM : Theme.TEXT);
        } else {
            t.setBackground(Theme.pillStroke(Theme.NONE, Theme.LINE, dp(1)));
            t.setTextColor(Theme.TEXT_DIM);
        }
        t.setAlpha(dead ? 0.42f : (enabled ? 1f : 0.5f));
    }

    private TextView chip(String label) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextSize(Theme.T_SUB + 1);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        t.setMinWidth(dp(MIN_W));
        t.setPadding(dp(11), 0, dp(11), 0);
        Anim.press(t);
        return t;
    }

    private LinearLayout.LayoutParams chipLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(H));
        lp.leftMargin = dp(leftMargin);
        return lp;
    }

    private boolean usable(int cap, String why) {
        if (isGated(cap)) {
            hint(why == null || why.length() == 0 ? Caps.degradeHint(cap) : why);
            return false;
        }
        if (!enabled) {
            hint("这一轮正在回答，下一条才生效。");
            return false;
        }
        return true;
    }

    private boolean isGated(int cap) {
        return gated != 0 && (gated & cap) != 0;
    }

    /** 两个开关一起回调（界面按这对布尔存盘 + 刷闸门）。 */
    private void fireCaps() {
        if (cb != null) cb.onCaps(webOn, agentOn);
    }

    private void hint(String s) {
        try {
            android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- 档位气泡

    private void cancelPopup() {
        if (popupLater != null) {
            think.removeCallbacks(popupLater);
            popupLater = null;
        }
    }

    /**
     * 从思考胶囊**上方**弹出的四档选择。
     *
     * <p>顺序固定 `自动 / 高 / 最高 / 关`（和设置页一致，不按字母序 ——
     * 用户点这个是想"再想深一点"，顺序必须是"由浅入深"）。
     */
    private void showEffortBubble(View anchor) {
        final int w = dp(216);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(Theme.roundStroke(Theme.SURFACE_2, dp(14), Theme.LINE, dp(1)));
        col.setPadding(dp(6), dp(6), dp(6), dp(8));

        for (int i = 0; i < Caps.EFFORTS.length; i += 1) {
            final String e = Caps.EFFORTS[i];
            boolean on = e.equals(Caps.normEffort(effort));

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));

            TextView mark = new TextView(a);
            mark.setText(on ? "●" : "○");
            mark.setTextSize(Theme.T_SUB);
            mark.setTextColor(on ? Theme.ACCENT : Theme.TEXT_DIM);
            row.addView(mark);

            TextView name = new TextView(a);
            name.setText(Caps.EFFORT_LABEL[i]);
            name.setTextSize(Theme.T_ROW);
            name.setTextColor(on ? Theme.TEXT : Theme.TEXT_SOFT);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nlp.leftMargin = dp(Theme.S2);
            row.addView(name, nlp);

            if (Caps.EFF_AUTO.equals(e)) {
                TextView tag = new TextView(a);
                tag.setText("默认");
                tag.setTextSize(Theme.T_TINY);
                tag.setTextColor(Theme.TEXT_DIM);
                row.addView(tag);
            }

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    setEffort(e);
                    if (cb != null) cb.onEffort(effort);
                    dismiss(pw);
                }
            });
            Anim.press(row);
            col.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        View line = new View(a);
        line.setBackgroundColor(Theme.LINE);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        llp.leftMargin = dp(10);
        llp.rightMargin = dp(10);
        llp.topMargin = dp(4);
        col.addView(line, llp);

        TextView note = new TextView(a);
        note.setText("自动由服务端按问题难度路由，与官方 App 一致。");
        note.setTextSize(Theme.T_TINY);
        note.setTextColor(Theme.TEXT_DIM);
        note.setLineSpacing(dp(2), 1f);
        note.setPadding(dp(10), dp(8), dp(10), dp(2));
        col.addView(note, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pw = new PopupWindow(col, w, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setFocusable(true);
        pw.setClippingEnabled(true);

        // 先量出自己的高度，才能把它整个抬到胶囊上方（yoff 是相对锚点**底边**的）
        int h = ViewGroup.LayoutParams.WRAP_CONTENT;
        try {
            col.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            h = col.getMeasuredHeight();
        } catch (Throwable ignored) { }
        int yoff = -(anchor.getHeight() + (h > 0 ? h : dp(212)) + dp(Theme.S2));
        try {
            pw.showAsDropDown(anchor, 0, yoff);
        } catch (Throwable t) {
            try {
                pw.showAsDropDown(anchor, 0, -dp(240));
            } catch (Throwable ignored) { }
        }
    }

    /** 当前气泡（只有一个，弹新的之前先关旧的）。 */
    private PopupWindow pw;

    private void dismiss(PopupWindow p) {
        try {
            if (p != null && p.isShowing()) p.dismiss();
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- 小工具

    /** 同一个色相换一个透明度（**不新建配色**，只改 alpha）。 */
    private static int fade(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private int dp(float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
