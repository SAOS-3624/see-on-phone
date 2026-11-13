package com.dsh.eye;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 输入框上面那条「● 正在看 3 帧…」胶囊。
 *
 * 它替代了上一版的两个东西：一条贴在消息区里的灰字状态（`◌ 正在看一眼…`）
 * **和**一条插进消息流的"临时气泡"。两者都在抢注意力，而且模型连着调两个工具时
 * 屏幕上会同时出现三行"正在…"。现在只有一条，位置固定在输入框正上方 ——
 * **"它在干活"和"它在说话"从此分得开**。
 *
 * 呼吸点是刻意的：静止的圆点在深色界面上看起来像卡死了。
 */
final class ToolChip extends LinearLayout {

    private final View dot;
    private final TextView text;

    ToolChip(Activity a) {
        super(a);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setPadding(0, dp(a, Theme.S1), 0, dp(a, Theme.S1));
        setVisibility(GONE);

        LinearLayout chip = new LinearLayout(a);
        chip.setOrientation(HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(Theme.pillStroke(Theme.SURFACE_2, Theme.LINE, dp(a, 1)));
        chip.setPadding(dp(a, 11), dp(a, 6), dp(a, 13), dp(a, 6));

        dot = new View(a);
        dot.setBackground(Theme.circle(Theme.ACCENT));
        LinearLayout.LayoutParams dlp =
            new LinearLayout.LayoutParams(dp(a, 7), dp(a, 7));
        dlp.rightMargin = dp(a, 7);
        chip.addView(dot, dlp);

        text = new TextView(a);
        text.setTextSize(Theme.T_SUB);
        text.setTextColor(Theme.TEXT);
        text.setSingleLine(true);
        chip.addView(text);

        addView(chip, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 有活动：淡入 + 开始呼吸。文案可以一直换，不会重新淡入。 */
    void show(String line) {
        text.setText(line == null ? "" : line);
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            Anim.fadeInUp(this, 0);
        }
        Anim.pulse(dot);
    }

    /**
     * 结束了：写一句收尾的话再淡出。
     *
     * 只在"屏幕上还是我这句话"时才淡出 —— 1.2 秒内可能已经有新的工具在跑，
     * 那股收尾的淡出不能把新的一条也关掉。
     */
    void hide(final String line) {
        if (getVisibility() != VISIBLE) return;
        if (line != null && line.length() > 0) {
            final String was = text.getText().toString();
            text.setText(line);
            postDelayed(new Runnable() {
                public void run() {
                    if (line.contentEquals(text.getText())) Anim.fadeOut(ToolChip.this, 0);
                    else if (was.length() == 0) Anim.fadeOut(ToolChip.this, 0);
                }
            }, 1200);
        } else {
            Anim.fadeOut(this, 0);
        }
        Anim.stopPulse(dot);
    }

    private static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
