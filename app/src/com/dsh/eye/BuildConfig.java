package com.dsh.eye;

/**
 * 版本号唯一来源。
 *
 * ⚠️ 必须和 `AndroidManifest.xml` 的 `versionName` 一致：android 用 manifest 那个，
 * 而界面/`/status` 报的是这个。两处不同步就会出现"设置页说 1.0.0、系统说 0.29.3"
 * 这种查半天的问题（交接文档第 1 节记的就是这个坑）。
 */
final class BuildConfig {
    static final String VERSION = "1.0.2";
    private BuildConfig() { }
}
