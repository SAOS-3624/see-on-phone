package com.dsh.eye;

import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 动效小工具。**只用 framework 的 `android.animation`** —— 不引 Spring / Lottie。
 *
 * 这一版界面之所以"活"，靠的不是复杂动画，而是三件事：
 *  1. 新消息**淡入并轻轻上移**（{@link #fadeInUp}）—— 一眼看得出"这条是刚到的"；
 *  2. 按下去有反馈（{@link #press}）—— 手指不知道有没有点中的那种界面最难受；
 *  3. 「正在看」那个点**呼吸**（{@link #pulse}）—— 静止的状态点看起来像卡死了。
 *
 * 时长全部压在 90~260ms：超过 300ms 的动画在这个尺寸的界面上就是"慢"，
 * 用户会开始重复点。
 */
public final class Anim {

    /** 存脉冲动画的 key。必须 &gt;= 0x02000000（framework 会校验，见 View.setTag(int,Object)）。 */
    private static final int KEY_PULSE = 0x7f0f0001;

    private static final TimeInterpolator EASE = new DecelerateInterpolator(1.6f);

    private Anim() { }

    private static int dp(View v, int d) {
        float s = v.getResources().getDisplayMetrics().density;
        return Math.round(d * s);
    }

    /**
     * 从下方 12dp 淡入上浮。用于新气泡、空态、工具胶囊出现。
     *
     * @param delayMs 错开用（同一屏多个元素依次出现），0 = 立刻
     */
    public static void fadeInUp(View v, long delayMs) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(v, 12));
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(240)
            .setInterpolator(EASE)
            .start();
    }

    /** 淡出（不动位置）。结束后把 view 设成 GONE —— 由调用方在动画里决定，避免闪一下。 */
    public static void fadeOut(final View v, long delayMs) {
        if (v == null) return;
        v.animate()
            .alpha(0f)
            .setStartDelay(delayMs)
            .setDuration(180)
            .setInterpolator(EASE)
            .withEndAction(new Runnable() {
                public void run() {
                    if (v.getAlpha() < 0.05f) v.setVisibility(View.GONE);
                }
            })
            .start();
    }

    /**
     * 按下缩放反馈（0.92）。
     *
     * 用 `OnTouchListener` 返回 **false**：事件继续交给 view 自己的点击识别，
     * 所以不需要在每个点击处再套一层。重复调用不会叠加监听（同一个 listener 实例）。
     */
    public static void press(View v) {
        if (v == null) return;
        v.setOnTouchListener(PRESS);
    }

    private static final View.OnTouchListener PRESS = new View.OnTouchListener() {
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    break;
                default:
                    break;
            }
            return false;
        }
    };

    /**
     * 循环呼吸（alpha 1 → 0.3 → 1）。用于"正在看"的状态点。
     *
     * 幂等：重复调用会先取消上一个，不会越叠越快（叠 5 个 animation 的那种 bug 很难查）。
     */
    public static void pulse(View v) {
        if (v == null) return;
        stopPulse(v);
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.3f);
        a.setDuration(900);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setInterpolator(EASE);
        v.setTag(KEY_PULSE, a);
        a.start();
    }

    /** 停止呼吸并恢复正常不透明度。 */
    public static void stopPulse(View v) {
        if (v == null) return;
        Object old = v.getTag(KEY_PULSE);
        if (old instanceof ObjectAnimator) {
            ((ObjectAnimator) old).cancel();
            v.setTag(KEY_PULSE, null);
        }
        v.setAlpha(1f);
    }

    /**
     * 交叉淡入：`in` 出现、`out` 消失。
     *
     * 用于工具胶囊换文案（旧的一条淡出、新的一条淡入）—— 直接 `setText` 会闪，
     * 而"闪一下"在深色高对比界面上很刺眼。
     */
    public static void crossFade(final View in, final View out) {
        if (out != null && out != in) fadeOut(out, 0);
        if (in == null) return;
        in.setAlpha(0f);
        in.setVisibility(View.VISIBLE);
        in.animate().alpha(1f).setDuration(200).setInterpolator(EASE).start();
    }
}
