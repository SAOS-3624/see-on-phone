/**
 * bin.js —— 找个可执行文件在哪。
 *
 * 原本寄生在 `cam/camera.js`（那个文件是摄像头后端，0.4.0 删了）。但 TTS 播放还要用它
 * 找 ffplay/mpv，所以单独拎出来 —— **删掉采集层，不该顺手把语音也弄坏**。
 */
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';

/**
 * `command -v <bin>`，找不到返回 null。
 *
 * 带超时：`command -v` 在某些 shell 配置下会去 sources 一大堆 rc 文件，实测能卡几秒。
 */
export function whichBin(bin, { timeoutMs = 2500 } = {}) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (!done) { done = true; resolve(v); } };
    let child;
    try {
      child = spawn('sh', ['-c', `command -v ${bin}`], { windowsHide: true });
    } catch {
      return finish(null);
    }
    let out = '';
    const timer = setTimeout(() => {
      try { child.kill('SIGKILL'); } catch { /* ignore */ }
      finish(null);
    }, timeoutMs);
    child.stdout.on('data', (s) => { out += s; });
    child.on('error', () => { clearTimeout(timer); finish(null); });
    child.on('close', () => {
      clearTimeout(timer);
      const p = out.trim().split('\n')[0];
      finish(p && existsSync(p) ? p : null);
    });
  });
}
