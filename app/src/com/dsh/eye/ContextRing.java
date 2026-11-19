package com.dsh.eye;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 顶栏的「上下文用量」小圆环：一圈底 + 一段进度弧。
 *
 * <p>只画环不画数字：26dp 里塞字会糊；百分比放在副标题和点开的用量弹窗里。
 * 颜色分级：<65% 强调蓝，65~85% 橙，>=85% 红。
 */
final class ContextRing extends View {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float stroke;
    private final float size;

    private int used;
    private int budget = 1;

    ContextRing(Context c) {
        super(c);
        stroke = dp(2.4f);
        size = dp(26);
        bg.setStyle(Paint.Style.STROKE);
        bg.setStrokeWidth(stroke);
        bg.setColor(0x33FFFFFF);
        fg.setStyle(Paint.Style.STROKE);
        fg.setStrokeWidth(stroke);
        fg.setStrokeCap(Paint.Cap.ROUND);
        fg.setColor(Theme.ACCENT);
        setContentDescription("上下文用量");
    }

    void setUsage(int usedTokens, int budgetTokens) {
        used = Math.max(0, usedTokens);
        budget = Math.max(1, budgetTokens);
        int pct = Math.min(100, Math.round(used * 100f / budget));
        setContentDescription("上下文 " + pct + "%");
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = Math.round(size);
        setMeasuredDimension(resolveSize(s, w), resolveSize(s, h));
    }

    @Override
    protected void onDraw(Canvas c) {
        float pad = stroke / 2f + dp(0.6f);
        float r = Math.min(getWidth(), getHeight()) / 2f - pad;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        if (r <= 0) return;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(oval, 0, 360, false, bg);

        float ratio = Math.min(1f, used / (float) budget);
        int pct = Math.round(ratio * 100);
        fg.setColor(pct >= 85 ? Theme.DANGER : (pct >= 65 ? 0xFFFFB020 : Theme.ACCENT));
        if (ratio > 0) c.drawArc(oval, -90f, 360f * ratio, false, fg);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
