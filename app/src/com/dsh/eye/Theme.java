package com.dsh.eye;

import android.graphics.drawable.GradientDrawable;

/**
 * 「鱼眼」的**唯一设计令牌来源**。
 *
 * ## 为什么要有这个文件
 *
 * 上一版每个 Activity 顶部都抄一份 `BG / CARD / FG / SUB / ACCENT`（4 个界面 × 5 个常量），
 * 值还各不相同（0xFF0B0F14 / 0xFF161C24 / 0xFFE6EDF3 …），于是"同一个 App"里出现了
 * 三种灰、两种蓝。改一次配色要在四个文件里搜字号字面色值。
 *
 * 从这一版起：**任何 Activity 里都不许再出现 `0xFF…` 字面量**，颜色/圆角/间距一律从这里取。
 * 好处不只是省字 —— 深色界面的对比度是算出来的，散落着写就没法整体调。
 *
 * ## 色板从哪来
 *
 * 取自 App 图标（`art/icon-source.jpg`）：深海蓝底 + 电光蓝环 + 一道紫。所以
 * `BG` 偏冷黑（0xFF0A0D14，不是纯黑 —— 纯黑在 OLED 上和卡片边缘会糊在一起），
 * 卡片是它的提亮一档，强调色是图标里那个环的颜色。
 *
 * ## 单位约定
 *
 * 所有尺寸常量（`S1..S6`、`R_*`）都是 **dp 数值**，用法是 `Theme.S4` → `dp(Theme.S4)`。
 * 但这几个便捷构造接收的是**像素**（调用方自己 `dp()` 过）—— 因为它们内部要算
 * 描边宽度和圆角半径，混着单位迟早出事，所以这里只认 px，签名里的参数名叫 `px`。
 */
public final class Theme {

    // ------------------------------------------------------------ 色板

    /** 页面底。比纯黑亮一点点，不然卡片描边会糊成一片。 */
    public static final int BG = 0xFF0A0D14;
    /** 卡片 / 助手气泡。 */
    public static final int SURFACE = 0xFF121826;
    /** 次级表面：输入框、胶囊 chip、预览条。 */
    public static final int SURFACE_2 = 0xFF1A2233;
    /** 1dp 描边。比 SURFACE 亮两档，深色界面里靠它分层。 */
    public static final int LINE = 0xFF243149;
    /** 正文。故意不用纯白：纯白在暗底上会"发光"，长时间看很累。 */
    public static final int TEXT = 0xFFE9EEF7;
    /** 次要文字：时间、副标题、hint。 */
    public static final int TEXT_DIM = 0xFF8695AE;
    /** 电光蓝（图标里那个环）。 */
    public static final int ACCENT = 0xFF5B9CFF;
    /** 紫。只用在渐变的另一端和「鱼眼」标记。 */
    public static final int ACCENT_2 = 0xFF9B7BFF;
    /** 删除 / 失败。 */
    public static final int DANGER = 0xFFFF6B6B;
    /** 成功 / 正在看。 */
    public static final int OK = 0xFF3DDC97;

    /** 渐变的两个端点（用户气泡、发送键、鱼眼标记都走它）。 */
    public static final int[] GRAD = { 0xFF5B9CFF, 0xFF8B5CF6 };

    // ------------------------------------------------------------ 半透明/特殊

    /** 纯白：只用在渐变底上的字和图标（SURFACE 上的正文一律用 {@link #TEXT}）。 */
    public static final int WHITE = 0xFFFFFFFF;
    /** 图标按钮的按压涟漪（白 20%）。 */
    public static final int RIPPLE = 0x33FFFFFF;
    /** 空（用于 `setBackground` 里"这一侧不要底色"的分支）。 */
    public static final int NONE = 0x00000000;
    /** 浮在画面上的玻璃卡片（通话页字幕，90% 不透明）。 */
    public static final int SURFACE_GLASS = 0xE6121826;
    /** 更透一档的玻璃（通话页顶栏胶囊）。 */
    public static final int SURFACE_GLASS_2 = 0xCC121826;
    /** 通话页输入框的玻璃底。 */
    public static final int INPUT_GLASS = 0xCC1A2233;
    /** 玻璃上的字（白 90%）。 */
    public static final int TEXT_GLASS = 0xCCE9EEF7;
    /** 图片上的圆形遮罩（✕ 按钮底）。 */
    public static final int SCRIM = 0x66000000;
    /** 更沉的遮罩（压在缩略图上的那个 ✕）。 */
    public static final int SCRIM_DARK = 0xCC0A0D14;
    /** 柔和正文（比 TEXT 暗一档，用在选中的 chip 反白之外的场景）。 */
    public static final int TEXT_SOFT = 0xFFC8D2DC;
    /** 忙碌态（通话页「在想…」的那个橙点）。 */
    public static final int BUSY = 0xFFFFB74D;
    /** 危险色的淡底（挂断按钮）。 */
    public static final int DANGER_SOFT = 0x33FF6B6B;

    // ------------------------------------------------------------ 尺寸（dp）

    /** 卡片 / 分组圆角。 */
    public static final int R_CARD = 18;
    /** 气泡圆角（那个"小尾巴"角另算，见 {@link #R_TAIL}）。 */
    public static final int R_BUBBLE = 18;
    /** 气泡说话人那一侧的"尖角"圆角。 */
    public static final int R_TAIL = 6;
    /** 图片缩略图圆角。 */
    public static final int R_IMAGE = 14;

    /** 4dp —— 图标和文字之间那种最小间隔。 */
    public static final int S1 = 4;
    /** 8dp —— 行内元素间距。 */
    public static final int S2 = 8;
    /** 12dp —— 卡片内边距。 */
    public static final int S3 = 12;
    /** 16dp —— 页面左右边距。 */
    public static final int S4 = 16;
    /** 20dp —— 分组之间。 */
    public static final int S5 = 20;
    /** 24dp —— 大段留白。 */
    public static final int S6 = 24;

    // ------------------------------------------------------------ 字号（sp）

    public static final int T_TITLE = 17;
    public static final int T_ROW = 15;
    public static final int T_BODY = 15;
    public static final int T_SUB = 12;
    public static final int T_TINY = 11;

    private Theme() { }

    // ------------------------------------------------------------ 便捷构造

    /**
     * 实心圆角矩形。
     *
     * @param color 填充色
     * @param px    圆角半径（**像素**）
     */
    public static GradientDrawable round(int color, float px) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(px);
        return d;
    }

    /**
     * 实心 + 1dp 描边。
     *
     * @param lineColor 描边色（通常 {@link #LINE}）
     * @param wPx       描边宽度（像素）
     */
    public static GradientDrawable roundStroke(int color, float px, int lineColor, int wPx) {
        GradientDrawable d = round(color, px);
        d.setStroke(Math.max(1, wPx), lineColor);
        return d;
    }

    /**
     * 渐变 + 圆角（从左到右）。
     *
     * @param px 圆角半径（像素）
     */
    public static GradientDrawable gradient(float px, int a, int b) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] { a, b });
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(px);
        return d;
    }

    /** 胶囊（半径给个大数，Canvas 会自己夹到短边的一半）。 */
    public static GradientDrawable pill(int color) {
        return round(color, 512f);
    }

    /** 胶囊 + 描边。 */
    public static GradientDrawable pillStroke(int color, int lineColor, int wPx) {
        return roundStroke(color, 512f, lineColor, wPx);
    }

    /** 正圆（头像、状态点、发送键）。 */
    public static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    // ------------------------------------------------------------ 组件级令牌

    /**
     * 气泡底：**说话人那一侧的下角是尖的**（6dp），其余 18dp。
     *
     * 左右两侧的尖角必须分开写 —— 一个 {@link GradientDrawable#setCornerRadius(float)}
     * 只能四角一样，那就成"药丸"了，看不出谁在说话。
     *
     * @param mine true = 我说的（渐变底、右下尖）；false = 它说的（SURFACE + 描边、左下尖）
     * @param r    主圆角（像素）
     * @param tail 尖角半径（像素）
     */
    public static GradientDrawable bubbleBg(boolean mine, float r, float tail, int strokePx) {
        // 顺序固定是「左上 右上 右下 左下」，每个角两个值（x,y）—— 写成 4 个值会直接抛异常
        float[] radii = mine
            ? new float[] { r, r, r, r, tail, tail, r, r }        // 右下尖
            : new float[] { r, r, r, r, r, r, tail, tail };       // 左下尖
        GradientDrawable d;
        if (mine) {
            d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, GRAD);
            d.setShape(GradientDrawable.RECTANGLE);
        } else {
            d = new GradientDrawable();
            d.setShape(GradientDrawable.RECTANGLE);
            d.setColor(SURFACE);
        }
        d.setCornerRadii(radii);
        if (!mine && strokePx > 0) d.setStroke(Math.max(1, strokePx), LINE);
        return d;
    }

    /**
     * 图标按钮的圆形 ripple（无边界）。
     *
     * 用 `RippleDrawable` 而不是 `?attr/selectableItemBackgroundBorderless`：那个属性在
     * `Theme.Material` 上解析出来的涟漪是方角灰块，在这个深色界面里像贴了一块补丁。
     */
    public static android.graphics.drawable.Drawable rippleCircle(int color) {
        GradientDrawable mask = circle(0xFFFFFFFF);
        return new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(color), null, mask);
    }
}
