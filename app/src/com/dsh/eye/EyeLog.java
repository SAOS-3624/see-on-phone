package com.dsh.eye;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 极简日志：追加到 /sdcard/Download/DSH-Go/eye.log。
 *
 * 为什么不用 Logcat：这台华为的 release 包在 logcat 里一条都看不到（连 crash buffer 都是空的）。
 * 查"窗口为什么没挂上 / 为什么重启"只能靠落盘 —— 和当初 say.txt 那条思路一样：
 * **唯一从没失败过的是文件。**
 */
final class EyeLog {
    private static final String FILE = "/sdcard/Download/DSH-Go/eye.log";
    private static final SimpleDateFormat FMT =
        new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private EyeLog() { }

    static void w(String tag, String msg) {
        try {
            File f = new File(FILE);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            if (f.length() > 64 * 1024) f.delete();      // 这不是日志归宿，超了就重开
            FileOutputStream out = new FileOutputStream(f, true);
            String line = FMT.format(new Date()) + " " + tag + " " + msg + "\n";
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) { }
    }
}
