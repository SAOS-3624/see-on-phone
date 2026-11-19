package com.dsh.eye;

import java.util.List;

/**
 * 跨层契约：**流式步骤事件** + **一轮回答的元数据**。
 *
 * <p>T2（Brain/Asker/EyeService）生产，T3（UI）消费。字段只增不改、不改语义 ——
 * 两边并行开发时，这个文件是唯一的"接口合同"。要加字段先在这里加，并保持旧字段原样。
 *
 * <p>刻意做成纯 Java（不 import android / org.json）：UI 层用假数据驱动时不需要起模拟器，
 * 数据层也能在电脑 JVM 上直接 new 出来做断言。
 */
final class Proto {

    private Proto() { }

    /**
     * 服务端工具的一步。由 `choices[0].server_tool_event` 逐帧喂进来。
     *
     * <p>T0 实测：事件挂在 **choice 顶层**（不是 delta），`type` 只有三种：
     * `call_started` / `call_completed` / `step_reasoning_done`；`call` 里带
     * `id/type/kind/action/status/sources`。
     */
    static final class Step {
        /** 第几步（0 起）。 */
        int step;
        /** call_started / call_completed / step_reasoning_done。 */
        String type = "";
        /** deepseek_web_search / deepseek_read_url。 */
        String callType = "";
        /** search / deep_search / read_link / image_search / deep_image_search。 */
        String kind = "";
        /** search / open_page / find_in_page / read_link。 */
        String action = "";
        /** 搜索词（多个用「、」连），给卡片第一行用。 */
        String query = "";
        /** 读链接/开页面时的 URL。 */
        String url = "";
        /** call_completed 时的来源数；未知为 -1。 */
        int sources = -1;
        /** completed / failed；未知为空。 */
        String status = "";
        /** 事件到达时间（毫秒），界面算每步耗时用。 */
        long at;
    }

    /**
     * 一轮回答的完整结果。**界面需要的一切都从这里拿**，不要让 UI 去读 Brain/Conv。
     */
    static final class Reply {
        /** 正文（已 trim）。 */
        String text;
        /** 思考过程（可能为空）。 */
        String reasoning;
        /** 真正想了几毫秒（0 = 没开思考或没记录）。 */
        long thinkMs;
        /** auto / high / max / off —— 界面显示「思考 6.2 秒 · 自动」用。 */
        String effort;
        /** 这一轮的模式（null 按 CHAT）。 */
        Caps.Mode mode;

        /** 引用（渲染期产物；**不进请求**）。可能为空。 */
        List<Cites.Cite> cites;
        /** 原始 server_tool_calls 数组的 JSON 文本（落 sidecar + 多轮回传）。无则 null。 */
        String stcJson;
        /** 服务端返回的会话标题（联网模式且 title 生效时才有）。 */
        String title;
        /** 搜索/Agent 步骤（按到达顺序）。可能为空。 */
        List<Step> steps;

        /** 用户按了停止（正文是半截的）。 */
        boolean stopped;
        /** 官方内容审核拒答（finish_reason=content_filter / stop_reason=refusal）。 */
        boolean refused;

        /** usage（拿不到就是 0）。 */
        int promptTokens;
        int completionTokens;
        /** 本轮是否真的发过 server tools（决定卡片要不要出现）。 */
        boolean web;
        /** 本轮估算花费（美元，见 {@link Cost}）。 */
        double costUsd;
    }
}
