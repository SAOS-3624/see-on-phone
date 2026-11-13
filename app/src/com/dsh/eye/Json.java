package com.dsh.eye;

/**
 * JSON 字符串转义的**唯一实现**。
 *
 * ## 为什么要有这个类
 *
 * 之前同一个功能在五个地方各写了一份：`ConvStore.esc`、`Speaker.esc`、`Workspace.esc`、
 * `Workspace.quote`、`Portable.err`。而且**语义还各不相同** ——
 *
 * | 位置 | 问题 |
 * |---|---|
 * | `ConvStore.esc` | **不转义 `"`** —— 会话标题里带个引号就把 JSON 打断了 |
 * | `Portable.err` | **不转义换行** —— 错误信息里带 `\n` 同样断 |
 * | `Speaker.esc` | 是对的，但控制字符直接换成空格（丢信息） |
 *
 * 这就是"补丁叠补丁"的典型：每加一个新模块就顺手抄一份旧的，抄的时候还抄错了。
 * 统一到这一个类，错也只会错一次。
 *
 * ## 为什么不用 `org.json.JSONObject.quote`
 *
 * 因为它只在 Android 上存在（`android.jar` 的桩）。这个类被 {@link Workspace} 用，
 * 而 `Workspace` 要在**电脑的 JVM** 上跑沙箱测试（`.tmp/ws/WsTest`），所以必须是纯 Java。
 */
final class Json {

    private Json() { }

    /** 转义成 JSON 字符串**内容**（不带首尾引号）。 */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i += 1) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    /** 转义成**带引号**的 JSON 字符串字面量。 */
    static String quote(String s) {
        return s == null ? "\"\"" : "\"" + esc(s) + "\"";
    }

    /** 统一的错误包：`{"ok":false,"error":"…"}`。 */
    static String err(String msg) {
        return "{\"ok\":false,\"error\":" + quote(msg == null || msg.length() == 0 ? "失败" : msg) + "}";
    }

    /** 字符串数组 → JSON 数组。 */
    static String arr(java.util.List<String> xs) {
        StringBuilder b = new StringBuilder("[");
        if (xs != null) {
            for (int i = 0; i < xs.size(); i += 1) {
                if (i > 0) b.append(',');
                b.append(quote(xs.get(i)));
            }
        }
        return b.append(']').toString();
    }
}
