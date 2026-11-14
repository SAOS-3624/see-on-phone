package com.dsh.eye;

/**
 * 通话页的**采样闸门**：相机 15fps 进来，只放 {@link #FPS} 帧/秒进管线。
 *
 * ## 它现在只干这一件事
 *
 * 以前它还是个"最近 N 秒的滑动窗口"（`ArrayDeque` 存 100 张 JPEG ≈ 5MB），
 * 因为那时是"每隔几秒把整窗重喂一遍新管线"。现在管线是**一条连续的**
 * （见 {@link CallStream}），帧一进来就直接 `push` 进去，窗口缓冲纯属多余 ——
 * 留着只会让人以为"还有一份窗口状态"，所以整块删了。
 *
 * 为什么是 5fps 而不是相机的 15fps：15 秒 = 225 张 JPEG，打分要 1.5 秒；
 * 5fps 下切点判据照样成立（差分是"相邻采样帧"的差，采样越稀差值越大，反而更好判）。
 * `Pipeline.Opts.fps` 必须跟着设成 {@link #FPS}。
 *
 * 纯 Java，不碰 Android（离线探针脚本（`tools/parity/capture.sh`，不在发布包里）在电脑上验节奏）。
 */
final class CallCapture {

    /** 采样帧率。**改它必须同时确认 `Pipeline.Opts.fps` 拿到了它**（见 `CallStream.open`）。 */
    static final int FPS = 5;

    /** 相机预热期：这之前收到的帧一律不要（reopen 后的黑帧/曝光未完成帧）。 */
    static final long WARMUP_MS = 1_200;

    private long lastAt = -1;

    /**
     * 这一帧要不要收。
     *
     * @param now 采集时刻（毫秒，单调递增的墙钟）
     * @return true = 距上一帧够 {@code 1000/FPS} 毫秒，收
     */
    synchronized boolean offer(long now) {
        if (lastAt >= 0 && now - lastAt < 1000L / FPS) return false;
        lastAt = now;
        return true;
    }

    /** 通话页关闭/重开：下一次 offer 立刻收。 */
    synchronized void reset() {
        lastAt = -1;
    }
}
