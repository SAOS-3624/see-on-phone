package com.dsh.eye;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 手机上那个「一点就传到电脑」的动作。
 *
 * ## 它到底做了什么
 *
 * 手机**不能主动推**给一个不知道在哪的电脑。所以这里的做法是：
 * 电脑那边开着同步面板（`tools/phone-sync.mjs`），它启动时会执行
 * `adb reverse tcp:7788 tcp:7788` —— 于是**手机里的 127.0.0.1:7788 就是电脑的面板**。
 *
 * 手机只要请求一次 `/run?cmd=receive`，电脑就去 pull + import 刚才打好的包。
 * 换句话说：**握手是 adb reverse 建的，握手之后手机这边一句话就能触发整条链**。
 *
 * ## 为什么不等结果
 *
 * pull + import 要好几秒（拉包 + 转 zstd + 写库）。手机没必要干等 ——
 * 短超时，超时也当成功（说明请求已经发出去了，电脑正在处理）。
 */
final class Sync {

    private Sync() { }

    /** 返回 null = 电脑已收到；否则是"为什么没成"（给人看的一句话）。 */
    static String trigger(String host) {
        String h = host == null || host.trim().length() == 0 ? "127.0.0.1:7788" : host.trim();
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://" + h + "/run?cmd=receive");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(3000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code != 200) return "电脑回 " + code;
            InputStream in = c.getInputStream();
            byte[] buf = new byte[4096];
            while (in.read(buf) > 0) { /* 丢掉输出，手机不展示 */ }
            EyeLog.w("sync", "已触发电脑同步 " + h);
            return null;
        } catch (java.net.SocketTimeoutException te) {
            // 读超时 = 请求已经送到，电脑还在搬 —— 这不是失败
            EyeLog.w("sync", "请求已送出，电脑还在处理");
            return null;
        } catch (Throwable e) {
            EyeLog.w("sync", "连不上 " + h + "：" + e);
            return String.valueOf(e.getMessage() == null ? e : e.getMessage());
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
        }
    }
}
