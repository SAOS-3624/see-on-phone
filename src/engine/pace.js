/**
 * pace.js — 节奏档与逐帧模式。**与桌面版 see-now 的 `runner.js` 逐字一致**，
 * 只是抽成独立模块（手机版没有 runner，采集层在 cam/ 里自写）。
 *
 * 为什么手机也要这一套：`cutAbs` 是全局常量，模型听见省点钱也只能嘴上答应 ——
 * 频率完全由插件定。用户实报回复太密集，说明缺一个能让这句话生效的旋钮。
 *
 * ⚠️ 改这里的任何数字之前，先回 `dsh-see-now/src/runner.js` 看同一段注释里的实测表。
 */

/**
 * 「逐帧 / 逐场景」模式的管线参数。
 *
 * - `maxFrames: 1` —— 留下 1 帧就立刻封箱（`checkFlush` 的 `frames.length >= maxFrames`），
 *   所以一张图永远只有一个画面。
 * - `strategy: 'single'` —— 档位固定压到 1，让这一帧**铺满整张画布**。
 *   不压的话，场景密集时密度档位会升到 2/3，一格只占 1/4、1/9，中间全是灰条。
 *
 * 这两个必须成对出现：只改 maxFrames 会得到"小格子 + 大片留白"，只改 strategy
 * 会得到"一张图里好几幅满幅图，装不下就被切开"。
 */
export const SCENE_OPTS = Object.freeze({ maxFrames: 1, strategy: 'single' });

/**
 * 三档节奏。**只影响"多久算一次画面变了"和"多久叫醒一次"**，不改出图口径。
 *
 * 为什么要有它：`cutAbs` 是全局常量，模型听见"省点钱"也只能嘴上答应 ——
 * 频率完全由插件定。用户实报"回复太密集"，说明缺一个能让这句话生效的旋钮。
 *
 * | 档 | cutAbs | 唤醒间隔 | 适合 |
 * |---|---|---|---|
 * | calm | 20 | 30s | 日常挂着看，只想知道"大场面变了没" |
 * | normal | 12 | 15s | 默认。滚网页/切窗口不会每下都留帧 |
 * | dense | 7 | 8s | 幻灯片、逐帧播报这种"每一刀都要"的 |
 * | game-economic | 80 | 20s | 全屏游戏 / 高速运动。靠 `pipeline` 把积分阈值也一起抬高 |
 *
 * 7 是能给的最低值（再低只有"只改亮度"那种肉眼都看不出的变化），
 * 但它对日常屏幕太敏感 —— 光标一停、窗口一换都能过线，所以不做默认。
 *
 * 80 看着离谱，其实是被内容逼的：动作游戏里每帧都有大位移，正常切点淹没在运动里。
 * 这一档同时把积分阈值从 144 抬到 1440（`integralSeconds` 3 → 30），
 * 否则"改画面"的门槛抬了、出帧密度却没降，还是会被叫到贴限速。
 */
export const PACES = Object.freeze({
  // 三档都带自适应兜底（`floorGapK`）：兜底间隔 = k × 近期出帧间隔，夹在 [floorMin, floorMax]。
  // 越省电的档兜底越长（calm k=3 / normal k=2 / dense k=1.2），因为省电档宁可漏也不要多。
  // normal 取 2 而不是 1.5：实测覆盖率 64.2→67.4（SHOT）/ 89.1→94.2（BBC），帧数只 +8~10%；
  // 1.5 能到 70.4 / 97.5，但 BBC 上每多 1 个点要花 83 帧（k=2 只要 47），是为最后几个点付溢价。
  // 想要全覆盖：给 normal 传 floorGapK: 1.5。
  // calm 刻意只抓大场面：关掉 6~20 分的弱切点，避免省电档反而变吵
  calm: { cutAbs: 20, wakeGapSec: 30, pipeline: { cutIsolatedAbs: 0, cutMcAbs: 0, floorGapK: 3, floorMin: 3, floorMax: 30 } },
  normal: { cutAbs: 12, wakeGapSec: 15, pipeline: { floorGapK: 2, floorMin: 2, floorMax: 20 } },
  dense: { cutAbs: 7, wakeGapSec: 8, pipeline: { floorGapK: 1.2, floorMin: 2, floorMax: 20 } },
  /**
   * 全屏动作游戏 / 高速运动画面（key 用 `game-economic`：目标就是"陪玩但别烧钱"）。
   *
   * 为什么需要单独一档：这类画面**每一帧都在动**，分数基线被抬高，切点门槛 12 等于"全是切点"，
   * 尖峰判据也救不回来（左右邻居同样高）。实测反馈（B 站用户「总在冬眠的熊」，打了一两个小时
   * 全屏动作游戏后自己调出来的值）：cutAbs 80、积分阈值约 1800、每张 6 帧、maxWait 120s，
   * 才从"顶着限速发请求 + 40 帧一张巨糊"变成"舒适"。
   *
   * 这里取比他**略保守**的一组（积分 1440 而不是 1800，每张 8 帧而不是 6），
   * 因为他的 40 帧/张是在 normal 档下被 maxWait 逼出来的，不是他真想要 6 帧。
   * `pipeline` 里的键会原样覆盖 `pipeline.js::DEFAULTS` 的同名项。
   */
  'game-economic': {
    cutAbs: 80,
    wakeGapSec: 20,
    pipeline: {
      integralSeconds: 30,
      maxFrames: 8,
      maxWaitSec: 60,
      silentFloorSec: 40,
      cutSpike: 1.5,
      cutSpikeAbs: 30,
      // 游戏画面每帧都在动：低分孤立 / 台阶 / mc 都会把运动误判成切点
      cutIsolatedAbs: 0,
      cutJumpAbs: 0,
      cutMcAbs: 0,
      floorGapK: 2,
      floorMin: 2,
      floorMax: 20,
    },
  },
});
export const DEFAULT_PACE = 'normal';
