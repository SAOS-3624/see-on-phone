package com.dsh.eye;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * 版面渲染探针：**离线**把"这一窗帧 → 这张宫格"真画成 PNG，肉眼验收。
 *
 * 用法：`bash tools/parity/render.sh` → 图落在 `tools/parity/out/`。
 *
 * 为什么要有它：`stream.sh` 只打印 `tiers=[...]`，看到 `[6 8 8 2]` 也说不出
 * "到底好不好看"。用户那句"拼得和屎一样"是**视觉判断**，就得用眼睛验。
 * 这里用 AWT 复刻 `Sheet.compose` 的几何（同一套 `Packer.toRects` + 1px 缝 + 编号 + 灰洞），
 * 差别只在编码（那边 JPEG，这边 PNG，方便看）。
 *
 * 素材是合成的：三块不同颜色的场景 + 一个移动的方块 + 大字编号，
 * 这样"哪一格对应哪一帧"一眼能对上 —— 真机上做不到（画面一直在变）。
 */
public final class RenderProbe {

    private static final int SRC_W = 480;
    private static final int SRC_H = 640;
    private static final int FPS = CallCapture.FPS;

    static final File OUT = new File(System.getProperty("out", "tools/parity/out"));

    /** 一帧：480×640 的 JPEG + 240×320 的 rgb24（打分用）+ 时刻（秒）。 */
    static final class Shot {
        final byte[] jpeg;
        final byte[] rgb;
        final double t;
        Shot(byte[] jpeg, byte[] rgb, double t) { this.jpeg = jpeg; this.rgb = rgb; this.t = t; }
    }

    public static void main(String[] args) throws Exception {
        OUT.mkdirs();
        String lim = System.getProperty("limit");
        if (lim != null) CallSheet.MIN_TIER_LIMIT = Integer.parseInt(lim);
        List<Shot> still = still(10 * FPS);
        List<Shot> pan = pan(10 * FPS);
        List<Shot> cuts = cuts(10 * FPS);
        List<Shot> walk = walk(15 * FPS);
        List<Shot> frantic = frantic(15 * FPS);

        // 和 App 现在一致的基调（`Looker.opts()` + `Config` 默认值）
        //
        // ⚠️ 这里**没有"宫格张数"这一维**：几帧是 `checkFlush()` 判的（碎格 / 时间），
        //    `maxFrames:40` 只是保险。以前按 4/6/8 各画一遍，等于把这个被废掉的旋钮
        //    又请回来了 —— 三张图会一模一样。
        String[][] modes = {
            { "密度", "density", "standard", "rows" },        // ★ App 默认（see-now 原生默认）
            { "按变化", "weight", "standard", "rows" },       // 可选：按变化量给大小
            { "天幕", "density", "standard", "skyline" },     // 可选：天幕填洞（tuning.json 开）
        };
        for (String[] m : modes) {
            String tag = m[0] + (lim == null ? "" : "-L" + lim);
            draw("静止-" + tag, still, m[1], m[2], m[3]);
            draw("平移-" + tag, pan, m[1], m[2], m[3]);
            draw("硬切-" + tag, cuts, m[1], m[2], m[3]);
            draw("走动-" + tag, walk, m[1], m[2], m[3]);
            draw("碎切-" + tag, frantic, m[1], m[2], m[3]);
        }
        System.out.println("图在 " + OUT.getAbsolutePath());
    }

    // ------------------------------------------------------------------ 素材

    private static List<Shot> still(int n) throws Exception {
        List<Shot> out = new ArrayList<>();
        for (int k = 0; k < n; k += 1) out.add(shoot(scene(0, k, n), k));
        return out;
    }

    private static List<Shot> pan(int n) throws Exception {
        List<Shot> out = new ArrayList<>();
        for (int k = 0; k < n; k += 1) out.add(shoot(scene(1, k, n), k));
        return out;
    }

    /** 15 秒：平移 → 换场景 → 平移 → 换场景 → 站住不动（真人举着手机走路的样子）。 */
    static List<Shot> walkFor(int n) throws Exception { return walk(n); }

    private static List<Shot> walk(int n) throws Exception {
        List<Shot> out = new ArrayList<>();
        for (int k = 0; k < n; k += 1) {
            int kind;
            if (k < n * 0.2) kind = 1;
            else if (k < n * 0.55) kind = 2;
            else if (k < n * 0.75) kind = 1;
            else if (k < n * 0.85) kind = 3;
            else kind = 3;
            int local = (kind == 3 && k >= n * 0.85) ? 0 : k;   // 最后一段静止
            out.add(shoot(scene(kind, local, n), k));
        }
        return out;
    }

    /** 15 秒里每 2 秒换一个场景（切点很密的样子）。 */
    private static List<Shot> frantic(int n) throws Exception {
        List<Shot> out = new ArrayList<>();
        for (int k = 0; k < n; k += 1) {
            int phase = (k / 10) % 4;
            out.add(shoot(scene(phase == 0 ? 1 : phase, k, n), k));
        }
        return out;
    }

    private static List<Shot> cuts(int n) throws Exception {
        List<Shot> out = new ArrayList<>();
        for (int k = 0; k < n; k += 1) {
            int phase = k < n / 3 ? 0 : (k < 2 * n / 3 ? 1 : 2);
            out.add(shoot(scene(phase == 0 ? 1 : phase + 1, k, n), k));
        }
        return out;
    }

    /** ① 静止（只有轻微噪点）② 平移 ③ 换场景（暖色）④ 换场景（冷色）。 */
    private static BufferedImage scene(int kind, int k, int n) {
        BufferedImage img = new BufferedImage(SRC_W, SRC_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (kind == 1) {
            g.setPaint(new java.awt.GradientPaint(0, 0, new Color(38, 44, 58), 0, SRC_H, new Color(18, 20, 26)));
        } else if (kind == 2) {
            g.setPaint(new java.awt.GradientPaint(0, 0, new Color(120, 64, 30), 0, SRC_H, new Color(46, 22, 12)));
        } else {
            g.setPaint(new java.awt.GradientPaint(0, 0, new Color(24, 60, 90), 0, SRC_H, new Color(10, 22, 40)));
        }
        g.fillRect(0, 0, SRC_W, SRC_H);

        // 一个会动的方块：静止场景不动，平移场景匀速右移
        int x = (kind == 1) ? 160 : 40 + (k * 12) % (SRC_W - 160);
        g.setColor(kind == 2 ? new Color(255, 200, 90) : new Color(120, 220, 255));
        g.fillRoundRect(x, 220, 160, 160, 24, 24);

        // 场景编号（大字）+ 帧号（小字）
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 72));
        g.drawString(String.valueOf(kind), 40, 120);
        g.setFont(new Font("SansSerif", Font.PLAIN, 34));
        g.drawString("frame " + k + "  t=" + String.format(Locale.ROOT, "%.1f", k / (double) FPS), 40, 590);
        g.setColor(new Color(255, 255, 255, 40));
        g.setStroke(new BasicStroke(3f));
        for (int gy = 0; gy < SRC_H; gy += 80) g.drawLine(0, gy, SRC_W, gy);
        g.dispose();
        return img;
    }

    private static Shot shoot(BufferedImage img, int k) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", bos);
        // 240×320 rgb24：和 `Looker.toRgb24` 一样是"缩到打分分辨率"
        BufferedImage small = new BufferedImage(240, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, 240, 320, null);
        g.dispose();
        byte[] rgb = new byte[240 * 320 * 3];
        int i = 0;
        for (int y = 0; y < 320; y += 1) {
            for (int x = 0; x < 240; x += 1) {
                int p = small.getRGB(x, y);
                rgb[i] = (byte) ((p >> 16) & 0xff);
                rgb[i + 1] = (byte) ((p >> 8) & 0xff);
                rgb[i + 2] = (byte) (p & 0xff);
                i += 3;
            }
        }
        return new Shot(bos.toByteArray(), rgb, k / (double) FPS);
    }

    // ------------------------------------------------------------------ 出图

    private static void draw(String name, List<Shot> shots, String tierMode, String strategy,
                             String packMode) throws Exception {
        Pipeline.Opts o = new Pipeline.Opts();
        o.width = 240;
        o.height = 320;
        o.timeNormalizeFrames = true;
        o.cutAbs = 12;                 // pace=normal
        o.floorGapK = 2; o.floorMin = 2; o.floorMax = 20;
        o.tierMode = tierMode;
        o.strategy = strategy;
        o.packMode = packMode;
        CallStream cs = new CallStream();
        cs.open(o, 10, 1);       // 第 3 个参数 = wakeBatch：探针恒 1（一次一箱）
        long base = System.currentTimeMillis();
        for (Shot sh : shots) cs.push(base + Math.round(sh.t * 1000), sh.rgb, sh.jpeg);
        Pipeline.Sheet s = cs.take();
        if (s == null) s = cs.takeNow(base + Math.round(shots.get(shots.size() - 1).t * 1000), "probe");
        int fed = shots.size();
        if (s == null) {
            System.out.println("BAD  " + name + "：没有箱");
            return;
        }
        int cw = s.layout.canvasW;
        int ch = s.layout.canvasH;
        BufferedImage canvas = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(0x2A2A2E));
        g.fillRect(0, 0, cw, ch);      // 灰洞底色

        List<Packer.Rect> rects = Packer.toRects(s.layout.cells, cw, ch, 0);
        for (int i = 0; i < rects.size(); i += 1) {
            Packer.Rect r = rects.get(i);
            int w = Math.max(1, r.pw - 1);       // 1px 缝
            int h = Math.max(1, r.ph - 1);
            if (i < s.frames.size()) {
                BufferedImage f = ImageIO.read(new ByteArrayInputStream(s.frames.get(i).jpeg));
                if (f != null) drawCover(g, f, r.px, r.py, w, h);
            }
            // 编号
            if (i < s.frames.size() && !"mark".equals(r.via)) {
                int fs = Math.max(12, Math.min(w, h) / 6);
                g.setFont(new Font("SansSerif", Font.BOLD, fs));
                String num = String.valueOf(r.index);
                int tw = g.getFontMetrics().stringWidth(num);
                g.setColor(new Color(0, 0, 0, 160));
                g.fillRect(r.px + 2, r.py + 2, tw + 10, fs + 8);
                g.setColor(Color.WHITE);
                g.drawString(num, r.px + 7, r.py + fs + 4);
            }
        }
        g.dispose();
        File f = new File(OUT, name + ".png");
        ImageIO.write(canvas, "png", f);

        double fill = 0;
        for (Packer.Cell c : s.layout.cells) fill += c.w * c.h;
        StringBuilder ts = new StringBuilder();
        for (int t : s.tiers) ts.append(t).append(' ');
        StringBuilder vi = new StringBuilder();
        for (Pipeline.Entry e : s.frames) vi.append(e.via).append(' ');
        System.out.println(String.format(Locale.ROOT,
            "%-30s fed=%d cells=%d canvas=%dx%d tokens=%d fill=%.2f%n    tiers=[%s] via=[%s]",
            name, fed, s.frames.size(), cw, ch, s.layout.tokens(), fill,
            ts.toString().trim(), vi.toString().trim()));
    }

    /** cover：保持比例、居中裁切，和 `Sheet.coverSrc` 同一读法。 */
    private static void drawCover(Graphics2D g, BufferedImage src, int x, int y, int w, int h) {
        double sa = src.getWidth() / (double) src.getHeight();
        double da = w / (double) h;
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sa > da) {
            sw = (int) Math.round(sh * da);
            int sx = (src.getWidth() - sw) / 2;
            g.drawImage(src, x, y, x + w, y + h, sx, 0, sx + sw, sh, null);
        } else {
            sh = (int) Math.round(sw / da);
            int sy = (src.getHeight() - sh) / 2;
            g.drawImage(src, x, y, x + w, y + h, 0, sy, sw, sy + sh, null);
        }
    }
}
