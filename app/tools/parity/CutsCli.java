package com.dsh.eye;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.util.Locale;

/**
 * 对拍用 CLI：读一个裸 rgb24 文件（N 帧连排），逐帧跑 Scorer，输出每帧的分数。
 * 与 `dsh-see-now/src/cuts.js` 的 `createScorer` 逐帧对照。**不进 APK。**
 *
 * 用法：CutsCli <raw> <width> <height> <fps>
 */
public final class CutsCli {
    public static void main(String[] args) throws Exception {
        String path = args[0];
        int w = Integer.parseInt(args[1]);
        int h = Integer.parseInt(args[2]);
        int fps = Integer.parseInt(args[3]);

        Cuts.Scorer scorer = new Cuts.Scorer(
            w, h,
            "abs",      // scoreMode
            15, 2,      // mcTau, mcRadius
            4,          // gateMcRadius = cutMcRadius
            6,          // bgWindow = cutBgWindow
            24,         // bgStableRange
            15,         // bgTau
            true,       // bgModel = cutFutureDelay > 0
            24,         // bgRefWindow = cutFutureRefWindow
            12,         // futureMinScore = min(cutFutureMinScore 60, cutFutureBuildMinScore 12)
            true,       // scoreNormalize
            Cuts.BAR_WINDOW   // 电脑版口径：不按 fps 折算，跟 JS 逐帧一致
        );

        int frameSize = w * h * 3;
        byte[] buf = new byte[frameSize];
        int n = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(path), 1 << 20))) {
            while (true) {
                int read = 0;
                while (read < frameSize) {
                    int r = in.read(buf, read, frameSize - read);
                    if (r < 0) break;
                    read += r;
                }
                if (read < frameSize) break;
                Cuts.Score s = scorer.score(buf);
                StringBuilder sb = new StringBuilder();
                sb.append(n).append('\t');
                sb.append(f(s.score)).append('\t');
                sb.append(f(s.rawScore)).append('\t');
                sb.append(f(s.gain)).append('\t');
                sb.append(f(s.barFrac)).append('\t');
                sb.append(f(s.meanV)).append('\t');
                sb.append(f(s.mcScore)).append('\t');
                sb.append(f(s.mcBox)).append('\t');
                sb.append(f(s.mcFill)).append('\t');
                sb.append(f(s.mcSpanY)).append('\t');
                sb.append(f(s.activeY)).append('\t');
                sb.append(f(s.guardY)).append('\t');
                sb.append(f(s.bgScore)).append('\t');
                sb.append(s.bgStable).append('\t');
                sb.append(s.bgChanged);
                System.out.print(sb.toString() + "\n");
                n += 1;
            }
        }
        System.err.println("frames=" + n + " fps=" + fps);
    }

    private static String f(Double v) {
        if (v == null || v.isNaN()) return "null";
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String f(double v) {
        if (Double.isNaN(v)) return "null";
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
