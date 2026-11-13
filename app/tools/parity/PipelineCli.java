package com.dsh.eye;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.util.Locale;

/**
 * 对拍用 CLI：读一个裸 rgb24 文件，跑 Pipeline，输出每张宫格的元数据。
 * 与 `dsh-see-now/src/pipeline.js` 的 `createPipeline` 逐张对照。**不进 APK。**
 *
 * 用法：PipelineCli <raw> <width> <height> <fps>
 */
public final class PipelineCli {
    public static void main(String[] args) throws Exception {
        String path = args[0];
        int w = Integer.parseInt(args[1]);
        int h = Integer.parseInt(args[2]);
        int fps = Integer.parseInt(args[3]);

        Pipeline.Opts o = new Pipeline.Opts();
        o.width = w;
        o.height = h;
        o.fps = fps;
        o.queueDepth = 0;          // 对拍不能丢图
        applyPreset(o, args.length > 4 ? args[4] : "default");
        // 第 6 个参数 = "norm" 时打开时间归一化（App 的口径）
        if (args.length > 5 && "norm".equals(args[5])) o.timeNormalizeFrames = true;

        Pipeline p = new Pipeline(o);
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
                double t = n / (double) fps;
                p.push(t, buf.clone());
                drain(p);
                n += 1;
            }
        }
        p.finish();
        drain(p);
        System.err.println("frames=" + n
            + " kept=" + p.stats().kept
            + " cuts=" + p.stats().cuts
            + " marks=" + p.stats().marks
            + " floors=" + p.stats().floors
            + " sheets=" + p.stats().sheets
            + " scoreSum=" + Math.round(p.stats().scoreSum)
            + " scoreAvg=" + String.format(Locale.ROOT, "%.3f", p.stats().scoreN == 0 ? 0 : p.stats().scoreSum / p.stats().scoreN));
    }

    private static void drain(Pipeline p) {
        Pipeline.Sheet s;
        while ((s = p.take()) != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("S\t").append(f(s.at)).append('\t').append(f(s.from)).append('\t').append(f(s.to))
              .append('\t').append(s.why).append('\t').append(s.frames.size()).append('\t');
            StringBuilder ts = new StringBuilder();
            StringBuilder tiers = new StringBuilder();
            StringBuilder vias = new StringBuilder();
            StringBuilder levels = new StringBuilder();
            for (int i = 0; i < s.frames.size(); i += 1) {
                Pipeline.Entry e = s.frames.get(i);
                if (i > 0) { ts.append(','); tiers.append(','); vias.append(','); levels.append(','); }
                ts.append(String.format(Locale.ROOT, "%.4f", Math.round(e.t * 100) / 100.0));   // JS 的 sheet.frames[].t 是 toFixed(2)
                tiers.append(s.tiers[i]);
                vias.append(e.via);
                levels.append(e.level);
            }
            sb.append(ts).append('\t').append(tiers).append('\t').append(vias).append('\t').append(levels);
            System.out.print(sb.toString() + "\n");
        }
    }

    /** 压测档位：把各个 flush 分支都压到。与 pipeline-js.mjs 里的同名 preset 一一对应。 */
    private static void applyPreset(Pipeline.Opts o, String name) {
        switch (name) {
            case "sensitive":
                o.cutAbs = 3; o.cutIsolatedAbs = 2; o.cutMinGap = 0.2; o.maxWaitSec = 3;
                break;
            case "dense":
                o.cutAbs = 5; o.maxFrames = 40; o.maxWaitSec = 2; o.flushGain = 0.005;
                break;
            case "weight":
                o.tierMode = "weight"; o.cutAbs = 4; o.maxWaitSec = 4; o.markBoost = 2;
                break;
            case "scene":
                o.maxFrames = 1; o.strategy = "single"; o.cutAbs = 7;
                break;
            case "gap":
                o.floorGapK = 2; o.floorMin = 2; o.floorMax = 20; o.silentFloorSec = 3;
                break;
            case "nob":
                // 压测：关掉假切点加成，看 density 的出图张数是否还对帧率敏感
                o.markBoost = 0; o.floorBoost = 0;
                break;
            case "curve":
                o.tierCurve = "perceptual"; o.maxWaitSec = 3; o.cutAbs = 5;
                break;
            default:
                break;
        }
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
