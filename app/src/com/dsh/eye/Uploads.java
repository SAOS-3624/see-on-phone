package com.dsh.eye;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 文档附件的**准备 → 上传 → 复用**链路。
 *
 * ## 为什么不直接把 `Uri` 传上去
 *
 * `Uri` 是 SAF 的临时授权，进程一死（或重启后）就作废；而官方 `/files` 要的是一份
 * **真实的字节流**，且流式请求必须知道总长度（`setFixedLengthStreamingMode`）。
 * 所以一律先**复制一份到自己的 cache**，之后上传、重试都只对着这个 file 说话。
 *
 * ## 落盘只留 file_id，不留原文件
 *
 * 官方 file 对象**不设过期时间**（"会话里的历史附件一直可用"），所以上传成功之后
 * 本地那份副本就是纯浪费（一份 100MB 的 PDF 占手机 100MB，而 `file_id` 只占几十字节）。
 * 顺序是死的：**先记 `fileId`，再删副本**（见 {@link #upload}）—— 反过来一旦删除成功
 * 而写 fileId 失败，这个附件就从对话里消失了。
 *
 * ## 文件名
 *
 * 中文原样传（`Brain.upload` 用 UTF-8 原始字节写 multipart header，**不做 URL 编码**）。
 * 这里只把文件系统不接受的字符（`/ \ : * ? " < > |`）和控制字符换成 `_`，
 * 因为副本要落在 ext4 上；原始名留在 {@link Job#name} 里，上传和界面都用它。
 *
 * ## 线程
 *
 * `attach()` 是**同步**的（复制 100MB 要几百毫秒到几秒），调用方自己放后台线程；
 * `upload()` / `retry()` 自己进单线程池（最多 2 并发），回调**回到主线程**。
 * `sweep()` 同步、很轻（几十个文件的 stat + delete）。
 */
final class Uploads {

    private Uploads() { }

    // ---------------------------------------------------------------- 常量

    /** 待传：副本已就绪，还没开始。 */
    static final int ST_WAIT = 0;
    /** 上传中（`pct` 有意义）。 */
    static final int ST_UP = 1;
    /** 成功（`fileId` 有值，副本已删）。 */
    static final int ST_OK = 2;
    /** 失败/取消（副本还在，可重试）。 */
    static final int ST_FAIL = 3;

    /** 一次最多几个文档（再多模型也读不完，而且一次请求的 token 会翻倍）。 */
    static final int MAX_DOCS = 5;

    private static final long MB = 1024L * 1024L;
    /** 图片上限（这条只是防御：图片走相册那条 base64 路，不经过这里）。 */
    static final long MAX_IMAGE = 64 * MB;
    /** PDF / Office：官方按页解析，100MB 是给自己留的余量。 */
    static final long MAX_OFFICE = 100 * MB;
    /** 纯文本 / 表格：这个量级再大，解析出来的文字也只会把上下文预算吃光。 */
    static final long MAX_TEXT = 32 * MB;

    /** 走"整页版面识别"的格式（扫描件、PPT、Word：保住版面才读得对）。 */
    static final String PURPOSE_LAYOUT = "rich_layout_recognition";
    /** 其余一律 `user_data`（官方当普通资料读）。 */
    static final String PURPOSE_DATA = "user_data";

    /** 副本保留多久（超过就由 {@link #sweep} 清掉）。 */
    private static final long TTL_MS = 24L * 3600L * 1000L;

    private static final String[] EXT_LAYOUT = { "pdf", "doc", "docx", "ppt", "pptx" };
    private static final String[] EXT_TEXT = {
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm",
        "yml", "yaml", "log", "ini", "conf", "toml", "rtf", "epub", "mobi",
        "xlsx", "xls",
        // 源码也当纯文本：用户丢一个 .py 进来问"这段在干嘛"是最常见的用法之一
        "java", "kt", "py", "js", "ts", "c", "cpp", "h", "hpp", "go", "rs",
        "sh", "sql", "css",
    };
    private static final String[] EXT_IMAGE = {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif",
    };

    /** 上传用单线程池（2 并发）：再多会把上行带宽切碎，进步反而更慢。 */
    private static final ExecutorService RUNNER = Executors.newFixedThreadPool(2,
        new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "upload");
                t.setDaemon(true);
                return t;
            }
        });

    private static final Handler UI = new Handler(Looper.getMainLooper());

    // ---------------------------------------------------------------- 数据

    /**
     * 一个待发/在传/已传的文档。
     *
     * 字段全 public（包内）：界面要读 `state/pct/error` 画卡片，发送时要 `fileId`。
     * 没有 getter 是因为它在同包里只被 `ChatInputBar` 和 `SettingsActivity` 用。
     */
    static final class Job {
        /** 本地唯一标识（同名文件可以选两次，靠它区分）。 */
        String localId;
        /** 原始文件名（中文原样，上传和界面都用它）。 */
        String name;
        /** `rich_layout_recognition` / `user_data`。 */
        String purpose;
        /** 上传成功后的 `file_id`（`file-api-…`）。 */
        String fileId;
        /** 失败原因（**已经是可以直接给用户看的中文**，见 {@link #reason}）。 */
        String error;
        /** cache 里的副本（成功后置 null —— 副本已删）。 */
        File cache;
        /** 原始字节数。 */
        long size;
        /** {@link #ST_WAIT} … {@link #ST_FAIL}。 */
        int state = ST_WAIT;
        /** 0..100（复制阶段是复制进度，上传阶段是上传进度）。 */
        int pct;
        /** 用户点了取消（`Brain.upload` 每 64KB 查一次，当轮就停）。 */
        volatile boolean cancelled;

        boolean usable() {
            return state == ST_OK && fileId != null && fileId.trim().length() > 0;
        }

        /** 卡片的副标题：`1.2MB` / `上传中 34%` / `上传失败 · 点重试`。 */
        String statusText() {
            if (state == ST_UP) return "上传中 " + pct + "%";
            if (state == ST_OK) return humanSize(size) + " · 已就绪";
            if (state == ST_FAIL) return error == null || error.length() == 0
                ? "上传失败 · 点重试" : error;
            return humanSize(size) + " · 等待上传";
        }

        public String toString() {
            return "job{" + name + " " + state + " " + pct + "% " + fileId + "}";
        }
    }

    /**
     * 回调。
     *
     * ⚠️ 线程约定：{@link #upload} / {@link #retry} 的回调**在主线程**；
     * {@link #attach}（带 cb 的那个重载）的回调在**调用线程** —— 因为复制是同步的，
     * 调用方本来就在后台线程上，再绕一趟主线程只会让进度抖。
     */
    interface Cb {
        void onProgress(Job j);
        void onDone(Job j);
        void onFail(Job j);
    }

    // ---------------------------------------------------------------- 路径 / 小工具

    /** `cache/uploads/`（不用外部存储：附件是中间产物，卸载就该没）。 */
    static File dir(Context c) {
        File d = new File(c.getApplicationContext().getCacheDir(), "uploads");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 缓存目录现在占多少字节（设置页那一行显示它）。 */
    static long sizeOnDisk(Context c) {
        long n = 0;
        File[] fs = dir(c).listFiles();
        if (fs == null) return 0;
        for (File f : fs) if (f.isFile()) n += f.length();
        return n;
    }

    /** `1.2 MB` / `340 KB` / `12 B`。 */
    static String humanSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < MB) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (double) MB);
    }

    /** 小写扩展名（没有后缀 → ""）。 */
    static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 落到文件系统上的安全名：去掉 `/ \ : * ? " < > |` 与控制字符，限长 80。
     *
     * 只用于**副本**的文件名；上传和界面用的是 {@link Job#name}（原名，中文原样）。
     */
    static String safeName(String name) {
        String n = name == null ? "" : name.trim();
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i += 1) {
            char c = n.charAt(i);
            boolean bad = c < 0x20 || c == 0x7F
                || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                || c == '"' || c == '<' || c == '>' || c == '|';
            b.append(bad ? '_' : c);
        }
        String s = b.toString();
        if (s.length() > 80) s = s.substring(0, 80);
        if (s.length() == 0) s = "file";
        return s;
    }

    /** 这个后缀的临时副本能有多大（-1 = 不支持）。 */
    static long limitOf(String ext) {
        if (in(EXT_IMAGE, ext)) return MAX_IMAGE;
        if (in(EXT_LAYOUT, ext)) return MAX_OFFICE;
        if (in(EXT_TEXT, ext)) return MAX_TEXT;
        return -1;
    }

    /** 这个后缀该用哪个 purpose。 */
    static String purposeOf(String ext) {
        return in(EXT_LAYOUT, ext) ? PURPOSE_LAYOUT : PURPOSE_DATA;
    }

    /** 是图片吗（这类走相册那条 base64 路，不经过上传）。 */
    static boolean isImage(String name) {
        return in(EXT_IMAGE, ext(name));
    }

    /** 从 SAF 的 `Uri` 认一下是不是图片（先看 mime，再看后缀）。 */
    static boolean isImage(Context c, Uri uri) {
        try {
            String t = c.getContentResolver().getType(uri);
            if (t != null && t.startsWith("image/")) return true;
        } catch (Throwable ignored) { }
        return isImage(nameOf(c, uri));
    }

    private static boolean in(String[] xs, String v) {
        if (v == null) return false;
        for (int i = 0; i < xs.length; i += 1) if (xs[i].equals(v)) return true;
        return false;
    }

    // ---------------------------------------------------------------- SAF 元信息

    /** 选择器给的真实文件名（`OpenableColumns.DISPLAY_NAME`，中文原样）。 */
    static String nameOf(Context c, Uri uri) {
        if (uri == null) return "";
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null && n.trim().length() > 0) return n.trim();
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) { try { cur.close(); } catch (Throwable ignored) { } }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    /** 选择器给的真实大小（拿不到返回 0 —— 有些云盘 provider 就是不给）。 */
    static long sizeOf(Context c, Uri uri) {
        if (uri == null) return 0;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri,
                new String[] { OpenableColumns.SIZE }, null, null, null);
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) { try { cur.close(); } catch (Throwable ignored) { } }
        }
        return 0;
    }

    // ---------------------------------------------------------------- 准备（校验 + 复制）

    /** 等于 `attach(c, uri, null, 0, null)`：名字和大小都从 SAF 问。 */
    static Job attach(Context c, Uri uri, String name, long size) {
        return attach(c, uri, name, size, null);
    }

    /**
     * **校验 + 复制到 cache**，同步执行（100MB 要几百毫秒到几秒，调用方放后台线程）。
     *
     * 校验在**复制之前**做：文件太大时不该先把 100MB 拷一遍再告诉用户"太大了"。
     * 校验失败不抛，返回 `state=ST_FAIL` + 中文 `error`（界面直接显示，不用再解释）。
     *
     * @param cb 可为 null；非 null 时按已拷字节回调（**在调用线程上**）
     */
    static Job attach(Context c, Uri uri, String name, long size, Cb cb) {
        Job j = new Job();
        j.localId = localId();
        j.name = name == null || name.trim().length() == 0 ? nameOf(c, uri) : name.trim();
        j.size = size > 0 ? size : sizeOf(c, uri);
        j.state = ST_WAIT;

        if (uri == null) return fail(j, "没拿到这个文件");
        if (j.name == null || j.name.trim().length() == 0) j.name = "未命名文件";
        // purpose 必须在**名字定下来之后**算：有些 provider 只在 query 里给名字，
        // 传进来的 name 是空的，这时按空串算会把这个 PDF 当成纯文本发上去。
        j.purpose = purposeOf(ext(j.name));
        String e = ext(j.name);
        long limit = limitOf(e);
        if (limit < 0) {
            return fail(j, "这个格式还读不了（支持 PDF / Office / 文本 / 表格）");
        }
        if (j.size <= 0) return fail(j, "空文件（0 字节），发不了");
        if (j.size > limit) {
            return fail(j, "太大了（" + humanSize(j.size) + "，上限 " + humanSize(limit) + "）");
        }

        File out = new File(dir(c), j.localId + "-" + safeName(j.name));
        InputStream in = null;
        OutputStream o = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) return fail(j, "这个文件打不开（没权限或已被移走）");
            o = new FileOutputStream(out, false);
            byte[] buf = new byte[64 * 1024];          // 64KB 一块，绝不读进 byte[]
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                done += n;
                int p = j.size > 0 ? (int) Math.min(100, done * 100 / j.size) : 0;
                if (p != j.pct) {
                    j.pct = p;
                    if (cb != null) cb.onProgress(j);
                }
            }
            o.flush();
        } catch (Throwable t) {
            try { if (o != null) o.close(); } catch (Throwable ignored) { }
            try { out.delete(); } catch (Throwable ignored) { }
            return fail(j, "复制失败：" + brief(t));
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (o != null) o.close(); } catch (Throwable ignored) { }
        }
        j.cache = out;
        j.size = out.length() > 0 ? out.length() : j.size;
        j.pct = 100;
        j.state = ST_WAIT;
        EyeLog.w("upload", "副本就绪 " + j.name + " " + humanSize(j.size));
        return j;
    }

    private static Job fail(Job j, String why) {
        j.state = ST_FAIL;
        j.error = why;
        j.pct = 0;
        EyeLog.w("upload", "拒收 " + j.name + "：" + why);
        return j;
    }

    private static String localId() {
        return Long.toHexString(System.currentTimeMillis())
            + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    // ---------------------------------------------------------------- 上传

    /**
     * 开始上传（异步，回调在主线程）。
     *
     * 成功路径的顺序是**死的**：先写 `fileId`，再删副本。
     */
    static void upload(final Config cfg, final Job j, final Cb cb) {
        if (j == null) return;
        if (j.usable()) { postDone(cb, j); return; }                  // 已经传过了
        if (cfg == null || !cfg.direct()) {
            j.state = ST_FAIL;
            j.error = "还没填 key，传不了文件";
            postFail(cb, j);
            return;
        }
        if (j.cache == null || !j.cache.exists()) {
            j.state = ST_FAIL;
            j.error = "这份副本不见了，重新选一次文件";
            postFail(cb, j);
            return;
        }
        long max = limitOf(ext(j.name));
        if (max > 0 && j.size > max) {
            j.state = ST_FAIL;
            j.error = "太大了（上限 " + humanSize(max) + "）";
            postFail(cb, j);
            return;
        }

        j.cancelled = false;
        j.state = ST_UP;
        j.pct = 0;
        j.error = null;
        postProgress(cb, j);

        RUNNER.execute(new Runnable() {
            public void run() {
                Brain.ProgressCb pc = new Brain.ProgressCb() {
                    public void onProgress(long sent, long total) {
                        int p = total <= 0 ? 0 : (int) Math.min(99, sent * 100 / total);
                        if (p != j.pct) {
                            j.pct = p;
                            postProgress(cb, j);
                        }
                    }
                    public boolean aborted() { return j.cancelled; }
                };

                String id = null;
                String err = null;
                boolean wasCancelled = false;
                try {
                    org.json.JSONObject o = Brain.upload(cfg, j.cache, j.name, j.purpose, pc);
                    id = o == null ? null : Brain.str(o, "id", null);
                    if (id == null || id.trim().length() == 0) {
                        err = "服务端没给文件号，再传一次试试";
                    }
                } catch (Throwable t) {
                    wasCancelled = j.cancelled;
                    err = wasCancelled ? null : reason(t);
                    if (!wasCancelled) EyeLog.w("upload", "失败 " + j.name + "：" + brief(t));
                }

                if (id != null && id.trim().length() > 0) {
                    j.fileId = id.trim();                 // ① 先记 file_id
                    j.state = ST_OK;                      // ② 再删副本
                    j.pct = 100;
                    j.error = null;
                    File copy = j.cache;
                    j.cache = null;
                    if (copy != null) { try { copy.delete(); } catch (Throwable ignored) { } }
                    EyeLog.w("upload", "成功 " + j.name + " → " + j.fileId);
                    postDone(cb, j);
                } else if (wasCancelled) {
                    j.state = ST_FAIL;
                    j.error = "已取消";
                    postFail(cb, j);
                } else {
                    j.state = ST_FAIL;
                    j.error = err == null || err.length() == 0 ? "上传失败，点一下重试" : err;
                    postFail(cb, j);
                }
            }
        });
    }

    /** 重试：副本还在就再来一次；已经成功过就什么都不做。 */
    static void retry(Config cfg, Job j, Cb cb) {
        if (j == null) return;
        if (j.usable()) { postDone(cb, j); return; }
        if (j.cache == null || !j.cache.exists()) {
            j.state = ST_FAIL;
            j.error = "这份副本不见了，重新选一次文件";
            postFail(cb, j);
            return;
        }
        upload(cfg, j, cb);
    }

    /**
     * 取消。真的在传时只置标记 —— `Brain.upload` 每 64KB 查一次 `aborted()`
     * 并抛 `IOException("上传已取消")`，那一轮的连接会当轮断开。
     */
    static void cancel(Job j) {
        if (j == null) return;
        j.cancelled = true;
        if (j.state == ST_WAIT) {
            j.state = ST_FAIL;
            j.error = "已取消";
        }
    }

    // ---------------------------------------------------------------- 清理

    /**
     * 清 cache 里的副本。
     *
     * @param force true = 设置页手动清理（不管多新都删）；false = 只删超过 24 小时的
     * @return 删了几个（调用方可以据此提示"清了 3 个"，返回值可以不管）
     */
    static int sweep(Context c, boolean force) {
        File d = dir(c);
        File[] fs = d.listFiles();
        int n = 0;
        long now = System.currentTimeMillis();
        if (fs != null) {
            for (File f : fs) {
                if (f == null || f.isDirectory()) continue;
                if (force || now - f.lastModified() > TTL_MS) {
                    if (f.delete()) n += 1;
                }
            }
        }
        if (n > 0) {
            EyeLog.w("upload", (force ? "手动" : "过期") + "清理 uploads/ " + n + " 个副本");
        }
        return n;
    }

    // ---------------------------------------------------------------- 交给上层

    /** 转成落盘/请求用的附件引用；没传成功返回 null。 */
    static Conv.Att ref(Job j) {
        if (j == null || !j.usable()) return null;
        return Conv.Att.of(j.fileId, j.name, j.size, j.purpose, Conv.Att.ST_OK);
    }

    // ---------------------------------------------------------------- 错误人话

    /**
     * 把异常翻成**一句用户看得懂的中文**。
     *
     * 走 {@link Err} 的分类表：同一个 400，欠费和"文件太大"不该给同一句话。
     * 技术细节只进 {@link EyeLog}。
     */
    static String reason(Throwable e) {
        if (e == null) return "上传失败，点一下重试";
        if (e instanceof Brain.HttpError) {
            Brain.HttpError h = (Brain.HttpError) e;
            String low = h.body == null ? "" : h.body.toLowerCase(Locale.ROOT);
            if (h.code == 413
                || (h.code == 400 && (low.contains("too large") || low.contains("exceed")
                    || low.contains("max size") || low.contains("file size")))) {
                return Err.cta(Err.Code.UPLOAD_TOO_BIG);
            }
            if (h.code == 400 && (low.contains("parse") || low.contains("recogni")
                || low.contains("extract"))) {
                return Err.cta(Err.Code.UPLOAD_PARSE_FAIL);
            }
            return Err.cta(Err.of(h.code, h.body));
        }
        if (e instanceof java.net.SocketTimeoutException) return Err.cta(Err.Code.TIMEOUT);
        if (e instanceof java.io.FileNotFoundException) return "这个文件读不到了（可能被删了）";
        if (e instanceof java.io.IOException) return Err.cta(Err.Code.OFFLINE);
        if (e instanceof org.json.JSONException) return Err.cta(Err.Code.PARSE);
        return "上传失败，点一下重试";
    }

    /** 日志用的短原因（界面上不显示）。 */
    private static String brief(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        String s = t.getClass().getSimpleName() + (m == null ? "" : "：" + m);
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    // ---------------------------------------------------------------- 主线程回投

    private static void postProgress(final Cb cb, final Job j) {
        if (cb == null) return;
        UI.post(new Runnable() {
            public void run() { cb.onProgress(j); }
        });
    }

    private static void postDone(final Cb cb, final Job j) {
        if (cb == null) return;
        UI.post(new Runnable() {
            public void run() { cb.onDone(j); }
        });
    }

    private static void postFail(final Cb cb, final Job j) {
        if (cb == null) return;
        UI.post(new Runnable() {
            public void run() { cb.onFail(j); }
        });
    }
}
