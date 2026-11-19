package com.dsh.eye;

import java.util.List;
import java.util.Locale;

/**
 * 花费估算。**只做估算，不声称账单** —— 官方牌价会变，改动只在本文件。
 *
 * <p>口径：
 * <ul>
 *   <li>输入/输出按 token × 每百万 token 单价；</li>
 *   <li>`web_search` / `read_url`（含服务端搜索/读链接）按**次**计费；</li>
 *   <li>不区分缓存命中（命中会更便宜），所以显示值通常**略高**，不会低报。</li>
 * </ul>
 *
 * <p>价格来源：DeepSeek 官方 V3.2/4 系列牌价的保守取整；pro 按 flash 的 2×/4× 估算。
 * 真要精确对账，把下面 4 个常量换成账单页的数字即可。
 */
final class Cost {

    private Cost() { }

    // ---- 每百万 token 的美元价 ----
    // 参考 DSH Cost Meter 的 DeepSeek V4 牌价（opencode-go 档）：
    //   flash 输入 $0.15 / 输出 $0.60；pro 输入 $0.66 / 输出 $1.98。
    // 未计 UTC 峰谷倍率与缓存命中折扣（命中会更便宜，所以这里通常略高估）。
    private static final double FLASH_IN = 0.15;
    private static final double FLASH_OUT = 0.60;
    private static final double PRO_IN = 0.66;
    private static final double PRO_OUT = 1.98;

    // ---- 原子工具按次计费（美元/次） ----
    static final double SEARCH_CALL = 0.00075;   // /v1/tools/search
    static final double FETCH_CALL = 0.00075;    // /v1/tools/fetch

    /** 本轮估算花费（美元）。 */
    static double of(String model, int promptTokens, int completionTokens, List<Proto.Step> steps) {
        double in = FLASH_IN;
        double out = FLASH_OUT;
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (m.contains("pro")) {
            in = PRO_IN;
            out = PRO_OUT;
        }
        double usd = Math.max(0, promptTokens) / 1_000_000.0 * in
            + Math.max(0, completionTokens) / 1_000_000.0 * out;
        usd += countTools(steps) * SEARCH_CALL;   // fetch 同价，下面按类型分
        return usd;
    }

    /** 这一轮有几个计次的网络工具（搜索/读网页）。 */
    static int countTools(List<Proto.Step> steps) {
        if (steps == null) return 0;
        int n = 0;
        for (Proto.Step s : steps) {
            if (s == null) continue;
            String t = s.callType == null ? "" : s.callType;
            String a = s.action == null ? "" : s.action;
            if (t.contains("web_search") || t.contains("read_url")
                || a.contains("search") || a.contains("read_link")) {
                n += 1;
            }
        }
        return n;
    }

    /** 美元显示：小于 1 分用 6 位，往上用 4 位；0 显示 $0。 */
    static String fmt(double usd) {
        if (usd <= 0 || Double.isNaN(usd)) return "$0";
        if (usd < 0.01) return String.format(Locale.ROOT, "$%.6f", usd);
        return String.format(Locale.ROOT, "$%.4f", usd);
    }

    /** 顶栏副标题用的短格式（空间小，1 分以下保留 4 位）。 */
    static String fmtShort(double usd) {
        if (usd <= 0 || Double.isNaN(usd)) return "$0";
        if (usd < 0.01) return String.format(Locale.ROOT, "$%.4f", usd);
        if (usd < 1) return String.format(Locale.ROOT, "$%.3f", usd);
        return String.format(Locale.ROOT, "$%.2f", usd);
    }
}
