/**
 * 手机 TTS。
 *
 * 导盲场景里"说了但用户没听到"等于没说 —— 模型自己的回复文本在手机上不会自动朗读，
 * 所以 `say` 必须真的走一次系统语音。
 *
 * 手机上唯一不装额外软件就能用的通道是 Termux:API 的 `termux-tts-speak`。
 * 拿不到就**明确报告**，不要静默假装说了（用户会以为四周安全）。
 */
import { spawn } from 'node:child_process';
import { whichBin } from './cam/camera.js';

let binCache;
let speech = null;   // 上一次朗读的进程，新的一句来了先掐掉（导盲不能排长队）
let lastSpokenAt = 0;

/** `which` 的通用版：只找 PATH 里真的存在的那一个。 */
async function which(cmd) {
  return whichBin(cmd);
}

export async function resolveTts(explicit = null) {
  if (binCache !== undefined) return binCache;
  const cand = explicit ?? process.env.DSH_TTS_BIN ?? 'termux-tts-speak';
  binCache = (await which(cand))
    ?? (await which('termux-tts-speak'))
    ?? null;
  return binCache;
}

/**
 * 朗读一句话。
 *
 * **打断优先**：上一句还在念就杀掉它。导盲最怕的是一句话排在一句话后面 ——
 * "前面有台阶"晚 3 秒到达，人已经迈出去了。
 *
 * @returns {Promise<{ok:boolean, queued?:boolean, reason?:string}>}
 */
export async function speak(text, { rate = 1.15, flush = true } = {}) {
  const s = String(text ?? '').trim();
  if (!s) return { ok: false, reason: 'empty' };
  const bin = await resolveTts();
  if (!bin) return { ok: false, reason: 'no-tts' };

  if (flush && speech) {
    try { speech.kill('SIGKILL'); } catch { /* 已结束 */ }
    speech = null;
  }

  lastSpokenAt = Date.now();
  const child = spawn(bin, ['-r', String(rate), s.slice(0, 500)], { windowsHide: true });
  speech = child;
  child.on('error', () => { speech = null; });
  child.on('close', () => { if (speech === child) speech = null; });
  return { ok: true };
}

export function ttsState() {
  return { available: Boolean(binCache), bin: binCache ?? null, lastSpokenAt };
}

export function stopSpeaking() {
  if (speech) { try { speech.kill('SIGKILL'); } catch { /* ignore */ } speech = null; }
}
