package com.dsh.eye;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 纯 Path 手绘的矢量图标集。
 *
 * ## 为什么不用图片 / SVG / 字体
 *
 * 上一版界面里的图标是**字符**：`☰` `⚙` `📹` `说` `图` `↑` `◉` `✕`。踩过两个坑：
 * 这台华为的字体里 `🎤` 是彩色 emoji，15sp 下糊成一团（像把叉子）；而 `☰`/`⚙` 这类
 * 符号在 `uiautomator dump` 里连节点都找不到，排障时看不见。
 * 更要紧的是**字号一改图标就跟着变**，行高全乱。
 *
 * 引 SVG 需要三方库（本项目零依赖），放 PNG 要为每个密度切图（图标改了要重切五份）。
 * 所以：**用 Path 在 24×24 的设计栅格上把图标画出来**，尺寸/颜色都是参数，
 * 描边宽度按缩放自动换算 —— 一个文件解决所有图标，任何 dp 都清晰。
 *
 * ## 用法
 *
 * ```java
 * ImageView v = new ImageView(ctx);
 * v.setImageDrawable(Glyph.of(ctx, "mic", 20, Theme.TEXT_DIM));
 * v.setImageDrawable(Glyph.ofGradient(ctx, "fisheye", 72, Theme.ACCENT, Theme.ACCENT_2));
 * ```
 *
 * 名字写错不会崩 —— 会画一个空图标（排障时立刻看得出来"这里少了一个图标"，
 * 而不是抛异常把整个界面拖死）。
 */
public final class Glyph {

    /** 设计栅格：所有坐标都按 24×24 写，绘制时整体缩放。 */
    private static final float GRID = 24f;
    /** 描边宽度（栅格单位）。1.9 在 20dp 下约 1.6dp，和 Material 的观感接近。 */
    private static final float STROKE = 1.9f;

    private Glyph() { }

    // ------------------------------------------------------------ 公开 API

    /** 单色图标。 */
    public static Drawable of(Context ctx, String name, int sizeDp, int color) {
        return new Icon(name, color, 0, px(ctx, sizeDp));
    }

    /** 渐变图标（「鱼眼」标记用，其它地方一般用不着）。 */
    public static Drawable ofGradient(Context ctx, String name, int sizeDp, int a, int b) {
        return new Icon(name, b, a, px(ctx, sizeDp));
    }

    /** 所有可用的名字（排障 / 单测用，别在业务代码里遍历它）。 */
    static final String[] NAMES = {
        "mic", "image", "send", "stop", "gear", "video", "menu", "back", "plus", "trash",
        "edit", "chevron", "chevronUp", "chevronRight", "eye", "check", "close", "copy",
        "refresh", "search", "fisheye", "sound", "folder", "wifi", "speed", "camera",
    };

    private static int px(Context c, int sizeDp) {
        float d = c == null ? 1f : c.getResources().getDisplayMetrics().density;
        return Math.max(1, Math.round(sizeDp * d));
    }

    // ------------------------------------------------------------ Drawable

    /** 一个图标 = 两条 Path（描边的 / 填充的）+ 两个 Paint。 */
    private static final class Icon extends Drawable {

        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path strokePath = new Path();
        private final Path fillPath = new Path();
        private final int sizePx;
        private final int color;
        private final int color2;
        private final boolean gradient;

        Icon(String name, int color, int color2, int sizePx) {
            this.sizePx = sizePx;
            this.color = color;
            this.color2 = color2;
            this.gradient = color2 != 0;
            this.fillPath.setFillType(Path.FillType.EVEN_ODD);

            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(color);

            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);

            build(name, strokePath, fillPath);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            int w = b.width() > 0 ? b.width() : sizePx;
            int h = b.height() > 0 ? b.height() : sizePx;
            if (w <= 0 || h <= 0) return;

            // 渐变在**栅格坐标**里定义：和 Path 同一套坐标，缩放时一起缩
            if (gradient) {
                Shader sh = new LinearGradient(0, 0, GRID, GRID, color2, color, Shader.TileMode.CLAMP);
                stroke.setShader(sh);
                fill.setShader(sh);
            }

            c.save();
            c.translate(b.left, b.top);
            c.scale(w / GRID, h / GRID);
            // 描边宽度用栅格单位写（1.9），这里是画布缩放带着它一起缩 ——
            // 所以 20dp 的图标描边是 1.6dp、40dp 的是 3.2dp，观感一致，不用分尺寸调参
            c.drawPath(fillPath, fill);
            c.drawPath(strokePath, stroke);
            c.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
            fill.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            stroke.setColorFilter(cf);
            fill.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }
    }

    // ------------------------------------------------------------ 图标定义

    /**
     * 把某个图标画进两条 Path。
     *
     * `s` = 描边（线），`f` = 填充（实心小块）。所有坐标都在 0..24 的栅格里。
     * `addArc` 而不是 `arcTo`：前者开新轮廓，后者会从当前点**连一条直线**过去，
     * 描边的图标上会多出一根莫名其妙的斜线（第一版就踩了）。
     */
    private static void build(String name, Path s, Path f) {
        if ("mic".equals(name)) {
            s.addRoundRect(new RectF(9f, 2.4f, 15f, 12.6f), 3f, 3f, Path.Direction.CW);
            s.addArc(new RectF(5.4f, 6.6f, 18.6f, 19.4f), 0f, 180f);
            s.moveTo(12f, 19.4f);
            s.lineTo(12f, 21.6f);
            s.moveTo(8.8f, 21.6f);
            s.lineTo(15.2f, 21.6f);

        } else if ("image".equals(name)) {
            s.addRoundRect(new RectF(3f, 4.4f, 21f, 19.6f), 3.2f, 3.2f, Path.Direction.CW);
            f.addCircle(8.1f, 9.3f, 1.6f, Path.Direction.CW);
            s.moveTo(4.4f, 16.6f);
            s.lineTo(9.2f, 12.2f);
            s.lineTo(12.8f, 15.2f);
            s.lineTo(15.6f, 12.6f);
            s.lineTo(19.6f, 17.2f);

        } else if ("send".equals(name)) {
            // 向上的箭头：发送
            s.moveTo(12f, 20f);
            s.lineTo(12f, 4.8f);
            s.moveTo(5.9f, 10.9f);
            s.lineTo(12f, 4.7f);
            s.lineTo(18.1f, 10.9f);

        } else if ("stop".equals(name)) {
            // 流式进行中，发送键变停止键
            f.addRoundRect(new RectF(6.8f, 6.8f, 17.2f, 17.2f), 2.4f, 2.4f, Path.Direction.CW);

        } else if ("gear".equals(name)) {
            // 齿轮：中心孔 + 轮圈 + 8 个齿（齿是短粗线，圆头，20dp 下读得出来）
            s.addCircle(12f, 12f, 3.4f, Path.Direction.CW);
            s.addCircle(12f, 12f, 6.6f, Path.Direction.CW);
            for (int i = 0; i < 8; i += 1) {
                double a = Math.PI * i / 4.0;
                float cs = (float) Math.cos(a);
                float sn = (float) Math.sin(a);
                s.moveTo(12f + 6.6f * cs, 12f + 6.6f * sn);
                s.lineTo(12f + 9.2f * cs, 12f + 9.2f * sn);
            }

        } else if ("video".equals(name)) {
            // 摄像机：机身 + 右侧三角镜头
            s.addRoundRect(new RectF(2.4f, 6.4f, 15.4f, 17.6f), 3.2f, 3.2f, Path.Direction.CW);
            s.moveTo(15.4f, 10.6f);
            s.lineTo(21.4f, 7.4f);
            s.lineTo(21.4f, 16.6f);
            s.lineTo(15.4f, 13.4f);
            s.close();

        } else if ("menu".equals(name)) {
            s.moveTo(4f, 7f);
            s.lineTo(20f, 7f);
            s.moveTo(4f, 12f);
            s.lineTo(20f, 12f);
            s.moveTo(4f, 17f);
            s.lineTo(20f, 17f);

        } else if ("back".equals(name)) {
            s.moveTo(15f, 4.6f);
            s.lineTo(7.8f, 12f);
            s.lineTo(15f, 19.4f);

        } else if ("plus".equals(name)) {
            s.moveTo(12f, 4.8f);
            s.lineTo(12f, 19.2f);
            s.moveTo(4.8f, 12f);
            s.lineTo(19.2f, 12f);

        } else if ("trash".equals(name)) {
            s.moveTo(4.4f, 6.8f);
            s.lineTo(19.6f, 6.8f);
            s.moveTo(9.4f, 6.8f);
            s.lineTo(9.4f, 4.4f);
            s.lineTo(14.6f, 4.4f);
            s.lineTo(14.6f, 6.8f);
            s.moveTo(6.6f, 6.8f);
            s.lineTo(7.5f, 19.8f);
            s.lineTo(16.5f, 19.8f);
            s.lineTo(17.4f, 6.8f);
            s.moveTo(10.4f, 10.4f);
            s.lineTo(10.4f, 16.6f);
            s.moveTo(13.6f, 10.4f);
            s.lineTo(13.6f, 16.6f);

        } else if ("edit".equals(name)) {
            s.moveTo(4.4f, 19.6f);
            s.lineTo(5.7f, 14.6f);
            s.lineTo(15.5f, 4.8f);
            s.lineTo(19.2f, 8.5f);
            s.lineTo(9.4f, 18.3f);
            s.close();
            s.moveTo(13.4f, 6.9f);
            s.lineTo(17.1f, 10.6f);

        } else if ("chevron".equals(name)) {
            s.moveTo(6f, 9.4f);
            s.lineTo(12f, 15.4f);
            s.lineTo(18f, 9.4f);

        } else if ("chevronUp".equals(name)) {
            s.moveTo(6f, 14.6f);
            s.lineTo(12f, 8.6f);
            s.lineTo(18f, 14.6f);

        } else if ("chevronRight".equals(name)) {
            s.moveTo(9.4f, 6f);
            s.lineTo(15.4f, 12f);
            s.lineTo(9.4f, 18f);

        } else if ("eye".equals(name)) {
            s.moveTo(2.4f, 12f);
            s.cubicTo(6.2f, 6.4f, 17.8f, 6.4f, 21.6f, 12f);
            s.cubicTo(17.8f, 17.6f, 6.2f, 17.6f, 2.4f, 12f);
            s.close();
            s.addCircle(12f, 12f, 2.7f, Path.Direction.CW);

        } else if ("check".equals(name)) {
            s.moveTo(4.6f, 12.6f);
            s.lineTo(9.6f, 17.6f);
            s.lineTo(19.4f, 6.6f);

        } else if ("close".equals(name)) {
            s.moveTo(5.6f, 5.6f);
            s.lineTo(18.4f, 18.4f);
            s.moveTo(18.4f, 5.6f);
            s.lineTo(5.6f, 18.4f);

        } else if ("copy".equals(name)) {
            s.addRoundRect(new RectF(8.4f, 8.4f, 20f, 20f), 2.8f, 2.8f, Path.Direction.CW);
            s.moveTo(15.6f, 4.4f);
            s.lineTo(6.4f, 4.4f);
            s.quadTo(4.4f, 4.4f, 4.4f, 6.4f);
            s.lineTo(4.4f, 15.6f);

        } else if ("refresh".equals(name)) {
            // 圆环 + 缺口 + 箭头：缺口在左上，箭头指着"往回绕"的方向（通用刷新符号）
            s.addArc(new RectF(4f, 4f, 20f, 20f), -80f, 300f);
            s.moveTo(9.2f, 1.6f);
            s.lineTo(12.2f, 4.4f);
            s.lineTo(9.2f, 7.2f);

        } else if ("search".equals(name)) {
            s.addCircle(10.6f, 10.6f, 6.6f, Path.Direction.CW);
            s.moveTo(15.4f, 15.4f);
            s.lineTo(20.2f, 20.2f);

        } else if ("sound".equals(name)) {
            // 喇叭 + 两道声波
            s.moveTo(4f, 9.4f);
            s.lineTo(7.4f, 9.4f);
            s.lineTo(11.6f, 5.4f);
            s.lineTo(11.6f, 18.6f);
            s.lineTo(7.4f, 14.6f);
            s.lineTo(4f, 14.6f);
            s.close();
            s.addArc(new RectF(10.4f, 7.2f, 17.2f, 16.8f), -62f, 124f);
            s.addArc(new RectF(9.4f, 3.6f, 21.4f, 20.4f), -58f, 116f);

        } else if ("folder".equals(name)) {
            // 文件夹：上沿有个台阶（不然就是个方块）
            s.moveTo(3f, 19.4f);
            s.lineTo(3f, 6.2f);
            s.lineTo(9.4f, 6.2f);
            s.lineTo(11.4f, 8.6f);
            s.lineTo(21f, 8.6f);
            s.lineTo(21f, 19.4f);
            s.close();

        } else if ("wifi".equals(name)) {
            // 三层弧 + 一个点
            s.addArc(new RectF(2.4f, 4.4f, 21.6f, 16.4f), 205f, 130f);
            s.addArc(new RectF(5.4f, 7.4f, 18.6f, 18.4f), 208f, 124f);
            s.addArc(new RectF(8.6f, 10.6f, 15.4f, 20.2f), 212f, 116f);
            f.addCircle(12f, 19.6f, 1.5f, Path.Direction.CW);

        } else if ("speed".equals(name)) {
            // 速度：仪表盘（一段弧）+ 指针
            s.addArc(new RectF(3.6f, 5.4f, 20.4f, 22.2f), 190f, 160f);
            s.moveTo(12f, 13.8f);
            s.lineTo(16.6f, 8.6f);
            f.addCircle(12f, 13.8f, 1.4f, Path.Direction.CW);

        } else if ("camera".equals(name)) {
            // 相机（拍照）：机身 + 顶上的凸起 + 镜头圈。"video" 是那个带三角的摄像机，
            // 两个图标在同一屏上出现（会话页顶栏 = 通话，输入栏 = 拍一张），不能画得像
            s.moveTo(3.2f, 8.4f);
            s.lineTo(7.4f, 8.4f);
            s.lineTo(9.2f, 5.8f);
            s.lineTo(14.8f, 5.8f);
            s.lineTo(16.6f, 8.4f);
            s.lineTo(20.8f, 8.4f);
            s.lineTo(20.8f, 19.6f);
            s.lineTo(3.2f, 19.6f);
            s.close();
            s.addCircle(12f, 13.9f, 3.5f, Path.Direction.CW);

        } else if ("fisheye".equals(name)) {
            /**
             * 「鱼眼」标记：三层同心 + 一道高光。
             *
             * 为什么是同心圆而不是画一条鱼：图标本体的构图就是一个鱼眼镜头的剖面
             * （外环 = 镜筒、内环 = 玻璃边缘、实心 = 瞳孔），和 App 图标同源；
             * 画鱼的话 24×24 里只能画出一团糊。
             */
            s.addCircle(12f, 12f, 10.2f, Path.Direction.CW);
            s.addCircle(12f, 12f, 6.4f, Path.Direction.CW);
            f.addCircle(12f, 12f, 2.7f, Path.Direction.CW);
            // 内圈高光：左上一小段弧 + 一个亮点（"玻璃反光"）
            s.addArc(new RectF(7.6f, 7.6f, 16.4f, 16.4f), 200f, 62f);
            f.addCircle(8.9f, 8.9f, 0.95f, Path.Direction.CW);
        }
    }
}
