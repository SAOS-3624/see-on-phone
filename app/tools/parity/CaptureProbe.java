package com.dsh.eye;

/**
 * 采样闸门探针：**离线**验 {@link CallCapture} 只做一件事 —— 15fps 进、5fps 出。
 *
 * 用法：`bash tools/parity/capture.sh`
 *
 * （窗口/滑窗那部分随"一窗一条管线"一起删了，见 `CallCapture` 的类注释。）
 */
public final class CaptureProbe {

    private static int failed;

    public static void main(String[] args) {
        CallCapture c = new CallCapture();

        // ① 15fps 的相机进来，只留 1/3（5fps）
        int kept = 0;
        for (long t = 0; t <= 8_000; t += 66) if (c.offer(t)) kept += 1;
        // 66ms 的相机帧撞 200ms 的闸门：3×66=198 差点，于是每 4 帧留 1 帧 ≈ 3.8fps
        eq("8 秒 15fps 下收下的帧数（3.8~5fps）", 31, kept, 3);

        // ② 同一毫秒重复喂：只收第一张
        CallCapture d = new CallCapture();
        check("重开后第一帧立刻收", d.offer(1_000));
        check("紧接着的同一帧被挡掉", !d.offer(1_010));
        check("够 200ms 就放行", d.offer(1_200));

        // ③ reset：下一次立刻收
        d.reset();
        check("reset 后第一帧立刻收", d.offer(1_220));

        System.out.println(failed == 0 ? "ALL OK" : (failed + " FAILED"));
        if (failed != 0) System.exit(1);
    }

    private static void eq(String what, int want, int got, int tol) {
        boolean ok = Math.abs(want - got) <= tol;
        check(what + "：期望 " + want + "±" + tol + "，实际 " + got, ok);
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            System.out.println("   ✓ " + what);
        } else {
            failed += 1;
            System.out.println("   ✗ " + what);
        }
    }
}
