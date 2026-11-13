/**
 * phone.js —— 跟手机 App 说话的那一层。
 *
 * 0.4.0 起，这个插件**不再自己抽帧拼图**：手机 App 里已经有同一套引擎（Java 版，
 * 逐位对拍过 0 差异），而且图就在手机里、带宽也在手机里，让它拼完一次拿回来更省。
 *
 * 所以电脑这边只剩三件事：**发请求、收图、交给宿主**。
 *
 * 之前电脑要拉原始 MJPEG 流、起本地 ffmpeg 抽帧、跑 4137 行引擎挑帧拼图 ——
 * 那套现在全删了，代价是你电脑上不用再装 ffmpeg。
 */

import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const DEFAULT_TIMEOUT_MS = 30_000;

/**
 * 读配置：`~/.dsh/see-phone.json`，环境变量 `DSH_SEE_PHONE_CONFIG` 优先。
 *
 * 放在这里而不是 index.js，是因为**脚本也要读**（smoke/doctor）——
 * 一份实现，别让它们各抄一段然后哪天走偏。
 */
export function loadPhoneConfig() {
  const inline = process.env.DSH_SEE_PHONE_CONFIG;
  if (inline) {
    try { return JSON.parse(inline) ?? {}; } catch { /* 写坏了就当没写 */ }
  }
  try {
    return JSON.parse(readFileSync(join(homedir(), '.dsh', 'see-phone.json'), 'utf8')) ?? {};
  } catch {
    return {};
  }
}

/** 手机那头回的错误，统一包装成一句人话。 */
export class PhoneError extends Error {
  constructor(message, { status = 0, url = '' } = {}) {
    super(message);
    this.name = 'PhoneError';
    this.status = status;
    this.url = url;
  }
}

/** `http://127.0.0.1:4444/video/mjpeg` → `http://127.0.0.1:4444` */
export function baseOf(url) {
  const u = String(url ?? '').trim() || 'http://127.0.0.1:4444/video/mjpeg';
  try {
    const parsed = new URL(u);
    return `${parsed.protocol}//${parsed.host}`;
  } catch {
    return 'http://127.0.0.1:4444';
  }
}

async function getJson(base, path, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const url = `${base}${path}`;
  let res;
  try {
    res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  } catch (err) {
    throw new PhoneError(
      `连不上手机（${base}）：${err?.message ?? err}。`
      + '检查三件事：手机连着、App 在跑、`adb forward tcp:4444 tcp:4444` 没断。',
      { url },
    );
  }
  const text = await res.text();
  if (!res.ok) {
    throw new PhoneError(`手机回 ${res.status}：${text.slice(0, 200)}`, { status: res.status, url });
  }
  try {
    return JSON.parse(text);
  } catch {
    throw new PhoneError(`手机回的不是 JSON：${text.slice(0, 200)}`, { status: res.status, url });
  }
}

/**
 * 让手机拍一张（它自己拼好的宫格）。
 *
 * @param {string} base  例如 http://127.0.0.1:4444
 * @param {number} seconds 手机上采集多久（1~8 秒；越久越可能有动作）
 * @returns {Promise<{bytes: Buffer, mediaType: string, frames: number, tokens: number,
 *                    why: string, canvas: {w:number,h:number}|null}>}
 */
export async function lookSheet(base, seconds) {
  const sec = Math.min(Math.max(Number(seconds) || 2.5, 0.5), 8);
  const data = await getJson(base, `/look?seconds=${sec}`, 40_000);
  const first = Array.isArray(data?.images) ? data.images[0] : null;
  if (!first || typeof first !== 'string') {
    throw new PhoneError('手机没有返回图（/look 的 images 是空的）');
  }
  return {
    bytes: Buffer.from(first, 'base64'),
    mediaType: 'image/jpeg',
    frames: Number(data.frames) || 0,
    tokens: Number(data.tokens) || 0,
    why: String(data.why ?? ''),
    canvas: data.canvas ?? null,
  };
}

/** 手机的状态（摄像头开没开、什么档、多少条会话）。 */
export async function status(base) {
  return getJson(base, '/status', 8000);
}

/** 让手机念一句（它自己那边有音色）。 */
export async function say(base, text) {
  const t = String(text ?? '').trim().slice(0, 400);
  if (!t) return null;
  const data = await getJson(base, `/say?text=${encodeURIComponent(t)}`, 20_000);
  return data;
}
