package com.dsh.eye;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Toast;

/**
 * 四个界面共用的几件小事：`dp()`、`toast()`、权限、沉浸式。
 *
 * 之前这些在 `MainActivity` / `SettingsActivity` / `ConvActivity` / `CallActivity`
 * 里**各有一份**（`dp` 4 份、`toast` 4 份、沉浸式 2 份）。这是典型的"新加一个界面就
 * 从头抄上一个"，抄的时候还可能抄错（有一次 `dp` 用了 float 加法，四舍五入就不一样了）。
 *
 * 抽到基类之后，界面的 diff 里就只剩"这个界面独有的东西"。
 */
abstract class AppActivity extends Activity {

    /** dp → px（四舍五入，不许用 (int) 截断：差 1px 在细边框上看得见）。 */
    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    void toast(String s) {
        Toast.makeText(this, s == null ? "" : s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 权限

    /** 有没有相机权限（<23 恒 true —— 装机即授权）。 */
    boolean hasCamera() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    /** 首次启动要的那一组（相机 / 录音 / 读写存储）。 */
    void askCorePerms(int code) {
        if (Build.VERSION.SDK_INT < 23) return;
        requestPermissions(new String[] {
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        }, code);
    }

    /**
     * 存储权限单独补一次。
     *
     * 相机给了之后 {@link #hasCamera()} 就是 true，以后每次启动都不会再进权限请求分支 ——
     * 但存储可能从来没给过。而会话、图、记忆全在 `/sdcard/Download/DSH-Go/` 下，
     * 没有它就是"能聊但什么都存不住"。
     */
    void ensureStorage(int code) {
        if (Build.VERSION.SDK_INT < 23) return;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[] {
            Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE
        }, code);
    }

    // ------------------------------------------------------------ 沉浸式

    /**
     * 状态栏透明 + 内容延伸到状态栏下。
     *
     * ⚠️ 用了它就必须自己加 `statusBarHeight` 的 padding（见 {@link TopBar#statusBarHeight}），
     * 否则第一条内容会钻到刘海/状态栏文字底下 —— 深色界面上这看起来像"内容被裁了"。
     */
    void immersive() {
        if (Build.VERSION.SDK_INT < 21) return;
        Window w = getWindow();
        w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        w.getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /** 系统状态栏高度（沉浸式下算 padding 用）。 */
    int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    /** 系统导航栏高度（全屏页面底部留白用）。 */
    int navigationBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    // ------------------------------------------------------------ 键盘让位

    /**
     * 让根容器给系统键盘让出位置（**四页都用这一个**）。
     *
     * ## 为什么需要它
     *
     * `immersive()` 里的 `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` 会让 decor 不再替我们
     * 消费 insets，于是清单里的 `windowSoftInputMode="adjustResize"` **失效** ——
     * 键盘弹起时窗口高度一点没变（uiautomator 拿到的 root 还是 `[0,0][1084,2186]`），
     * 输入栏被压在键盘底下，打字看不见。这是安卓老问题（沉浸式与 adjustResize 天然打架），
     * 只能自己拿 insets 换成 padding。
     *
     * ## 为什么不是简单的 `padding = insetBottom`
     *
     * `getSystemWindowInsetBottom()` 在**键盘收起时也有值**（= 导航栏高度），
     * 直接赋值会在键盘收起时凭空多出一条空白带（QA 已经验证过当前底部留白的样子，
     * 不能动）。所以这里记一个**静息基线**，只补"超出基线的那部分"：
     *
     * ```
     * 收起：hidden == 基线           → 补 0（视觉零变化）
     * 弹起：hidden == 基线 + 键盘高度 → 补键盘高度
     * ```
     *
     * 两条数据源取 max：`WindowInsets`（正路）和 `getWindowVisibleDisplayFrame`（兜底，
     * OEM 不给 IME inset 时靠它）。两者写同一个 padding，所以不会叠加成两倍。
     */
    void keepAboveKeyboard(final View root) {
        if (root == null) return;
        root.getViewTreeObserver().addOnGlobalLayoutListener(
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                public void onGlobalLayout() { applyKeyboardPadding(root); }
            });
        if (Build.VERSION.SDK_INT >= 20) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                public WindowInsets onApplyWindowInsets(View v, WindowInsets ins) {
                    int b = ins.getSystemWindowInsetBottom();
                    // ⚠️ 这里**不能**只往上记（`if (b > kbFromInsets) kbFromInsets = b`）。
                    //    那样键盘收起后 kbFromInsets 永远停在键盘高度，底部留白再也回不去 ——
                    //    屏幕上就是一截空白，输入栏吊在半空。键盘收起时 b 会回到导航栏高度，
                    //    跟着它一起降下来才对。
                    kbFromInsets = b;
                    applyKeyboardPadding(root);
                    return ins;
                }
            });
            root.requestApplyInsets();
        }
        root.post(new Runnable() {
            public void run() { applyKeyboardPadding(root); }
        });
    }

    /** 键盘 inset（WindowInsets 报上来的那份；键盘收起时通常是导航栏高度）。 */
    private int kbFromInsets;
    /** 静息基线：键盘收起时 `rootH - visibleFrame.bottom` 的值。-1 = 还没测到。 */
    private int kbBase = -1;

    private void applyKeyboardPadding(View root) {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            root.getWindowVisibleDisplayFrame(r);
            int hidden = root.getRootView().getHeight() - r.bottom;
            // 基线只往下走：万一第一次就测在键盘弹起时，键盘收起那次会把基线拉回真值
            if (kbBase < 0 || hidden < kbBase) kbBase = hidden;
            int extra = hidden - kbBase;
            int want = Math.max(extra, kbFromInsets - kbBase);
            // 小于 100dp 的抖动当成导航栏/手势条的噪声，别去补（否则底部会一跳一跳）
            if (want < dp(100)) want = 0;
            if (root.getPaddingBottom() == want) return;
            root.setPadding(root.getPaddingLeft(), root.getPaddingTop(),
                root.getPaddingRight(), want);
        } catch (Throwable ignored) { }
    }
}
