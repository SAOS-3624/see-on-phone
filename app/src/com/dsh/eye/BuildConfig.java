package com.dsh.eye;

/**
 * 版本号的**唯一读取口**：直接问系统，不再维护第二份常量。
 *
 * ⚠️ 这里原来是一个写死的 `VERSION = "1.3.2"`，注释还写着"必须和 AndroidManifest 的
 * versionName 一致"。结果是每次发版都要手动改两个地方 —— 2026-11-14 升 1.3.4 / 1.3.5
 * 时漏了它，`/status` 一直报 1.3.2，看接口会以为版本根本没更新。
 *
 * 现在只从 `PackageManager` 读 manifest 里那个值：**不可能再不同步**。
 * 发版只要改 `AndroidManifest.xml` 的 `versionCode` / `versionName` 一处。
 */
final class BuildConfig {

    /** `applicationContext`，由 `EyeService.onCreate` 挂上。只为给不带 Context 的静态工具用。 */
    private static android.content.Context app;

    static void attach(android.content.Context ctx) {
        if (ctx != null) app = ctx.getApplicationContext();
    }

    /** 不带 Context 的场合（`Portable` 那种静态工具）用这个。 */
    static String version() { return version(app); }

    static String version(android.content.Context ctx) {
        if (ctx == null) return "?";
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "?";
        }
    }

    private BuildConfig() { }
}
