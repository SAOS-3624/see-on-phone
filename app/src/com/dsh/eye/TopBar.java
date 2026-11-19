package com.dsh.eye;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 会话页顶部那条 56dp 的栏：`☰  会话名 ~~模型 · 思考 · 上下文~~  ▶ ⚙`
 *
 * 单独立出来是因为它有三个"活"的部分（会话名、第二行状态、三个入口），
 * 而 `MainActivity` 只需要在会话变了的时候推一次 `setTitle` / `setSubtitle`。
 *
 * 图标按钮是 40dp 的圆形 ripple（`Theme.rippleCircle`）：`?attr/selectableItemBackgroundBorderless`
 * 在 `Theme.Material` 上解析出来是方角灰块，在深色界面里像贴了一块补丁。
 */
final class TopBar extends LinearLayout {

    interface Cb {
        void onMenu();
        void onVideo();
        void onGear();
        void onSpeak();
        void onUsage();
    }

    private final TextView title;
    private final TextView subtitle;
    private android.widget.ImageView speakIcon;
    private ContextRing ring;
    private boolean speakOn = true;

    TopBar(Activity a, Cb cb) {
        super(a);
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        int sb = statusBarHeight(a);
        setPadding(dp(Theme.S2), sb, dp(Theme.S2), 0);
        setMinimumHeight(dp(56) + sb);

        addView(iconBtn(a, "menu", new Runnable() {
            public void run() { if (cb != null) cb.onMenu(); }
        }), new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(VERTICAL);
        col.setPadding(dp(Theme.S2), 0, dp(Theme.S2), 0);

        title = new TextView(a);
        title.setTextColor(Theme.TEXT);
        title.setTextSize(Theme.T_ROW);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setText("鱼眼");
        title.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (cb != null) cb.onMenu(); }
        });
        col.addView(title);

        subtitle = new TextView(a);
        subtitle.setTextColor(Theme.TEXT_DIM);
        subtitle.setTextSize(Theme.T_TINY);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle.setText("正在启动…");
        col.addView(subtitle);

        addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ring = new ContextRing(a);
        ring.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (cb != null) cb.onUsage(); }
        });
        addView(ring, new LinearLayout.LayoutParams(dp(26), dp(26)));

        addView(speakBtn(a, new Runnable() {
            public void run() { if (cb != null) cb.onSpeak(); }
        }), new LinearLayout.LayoutParams(dp(40), dp(40)));

        addView(iconBtn(a, "video", new Runnable() {
            public void run() { if (cb != null) cb.onVideo(); }
        }), new LinearLayout.LayoutParams(dp(40), dp(40)));

        addView(iconBtn(a, "gear", new Runnable() {
            public void run() { if (cb != null) cb.onGear(); }
        }), new LinearLayout.LayoutParams(dp(40), dp(40)));
    }

    void setTitle(String t) {
        title.setText(t == null || t.length() == 0 ? "鱼眼" : t);
    }

    void setSubtitle(String s) {
        subtitle.setText(s == null ? "" : s);
    }

    /** 上下文圆环：0..budget 的 token 占用。 */
    void setUsage(int used, int budget) {
        if (ring != null) ring.setUsage(used, budget);
    }

    /** 朗读开关：换图标（喇叭 / 静音），由 `MainActivity` 在点击后推过来。 */
    void setSpeak(boolean on) {
        speakOn = on;
        paintSpeak();
    }

    private void paintSpeak() {
        if (speakIcon == null) return;
        speakIcon.setImageDrawable(Glyph.of(getContext(),
            speakOn ? "sound" : "mute", 20, Theme.TEXT_DIM));
    }

    private View speakBtn(Activity a, final Runnable onClick) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(a);
        f.setBackground(Theme.rippleCircle(Theme.RIPPLE));
        speakIcon = new android.widget.ImageView(a);
        paintSpeak();
        android.widget.FrameLayout.LayoutParams lp =
            new android.widget.FrameLayout.LayoutParams(
                Math.round(20 * a.getResources().getDisplayMetrics().density),
                Math.round(20 * a.getResources().getDisplayMetrics().density));
        lp.gravity = android.view.Gravity.CENTER;
        f.addView(speakIcon, lp);
        f.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onClick.run(); }
        });
        Anim.press(f);
        return f;
    }

    /** 状态栏高度：沉浸式下要自己加 padding，不然内容会钻到状态栏/刘海底下。 */
    static int statusBarHeight(Activity a) {
        int id = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? a.getResources().getDimensionPixelSize(id)
            : Math.round(24 * a.getResources().getDisplayMetrics().density);
    }

    private static View iconBtn(Activity a, String glyph, final Runnable onClick) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(a);
        f.setBackground(Theme.rippleCircle(Theme.RIPPLE));
        android.widget.ImageView iv = new android.widget.ImageView(a);
        iv.setImageDrawable(Glyph.of(a, glyph, 20, Theme.TEXT_DIM));
        android.widget.FrameLayout.LayoutParams lp =
            new android.widget.FrameLayout.LayoutParams(
                Math.round(20 * a.getResources().getDisplayMetrics().density),
                Math.round(20 * a.getResources().getDisplayMetrics().density));
        lp.gravity = android.view.Gravity.CENTER;
        f.addView(iv, lp);
        f.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onClick.run(); }
        });
        Anim.press(f);
        return f;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
