package com.dsh.eye;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 迷你 SVG 光栅器：**只画模型画得出来的那种图**（框图 / 流程 / 坐标示意）。
 *
 * ## 为什么要自己写
 *
 * 项目是零依赖的（APK 314KB）。可选的三条路都不合适：
 *   - WebView：每张图 20~40MB 内存，在 `ListView` 气泡里嵌套滚动/长按选择全坏；
 *   - 引 SVG 库：+800KB~1.5MB，还要 AndroidX；
 *   - 让模型画 ASCII：已经试过了，`┌──┐` 在手机上看不清。
 *
 * 所以只认**一个子集**，够用就行：
 * ```
 * <svg viewBox>  <g transform="translate|scale">  <rect rx>  <circle>  <ellipse>
 * <line>  <polyline>  <polygon>  <path d="M L H V C Q Z">  <text>
 * 属性：fill / stroke / stroke-width / opacity / fill-opacity / stroke-opacity /
 *       font-size / text-anchor / font-weight / style="…"
 * ```
 * **不支持就整块回退成代码块**（返回 null）—— 画半张比不画更糟。
 *
 * ## 三个和"手机 + 深色主题"有关的决定
 *
 * 1. **铺浅底**：模型写的图默认是黑字（网页习惯），深色气泡上会看不见。
 *    统一铺 `#FFFFFF` + 12px 留白，任何主题下都清楚 —— 也像"纸上的示意图"。
 * 2. **按屏宽渲染**：宽度 = min(屏宽×0.8, 900)，高度按 viewBox 等比。
 * 3. **缓存**：流式回答每次增量都会重渲染，`LruCache` 按（源码 hash + 宽度）缓存，
 *    同一张图只光栅化一次。
 */
final class Svg {

    /** 单张源码上限：再大就不像示意图了，渲染也慢。 */
    static final int MAX_SRC = 20_000;
    /** 最多画多少个元素（防一个 5000 行的 path 把手机卡住）。 */
    private static final int MAX_ELEMS = 900;
    /** 高度上限（像素），超过就继续缩。 */
    private static final int MAX_H = 1_800;

    private Svg() { }

    // ---------------------------------------------------------------- 对外

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12) {
        protected int sizeOf(String k, Bitmap b) { return b == null ? 0 : b.getByteCount(); }
    };

    /** 这段文字像不像一张 SVG（Markdown 里 ```` ```svg ```` 的判断兜底）。 */
    static boolean looks(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.startsWith("<svg") || (t.startsWith("<?xml") && t.contains("<svg"));
    }

    /** 渲染成可塞进 TextView 的 Drawable；画不了返回 null（调用方回退成代码块）。 */
    static Drawable drawable(String src) {
        Bitmap b = bitmap(src);
        return b == null ? null : new Pic(b);
    }

    /** 渲染成 Bitmap（带缓存）；画不了返回 null。 */
    static Bitmap bitmap(String src) {
        if (src == null || src.length() == 0 || src.length() > MAX_SRC) return null;
        int w = targetWidth();
        String key = Integer.toHexString(src.hashCode()) + "@" + w;
        Bitmap hit = CACHE.get(key);
        if (hit != null) return hit;
        Bitmap b = render(src, w);
        if (b != null) CACHE.put(key, b);
        return b;
    }

    // ---------------------------------------------------------------- 从正文里抽图

    /** {@link #extract} 的产物：去掉图之后的正文 + 画好的图（按出现顺序）。 */
    static final class Parts {
        String text = "";
        final List<Bitmap> pics = new ArrayList<Bitmap>();
    }

    /**
     * 把正文里的 ```svg 段**抽出来画成图**，返回"剩下的文字 + 图"。
     *
     * ## 为什么不用 `ImageSpan`（踩过）
     *
     * 真机实测：`ImageSpan` + 自定义 `Drawable`（甚至 `BitmapDrawable`）在气泡里
     * **占位、行高都对，就是不画**。与其和 TextView 的绘制细节较劲，不如把图拿出来
     * 直接放进 `ImageView` —— 显示、缩放、长按都有了，也不影响正文排版。
     *
     * 画不出来的段落**原样留在正文里**（Markdown 会显示成代码块），不吞内容。
     */
    static Parts extract(String body) {
        Parts p = new Parts();
        if (body == null || body.length() == 0) return p;
        StringBuilder keep = new StringBuilder(body.length());
        String[] lines = body.split("\n", -1);
        boolean in = false;
        String lang = "";
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("```")) {
                if (!in) {
                    in = true;
                    lang = t.substring(3).trim().toLowerCase(java.util.Locale.ROOT);
                    cur.setLength(0);
                } else {
                    in = false;
                    String src = cur.toString().trim();
                    boolean svg = src.length() > 0 && looks(src)
                        && (lang.startsWith("svg") || lang.startsWith("xml") || lang.length() == 0);
                    Bitmap bmp = svg && p.pics.size() < 3 ? bitmap(src) : null;
                    if (bmp != null) {
                        p.pics.add(bmp);
                    } else {
                        keep.append("```").append(lang).append('\n')
                            .append(src).append("\n```\n");
                    }
                    lang = "";
                }
                continue;
            }
            if (in) cur.append(line).append('\n');
            else keep.append(line).append('\n');
        }
        if (in) {                                   // 流式中途：原样留着
            keep.append("```").append(lang).append('\n').append(cur).append("\n```\n");
        }
        p.text = keep.toString();
        return p;
    }

    /** 目标渲染宽度（像素）。没有 Context 时用系统度量。 */
    static int targetWidth() {
        try {
            int screen = android.content.res.Resources.getSystem()
                .getDisplayMetrics().widthPixels;
            int w = (int) (screen * 0.80f);
            return Math.max(320, Math.min(900, w));
        } catch (Throwable e) {
            return 640;
        }
    }

    // ---------------------------------------------------------------- 渲染

    /** 真正的光栅化。**任何异常都返回 null**（"认不出来就原样显示"，绝不吞内容）。 */
    static Bitmap render(String src, int targetW) {
        try {
            return render0(src, Math.max(200, targetW));
        } catch (Throwable e) {
            EyeLog.w("svg", "画不了：" + e + " | " + clip(src));
            return null;
        }
    }

    private static Bitmap render0(String src, int targetW)
            throws Exception {
        XmlPullParser p = android.util.Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(src));

        // ① 先找根节点，拿 viewBox（后续所有坐标都在这个坐标系里）
        float vbW = 0, vbH = 0;
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && "svg".equals(name(p))) {
                String box = attr(p, "viewBox");
                if (box != null) {
                    float[] v = nums(box);
                    if (v.length >= 4 && v[2] > 1 && v[3] > 1) {
                        vbW = v[2];
                        vbH = v[3];
                    }
                }
                if (vbW <= 0) {
                    vbW = px(attr(p, "width"), 640);
                    vbH = px(attr(p, "height"), 360);
                }
                break;
            }
            ev = p.next();
        }
        if (vbW <= 0 || vbH <= 0) throw new IllegalArgumentException("没有 viewBox");

        float scale = targetW / vbW;
        if (vbH * scale > MAX_H) scale = MAX_H / vbH;
        int pad = 12;
        int wPx = Math.round(vbW * scale) + pad * 2;
        int hPx = Math.round(vbH * scale) + pad * 2;

        Bitmap bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFFFFFFFF);                      // 浅底：深色主题下也看得清
        c.translate(pad, pad);
        c.scale(scale, scale);

        Painter painter = new Painter(c, vbW, vbH);
        painter.run(p, true);                          // 从 <svg> 里继续往下走
        EyeLog.w("svg", "画好 " + wPx + "x" + hPx + " elems=" + painter.elems
            + " vb=" + vbW + "x" + vbH + " scale=" + scale);
        return bmp;
    }

    /**
     * 一遍解析、一路画。
     *
     * 状态（样式 / 变换）用**栈**维护：`<g>` 进栈、`</g>` 出栈。
     * 不建 DOM —— 一次扫完就扔，内存只占一个 Bitmap。
     */
    private static final class Painter {
        final Canvas c;
        final float vbW, vbH;
        int elems;

        Painter(Canvas c, float vbW, float vbH) {
            this.c = c;
            this.vbW = vbW;
            this.vbH = vbH;
        }

        void run(XmlPullParser p, boolean skipRoot) throws Exception {
            List<St> stack = new ArrayList<St>();
            stack.add(new St());
            Matrix m = new Matrix();
            List<Matrix> ms = new ArrayList<Matrix>();
            ms.add(m);
            boolean first = skipRoot;
            int ev = p.next();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = name(p);
                    if (!first && "svg".equals(n)) {
                        // 嵌套 svg 不常见，当成 g 处理
                        push(stack, p, ms, true);
                    } else if ("g".equals(n)) {
                        push(stack, p, ms, true);
                    } else if ("defs".equals(n) || "style".equals(n) || "title".equals(n)
                        || "desc".equals(n) || "metadata".equals(n)) {
                        skip(p);
                    } else {
                        if (++elems > MAX_ELEMS) throw new IllegalStateException("元素太多");
                        draw(p, n, stack.get(stack.size() - 1),
                            ms.get(ms.size() - 1));
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = name(p);
                    if ("g".equals(n) || "svg".equals(n)) {
                        if (stack.size() > 1) stack.remove(stack.size() - 1);
                        if (ms.size() > 1) ms.remove(ms.size() - 1);
                    }
                }
                first = false;
                ev = p.next();
            }
        }

        void push(List<St> stack, XmlPullParser p, List<Matrix> ms, boolean group) {
            St cur = stack.get(stack.size() - 1);
            St s = new St(cur);
            s.read(p);
            stack.add(s);
            Matrix m = new Matrix(ms.get(ms.size() - 1));
            String tf = attr(p, "transform");
            if (tf != null) applyTransform(m, tf);
            ms.add(m);
        }

        void draw(XmlPullParser p, String tag, St inherited, Matrix m) throws Exception {
            /**
             * ⚠️ **图形自己的属性必须先读**（v2.8.4 修）。
             *
             * 之前只给 `<g>` 读了样式，图形（`<rect fill="#eef3fa">`）自己的 `fill` / `stroke`
             * 被完全忽略 —— 结果每个框都按默认的黑填充画，整张图黑成一片。
             * 这也是"图明明画出来了、界面上却是空的一块"的真凶：黑底黑字。
             */
            St st = new St(inherited);
            st.read(p);
            Paint fill = st.fill();
            Paint stroke = st.stroke();
            if ("rect".equals(tag)) {
                float x = num(attr(p, "x"), 0), y = num(attr(p, "y"), 0);
                float w = num(attr(p, "width"), 0), h = num(attr(p, "height"), 0);
                if (w <= 0 || h <= 0) return;
                float rx = num(attr(p, "rx"), 0);
                RectF r = new RectF(x, y, x + w, y + h);
                if (rx > 0) {
                    Path path = new Path();
                    path.addRoundRect(r, rx, rx, Path.Direction.CW);
                    path.transform(m);
                    paint(path, fill, stroke);
                } else {
                    r = mapRect(m, r);
                    if (fill != null) c.drawRect(r, fill);
                    if (stroke != null) c.drawRect(r, stroke);
                }
            } else if ("circle".equals(tag)) {
                float cx = num(attr(p, "cx"), 0), cy = num(attr(p, "cy"), 0);
                float r = num(attr(p, "r"), 0);
                if (r <= 0) return;
                Path path = new Path();
                path.addCircle(cx, cy, r, Path.Direction.CW);
                path.transform(m);
                paint(path, fill, stroke);
            } else if ("ellipse".equals(tag)) {
                float cx = num(attr(p, "cx"), 0), cy = num(attr(p, "cy"), 0);
                float rx = num(attr(p, "rx"), 0), ry = num(attr(p, "ry"), 0);
                if (rx <= 0 || ry <= 0) return;
                Path path = new Path();
                path.addOval(new RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW);
                path.transform(m);
                paint(path, fill, stroke);
            } else if ("line".equals(tag)) {
                float[] pts = {
                    num(attr(p, "x1"), 0), num(attr(p, "y1"), 0),
                    num(attr(p, "x2"), 0), num(attr(p, "y2"), 0)
                };
                m.mapPoints(pts);
                if (stroke != null) c.drawLine(pts[0], pts[1], pts[2], pts[3], stroke);
            } else if ("polyline".equals(tag) || "polygon".equals(tag)) {
                float[] v = nums(attr(p, "points"));
                if (v.length < 4) return;
                Path path = new Path();
                path.moveTo(v[0], v[1]);
                for (int i = 2; i + 1 < v.length; i += 2) path.lineTo(v[i], v[i + 1]);
                if ("polygon".equals(tag)) path.close();
                path.transform(m);
                paint(path, fill, stroke);
            } else if ("path".equals(tag)) {
                Path path = parsePath(attr(p, "d"));
                if (path == null) return;
                path.transform(m);
                paint(path, fill, stroke);
            } else if ("text".equals(tag)) {
                /**
                 * ⚠️ **先取坐标，再读正文**：`text(p)` 会把解析器推进到 `</text>`，
                 * 那之后 `p` 上挂的是结束标签，x/y 全是 null —— 所有字都会画在 (0,0) 上
                 * 叠成一坨（v2.8.4 之前就是这个症状）。
                 */
                float x = num(attr(p, "x"), 0), y = num(attr(p, "y"), 0);
                String anchor = attr(p, "text-anchor");
                String txt = text(p);
                if (txt == null || txt.trim().length() == 0) return;
                Paint tp = st.text();
                float[] xy = { x, y };
                m.mapPoints(xy);
                String a = anchor != null ? anchor : st.anchor;
                if ("middle".equalsIgnoreCase(a)) tp.setTextAlign(Paint.Align.CENTER);
                else if ("end".equalsIgnoreCase(a)) tp.setTextAlign(Paint.Align.RIGHT);
                c.drawText(txt, xy[0], xy[1], tp);
            }
            // 其它标签（image / use / marker …）静默跳过：画不了不如不画
        }

        void paint(Path path, Paint fill, Paint stroke) {
            if (fill != null) c.drawPath(path, fill);
            if (stroke != null) c.drawPath(path, stroke);
        }
    }

    /** 样式栈里的一层（`<g>` 会继承父层）。 */
    private static final class St {
        int fill = 0xFF222222;          // SVG 默认黑字
        int stroke = 0;                 // 0 = 不描边
        float strokeW = 1f;
        float fontSize = 14f;
        String anchor = "start";
        boolean bold;
        float alpha = 1f;               // group 级 opacity

        St() { }

        St(St o) {
            fill = o.fill;
            stroke = o.stroke;
            strokeW = o.strokeW;
            fontSize = o.fontSize;
            anchor = o.anchor;
            bold = o.bold;
            alpha = o.alpha;
        }

        /** 读这一层的属性（含 `style="fill:#fff;stroke:#333"` 这种内联 CSS）。 */
        void read(XmlPullParser p) {
            String style = attr(p, "style");
            if (style != null) {
                for (String kv : style.split(";")) {
                    int i = kv.indexOf(':');
                    if (i <= 0) continue;
                    apply(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
                }
            }
            String f = attr(p, "fill");
            if (f != null) fill = color(f, fill);
            String s = attr(p, "stroke");
            if (s != null) stroke = color(s, 0);
            String sw = attr(p, "stroke-width");
            if (sw != null) strokeW = num(sw, strokeW);
            String fs = attr(p, "font-size");
            if (fs != null) fontSize = num(fs, fontSize);
            String ta = attr(p, "text-anchor");
            if (ta != null) anchor = ta;
            String fw = attr(p, "font-weight");
            if (fw != null) bold = fw.contains("bold") || "600".equals(fw) || "700".equals(fw);
            String op = attr(p, "opacity");
            if (op != null) alpha *= Math.max(0, Math.min(1, num(op, 1)));
        }

        private void apply(String k, String v) {
            if ("fill".equals(k)) fill = color(v, fill);
            else if ("stroke".equals(k)) stroke = color(v, 0);
            else if ("stroke-width".equals(k)) strokeW = num(v, strokeW);
            else if ("font-size".equals(k)) fontSize = num(v, fontSize);
            else if ("text-anchor".equals(k)) anchor = v;
            else if ("font-weight".equals(k)) bold = v.contains("bold");
            else if ("opacity".equals(k)) alpha *= Math.max(0, Math.min(1, num(v, 1)));
        }

        Paint fill() {
            if (fill == 0) return null;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(mul(fill, alpha));
            return p;
        }

        Paint stroke() {
            if (stroke == 0 || strokeW <= 0) return null;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokeW);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(mul(stroke, alpha));
            return p;
        }

        Paint text() {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(mul(fill == 0 ? 0xFF222222 : fill, alpha));
            p.setTextSize(fontSize);
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            return p;
        }
    }

    // ---------------------------------------------------------------- path

    /**
     * `d` → Path。只认 M/m L/l H/h V/v C/c Q/q Z/z。
     *
     * 出现别的命令（A 圆弧、S/T 平滑）**返回 null** —— 宁可回退成代码块，
     * 也不要画一张缺了几段线的图（那比不画更误导）。
     */
    static Path parsePath(String d) {
        if (d == null || d.trim().length() == 0) return null;
        Path path = new Path();
        int i = 0, n = d.length();
        char cmd = 0;
        float cx = 0, cy = 0, sx = 0, sy = 0;
        boolean started = false;
        while (i < n) {
            char ch = d.charAt(i);
            if (Character.isLetter(ch)) {
                cmd = ch;
                i += 1;
                if (cmd == 'Z' || cmd == 'z') {
                    if (!started) return null;
                    path.close();
                    cx = sx;
                    cy = sy;
                    continue;
                }
            } else if (cmd == 0) {
                return null;
            }
            // 读这一条命令需要的数
            switch (cmd) {
                case 'M': case 'm': case 'L': case 'l': {
                    float[] v = readNums(d, i, 2);
                    if (v == null) return null;
                    i = (int) v[2];
                    float x = v[0], y = v[1];
                    if (cmd == 'm' && started) { x += cx; y += cy; }
                    if (cmd == 'M' || cmd == 'm') {
                        path.moveTo(x, y);
                        sx = x; sy = y;
                        started = true;
                        // 后续隐式坐标按 L 处理
                        cmd = (cmd == 'm') ? 'l' : 'L';
                    } else {
                        path.lineTo(x, y);
                    }
                    cx = x; cy = y;
                    break;
                }
                case 'H': case 'h': {
                    float[] v = readNums(d, i, 1);
                    if (v == null) return null;
                    i = (int) v[1];
                    float x = cmd == 'h' ? cx + v[0] : v[0];
                    path.lineTo(x, cy);
                    cx = x;
                    break;
                }
                case 'V': case 'v': {
                    float[] v = readNums(d, i, 1);
                    if (v == null) return null;
                    i = (int) v[1];
                    float y = cmd == 'v' ? cy + v[0] : v[0];
                    path.lineTo(cx, y);
                    cy = y;
                    break;
                }
                case 'C': case 'c': {
                    float[] v = readNums(d, i, 6);
                    if (v == null) return null;
                    i = (int) v[6];
                    float x1 = v[0], y1 = v[1], x2 = v[2], y2 = v[3], x = v[4], y = v[5];
                    if (cmd == 'c') {
                        x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy;
                    }
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    cx = x; cy = y;
                    break;
                }
                case 'Q': case 'q': {
                    float[] v = readNums(d, i, 4);
                    if (v == null) return null;
                    i = (int) v[4];
                    float x1 = v[0], y1 = v[1], x = v[2], y = v[3];
                    if (cmd == 'q') { x1 += cx; y1 += cy; x += cx; y += cy; }
                    path.quadTo(x1, y1, x, y);
                    cx = x; cy = y;
                    break;
                }
                default:
                    return null;
            }
        }
        return started ? path : null;
    }

    /** 从 `i` 开始读 `k` 个数；返回 `[..., 新下标]`。读不全返回 null。 */
    private static float[] readNums(String s, int i, int k) {
        float[] out = new float[k + 1];
        int n = s.length();
        for (int j = 0; j < k; j += 1) {
            while (i < n && (s.charAt(i) == ',' || Character.isWhitespace(s.charAt(i)))) i += 1;
            if (i >= n) return null;
            int st = i;
            if (s.charAt(i) == '-' || s.charAt(i) == '+') i += 1;
            boolean dot = false, exp = false;
            while (i < n) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) { i += 1; continue; }
                if (c == '.' && !dot && !exp) { dot = true; i += 1; continue; }
                if ((c == 'e' || c == 'E') && !exp) { exp = true; i += 1; continue; }
                if ((c == '-' || c == '+') && exp) { i += 1; continue; }
                break;
            }
            try {
                out[j] = Float.parseFloat(s.substring(st, i));
            } catch (Throwable e) {
                return null;
            }
        }
        out[k] = i;
        return out;
    }

    // ---------------------------------------------------------------- 小工具

    private static void applyTransform(Matrix m, String tf) {
        java.util.regex.Matcher mt = java.util.regex.Pattern
            .compile("(translate|scale|matrix)\\s*\\(([^)]*)\\)").matcher(tf);
        while (mt.find()) {
            String op = mt.group(1);
            float[] v = nums(mt.group(2));
            if ("translate".equals(op) && v.length >= 1) {
                m.preTranslate(v[0], v.length > 1 ? v[1] : 0);
            } else if ("scale".equals(op) && v.length >= 1) {
                m.preScale(v[0], v.length > 1 ? v[1] : v[0]);
            } else if ("matrix".equals(op) && v.length >= 6) {
                Matrix mm = new Matrix();
                mm.setValues(new float[] { v[0], v[2], v[4], v[1], v[3], v[5], 0, 0, 1 });
                m.preConcat(mm);
            }
        }
    }

    private static RectF mapRect(Matrix m, RectF r) {
        float[] p = { r.left, r.top, r.right, r.bottom };
        m.mapPoints(p);
        return new RectF(Math.min(p[0], p[2]), Math.min(p[1], p[3]),
            Math.max(p[0], p[2]), Math.max(p[1], p[3]));
    }

    private static String name(XmlPullParser p) {
        String n = p.getName();
        if (n == null) return "";
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    private static String attr(XmlPullParser p, String key) {
        String v = p.getAttributeValue(null, key);
        if (v != null) return v;
        // 带前缀的属性（`xlink:href` 之类）按后缀再试一次
        for (int i = 0; i < p.getAttributeCount(); i += 1) {
            String n = p.getAttributeName(i);
            if (n != null && n.endsWith(key)) return p.getAttributeValue(i);
        }
        return null;
    }

    private static float num(String s, float def) {
        if (s == null) return def;
        try {
            String t = s.trim();
            int i = 0;
            while (i < t.length() && (Character.isDigit(t.charAt(i)) || "-+.".indexOf(t.charAt(i)) >= 0)) i += 1;
            return Float.parseFloat(t.substring(0, Math.max(1, i)));
        } catch (Throwable e) {
            return def;
        }
    }

    /** `640` / `100%` / `640px` → px（百分比按 640 折算）。 */
    private static float px(String s, float def) {
        if (s == null) return def;
        if (s.endsWith("%")) return def;
        return num(s, def);
    }

    private static float[] nums(String s) {
        if (s == null) return new float[0];
        List<Float> out = new ArrayList<Float>();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").matcher(s);
        while (m.find()) {
            try { out.add(Float.parseFloat(m.group())); } catch (Throwable ignored) { }
        }
        float[] a = new float[out.size()];
        for (int i = 0; i < a.length; i += 1) a[i] = out.get(i);
        return a;
    }

    /** 颜色：`none` / `#rgb` / `#rrggbb` / `rgb(r,g,b)` / 十来个常用名。 */
    static int color(String s, int def) {
        if (s == null) return def;
        String v = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.length() == 0) return def;
        if ("none".equals(v) || "transparent".equals(v)) return 0;
        if (v.startsWith("#")) {
            try {
                String h = v.substring(1);
                if (h.length() == 3) {
                    int r = Integer.parseInt(h.substring(0, 1), 16) * 17;
                    int g = Integer.parseInt(h.substring(1, 2), 16) * 17;
                    int b = Integer.parseInt(h.substring(2, 3), 16) * 17;
                    return Color.rgb(r, g, b);
                }
                if (h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
                if (h.length() == 8) {
                    long x = Long.parseLong(h, 16);
                    return (int) x;
                }
            } catch (Throwable ignored) { }
            return def;
        }
        if (v.startsWith("rgb")) {
            float[] a = nums(v);
            if (a.length >= 3) return Color.rgb((int) a[0], (int) a[1], (int) a[2]);
            return def;
        }
        if ("black".equals(v)) return 0xFF000000;
        if ("white".equals(v)) return 0xFFFFFFFF;
        if ("red".equals(v)) return 0xFFE53935;
        if ("green".equals(v)) return 0xFF43A047;
        if ("blue".equals(v)) return 0xFF1E88E5;
        if ("gray".equals(v) || "grey".equals(v)) return 0xFF9E9E9E;
        if ("orange".equals(v)) return 0xFFFB8C00;
        if ("yellow".equals(v)) return 0xFFFDD835;
        if ("purple".equals(v)) return 0xFF8E24AA;
        if ("cyan".equals(v)) return 0xFF00ACC1;
        if ("transparent".equals(v)) return 0;
        return def;
    }

    private static int mul(int argb, float a) {
        if (a >= 0.999f) return argb;
        int al = (int) (((argb >>> 24) & 0xFF) * Math.max(0, a));
        return (al << 24) | (argb & 0x00FFFFFF);
    }

    private static void skip(XmlPullParser p) throws Exception {
        int depth = 0;
        while (true) {
            int e = p.next();
            if (e == XmlPullParser.END_DOCUMENT) return;
            if (e == XmlPullParser.START_TAG) depth += 1;
            else if (e == XmlPullParser.END_TAG) {
                if (depth == 0) return;
                depth -= 1;
            }
        }
    }

    /** `<text>` 的正文（含 `<tspan>` 里的字，拼成一行）。 */
    private static String text(XmlPullParser p) throws Exception {
        StringBuilder b = new StringBuilder();
        int depth = 0;
        while (true) {
            int e = p.next();
            if (e == XmlPullParser.END_DOCUMENT) break;
            if (e == XmlPullParser.TEXT) {
                String t = p.getText();
                if (t != null) b.append(t);
            } else if (e == XmlPullParser.START_TAG) {
                depth += 1;
            } else if (e == XmlPullParser.END_TAG) {
                if (depth == 0) break;
                depth -= 1;
            }
        }
        return b.toString().replace('\n', ' ').trim();
    }

    private static String clip(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }

    // ---------------------------------------------------------------- Drawable

    /** 把 Bitmap 当 Drawable 用（intrinsic = 像素尺寸，按 bounds 缩放）。 */
    static final class Pic extends Drawable {
        private final Bitmap bmp;
        private final Rect src = new Rect();

        Pic(Bitmap b) {
            bmp = b;
            src.set(0, 0, b.getWidth(), b.getHeight());
        }

        public void draw(Canvas c) {
            c.drawBitmap(bmp, src, getBounds(), null);
        }

        public void setAlpha(int alpha) { }
        public void setColorFilter(ColorFilter cf) { }
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        public int getIntrinsicWidth() { return bmp.getWidth(); }
        public int getIntrinsicHeight() { return bmp.getHeight(); }
    }
}
