package com.dsh.eye;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Tuning —— 高级设置：**直接读电脑版格式的 JSON**。
 *
 * 路径 `/sdcard/Download/DSH-Go/tuning.json`，键名与 `~/.dsh/see-now.json`
 * **完全一致**。一份文档管两个端：在电脑上调好的参数，拷到手机上就是同一个效果。
 *
 * ## 为什么不是"再多加几个设置页的开关"
 *
 * `pipeline.js::DEFAULTS` 有 60 个参数，`tuning.js` 有 14 个键。手写 74 行开关，
 * 一定会有几个漏在角落 —— 而"感觉算法还是不一样"往往就来自漏掉的那一个。
 * 所以这里用**反射**：`Opts` 里有什么字段，JSON 里就能写什么，一个都不会漏。
 *
 * ## 只有 6 个键名不一样，单独映射
 *
 * | `see-now.json` | `Opts` 字段 |
 * |---|---|
 * | `tierStyle` | `strategy` |
 * | 其余（tierMode/tierCurve/tierThresholds/tierMap/scoreNormalize） | 同名 |
 *
 * `width` / `height` 故意不给改：那是 App 的打分分辨率（240×320），
 * 改了 `gridRgb` 的采样点就变，和电脑版的对拍结论作废。
 */
final class Tuning {

    /** 放 Download 下，和 `watch-sheet.jpg` / `eye.log` 一个地方，用户拿得到。 */
    static final String FILE = "/sdcard/Download/DSH-Go/tuning.json";

    /** 已经单独映射过、或者不许改的键，反射那一步跳过。 */
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(
        "strategy", "tierMode", "tierCurve", "tierThresholds", "tierMap", "scoreNormalize",
        "width", "height"
    ));

    private JSONObject root;
    private String error;
    private long size;

    static Tuning load() {
        Tuning t = new Tuning();
        try {
            File f = new File(FILE);
            if (!f.exists()) return t;
            t.size = f.length();
            FileInputStream in = new FileInputStream(f);
            try {
                int n = (int) Math.min(f.length(), 256 * 1024);
                byte[] buf = new byte[n];
                int read = 0;
                while (read < n) {
                    int r = in.read(buf, read, n - read);
                    if (r < 0) break;
                    read += r;
                }
                t.root = new JSONObject(new String(buf, 0, read, "UTF-8"));
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable e) {
            t.error = String.valueOf(e.getMessage() == null ? e : e.getMessage());
        }
        return t;
    }

    boolean ok() { return root != null; }
    String error() { return error; }
    long size() { return size; }
    boolean exists() { return root != null || error != null; }

    /** 写进 `status` 的一行摘要。 */
    String brief() {
        if (root == null) return error == null ? "未使用（没有 tuning.json）" : "解析失败：" + error;
        return "已加载 " + size + " 字节、" + root.length() + " 个键";
    }

    /**
     * 把 JSON 里的键写进 `Pipeline.Opts`。**没写的键一个都不动。**
     *
     * `tierStyle` / `tierMode` / `tierCurve` / `tierThresholds` / `tierMap` /
     * `scoreNormalize` 先按名字映射，其余同名字段走反射。
     */
    void applyTo(Pipeline.Opts o) {
        if (root == null) return;

        o.strategy = str("tierStyle", o.strategy);
        o.tierMode = str("tierMode", o.tierMode);
        o.tierCurve = str("tierCurve", o.tierCurve);
        o.tierThresholds = darr("tierThresholds", o.tierThresholds);
        o.tierMap = iarr("tierMap", o.tierMap);
        o.scoreNormalize = bool("scoreNormalize", o.scoreNormalize);

        for (Field f : Pipeline.Opts.class.getDeclaredFields()) {
            String name = f.getName();
            if (SKIP.contains(name) || !root.has(name)) continue;
            Class<?> ty = f.getType();
            try {
                if (ty == double.class) f.setDouble(o, root.getDouble(name));
                else if (ty == int.class) f.setInt(o, root.getInt(name));
                else if (ty == boolean.class) f.setBoolean(o, root.getBoolean(name));
                else if (ty == String.class) f.set(o, root.getString(name));
            } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ 取值

    String str(String k, String def) {
        if (root == null || !root.has(k)) return def;
        try { return root.getString(k); } catch (Throwable e) { return def; }
    }

    double dbl(String k, double def) {
        if (root == null || !root.has(k)) return def;
        try { return root.getDouble(k); } catch (Throwable e) { return def; }
    }

    int num(String k, int def) {
        if (root == null || !root.has(k)) return def;
        try { return root.getInt(k); } catch (Throwable e) { return def; }
    }

    boolean bool(String k, boolean def) {
        if (root == null || !root.has(k)) return def;
        try { return root.getBoolean(k); } catch (Throwable e) { return def; }
    }

    double[] darr(String k, double[] def) {
        if (root == null || !root.has(k)) return def;
        try {
            JSONArray a = root.getJSONArray(k);
            double[] out = new double[a.length()];
            for (int i = 0; i < out.length; i += 1) out[i] = a.getDouble(i);
            return out;
        } catch (Throwable e) {
            return def;
        }
    }

    int[] iarr(String k, int[] def) {
        if (root == null || !root.has(k)) return def;
        try {
            JSONArray a = root.getJSONArray(k);
            int[] out = new int[a.length()];
            for (int i = 0; i < out.length; i += 1) out[i] = a.getInt(i);
            return out;
        } catch (Throwable e) {
            return def;
        }
    }
}
