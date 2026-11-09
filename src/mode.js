/**
 * mode.js — "这张图是给谁看的"。
 *
 * 同一个 `see_phone`，两种读者，别混：
 *
 *   - `normal`（默认）：用户**自己能看见**画面（手机预览、或者 PC 上当作摄像头用）。
 *     图是给他自己确认 / 一起讨论的辅助，回答按正常会话说话 —— 该说细节说细节，
 *     宫格按时间线摊开就是"刚才发生了什么"。
 *   - `guide`：用户**看不见**，图只有模型一个读者，模型说什么他就信什么。
 *     话要能直接念出来：先风险、再通路、最后动作，一句到两句。
 *
 * 这个区分不是文案问题，是**默认值问题**。0.2.x 把导盲当成唯一产品定位写进了工具描述，
 * 于是"看一眼"这种中性请求也会被模型接成"你现在正前方有台阶" —— 用户报的就是这个。
 *
 * 模式来源有优先级（见 `resolveMode`）：显式参数 > App 写的开关文件 > 配置文件 > normal。
 */

import { readFileSync } from 'node:fs';

/** App 侧「正常 / 导盲」开关的落点（`EyeService.MODE_FILE`）。 */
export const MODE_FILE = '/sdcard/Download/DSH-Go/mode.txt';

/** 没配置、认不出、文件读不到时用它。**默认必须是 normal。** */
export const DEFAULT_MODE = 'normal';

export const MODES = Object.freeze(['normal', 'guide']);

/**
 * 各种写法归一到两个值。`general` 是 0.2.x 的旧名字，必须继续认 ——
 * 存量配置、存量模型习惯里都是它。
 */
const ALIAS = Object.freeze({
  normal: 'normal',
  general: 'normal',
  default: 'normal',
  pc: 'normal',
  screen: 'normal',
  guide: 'guide',
  blind: 'guide',
  accessibility: 'guide',
  a11y: 'guide',
});

/** @returns {'normal'|'guide'|null} 认不出就给 null（调用方决定回退到哪一级） */
export function normalizeMode(value) {
  if (value == null) return null;
  const key = String(value).trim().toLowerCase();
  return ALIAS[key] ?? null;
}

/**
 * 读 App 写的那一行。**每次调用都读**：文件只有几个字节，比维护 mtime 缓存更不容易出错，
 * 而工具调用本来就是秒级的。
 *
 * @returns {'normal'|'guide'|null} 没装 App、没权限、内容不认识 → null（当作"没表态"）
 */
export function readAppMode(file = MODE_FILE) {
  try {
    return normalizeMode(readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

/**
 * 三级回退。返回 `source` 是为了排障：用户说"怎么又像导盲了"时，
 * `status` 里能直接看到这模式是谁定的。
 *
 * @param {{arg?:string, app?:string|null, cfg?:string}} p
 * @returns {{mode:'normal'|'guide', source:'arg'|'app'|'config'|'default'}}
 */
export function resolveMode({ arg, app, cfg } = {}) {
  const a = normalizeMode(arg);
  if (a) return { mode: a, source: 'arg' };
  const b = normalizeMode(app);
  if (b) return { mode: b, source: 'app' };
  const c = normalizeMode(cfg);
  if (c) return { mode: c, source: 'config' };
  return { mode: DEFAULT_MODE, source: 'default' };
}

/**
 * 两种模式的默认画布（3:4，与手机原始画面同比例，零裁切）。
 *
 * 为什么正常模式用更大的：token 是**阶梯 + 地板**计费，336×448 和 420×560 都小于
 * 544²，计费时被放大到同一个网格 —— 实测两者都是 197 token。同样的钱，多 56% 像素。
 * 导盲模式不动，是因为它只出单帧、手机上每帧都要在 JS 里缩放打分，省 CPU 比省像素值。
 */
export const MODE_SIZES = Object.freeze({
  normal: Object.freeze({ width: 420, height: 560 }),
  guide: Object.freeze({ width: 336, height: 448 }),
});

/** 配置里显式写了 width/height 就以配置为准（老用户的自定义尺寸不能被模式覆盖）。 */
export function sizesFor(mode, cfg = {}) {
  const w = Number(cfg.width);
  const h = Number(cfg.height);
  if (Number.isFinite(w) && w > 0 && Number.isFinite(h) && h > 0) {
    return { width: Math.round(w), height: Math.round(h) };
  }
  return MODE_SIZES[mode] ?? MODE_SIZES[DEFAULT_MODE];
}

/** 一句话（给 status / 日志）。 */
export function describeMode(mode) {
  return mode === 'guide'
    ? 'guide（导盲：用户看不见，只给结论和动作）'
    : 'normal（正常：用户自己看得见，按普通会话说）';
}
