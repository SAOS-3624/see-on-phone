/**
 * ffmpeg 帧流读取。
 *
 * 契约：ffmpeg 往 stdout 吐 **rgb24 rawvideo**，本模块按固定字节数切帧回调。
 * 这是与 see-now（PC 版）完全一致的帧契约 —— 下游 `engine/pipeline.js` 一行不用改。
 *
 * 为什么不用 ffmpeg 的 `-f rawvideo` 直接 pipe 给插件别处：手机上唯一能拿到的是
 * JPEG（termux-camera-photo）或 MJPEG/RTSP 流，解码 + 缩放必须在 ffmpeg 里做
 * （手机 SoC 有 NEON/SIMD，纯 JS 解码 1080p 会烧穿 CPU）。
 */
import { spawn } from 'node:child_process';

/** 帧率统计用的滚动窗口（帧） */
const FPS_WINDOW = 24;

/**
 * @param {object} opts
 * @param {string} opts.ffmpegPath
 * @param {string[]} opts.args          ffmpeg 参数（不含 ffmpegPath 本身）
 * @param {number} opts.width
 * @param {number} opts.height
 * @param {(frame:{buf:Buffer,t:number,wall:number})=>void} opts.onFrame
 * @param {(err:Error & {stderrTail?:string})=>void} [opts.onError]
 * @param {(info:{code:number|null,signal:string|null,stderrTail:string})=>void} [opts.onExit]
 * @param {string} [opts.label]         诊断用的源标签
 */
export function createFrameReader({
  ffmpegPath = 'ffmpeg', args, width, height,
  onFrame, onError, onExit, label = 'camera',
}) {
  const chunkSize = width * height * 3;
  if (!Number.isInteger(chunkSize) || chunkSize <= 0) {
    throw new RangeError(`非法帧尺寸 ${width}x${height}`);
  }

  let child = null;
  let stopped = false;
  let stderrTail = '';
  const startedAt = Date.now();
  let frames = 0;
  let firstFrameAt = null;

  // 预分配 + 游标填充，避免每帧 alloc/copy（12fps × 1MB 会 GC 抖动）
  let scratch = Buffer.allocUnsafe(chunkSize);
  let fill = 0;

  // 帧到达速率（真实值，不是标称 fps）—— 手机上抓拍后端只有 2~4fps，
  // 下游要按真实速率判"这一帧隔了多久"
  const stamps = [];
  let measuredFps = 0;

  function emit(buf) {
    const now = Date.now();
    frames += 1;
    if (firstFrameAt === null) firstFrameAt = now;
    stamps.push(now);
    if (stamps.length > FPS_WINDOW) stamps.shift();
    if (stamps.length >= 4) {
      const span = (stamps[stamps.length - 1] - stamps[0]) / 1000;
      measuredFps = span > 0 ? +((stamps.length - 1) / span).toFixed(2) : 0;
    }
    // t 用**真实墙钟**而不是 frameIndex/fps：抓拍后端的间隔抖动很大
    // （termux-camera-photo 单次 150~500ms），用标称 fps 推算会让 pipeline
    // 的"间隔判据"整个失真。
    onFrame({ buf, t: +((now - startedAt) / 1000).toFixed(3), wall: now });
  }

  function onData(chunk) {
    let off = 0;
    while (off < chunk.length) {
      const room = chunkSize - fill;
      const take = Math.min(room, chunk.length - off);
      chunk.copy(scratch, fill, off, off + take);
      fill += take;
      off += take;
      if (fill === chunkSize) {
        emit(scratch.subarray(0, chunkSize));
        scratch = Buffer.allocUnsafe(chunkSize);
        fill = 0;
      }
    }
  }

  function start() {
    if (child) return child;
    stopped = false;
    child = spawn(ffmpegPath, args, { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });

    child.stdout.on('data', onData);

    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (s) => {
      // 只留尾部 2000 字：手机上任何一条错误都可能被刷屏，要的是最后那几行原因
      stderrTail = (stderrTail + s).slice(-2000);
    });

    child.on('error', (err) => {
      err.stderrTail = stderrTail;
      onError?.(err);
    });

    child.on('close', (code, signal) => {
      child = null;
      if (stopped) return;
      onExit?.({ code, signal, stderrTail });
    });

    return child;
  }

  function writeToStdin(buf) {
    if (!child?.stdin?.writable) return false;
    return child.stdin.write(buf);
  }

  function stop({ timeoutMs = 350 } = {}) {
    stopped = true;
    if (!child) return Promise.resolve();
    const c = child;
    child = null;
    // 先关 stdin 让 image2pipe 后端自然收尾，再兜底 SIGKILL。
    // **手机上不能等**：僵尸 ffmpeg 会一直占着摄像头，别的 App 打不开，
    // 系统还可能把相机指示灯一直点亮。350ms 内必须见到它死。
    try { c.stdin?.end(); } catch { /* 已关闭 */ }
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        try { c.kill('SIGKILL'); } catch { /* 已退出 */ }
        // kill 之后立刻 resolve，不等 close 事件：Windows 上孙进程的 close 常常不来
        resolve();
      }, timeoutMs);
      c.once('close', () => { clearTimeout(timer); resolve(); });
    });
  }

  return {
    start,
    stop,
    writeToStdin,
    get pid() { return child?.pid ?? null; },
    get running() { return child !== null && !stopped; },
    get stderrTail() { return stderrTail; },
    get stats() {
      return {
        label, frames, fps: measuredFps,
        firstFrameMs: firstFrameAt === null ? null : firstFrameAt - startedAt,
        width, height,
      };
    },
  };
}

/** ffmpeg 可用性 + 是否带某个 demuxer/device。手机上这决定后端能不能用。 */
export async function probeFfmpeg(ffmpegPath = 'ffmpeg', { timeoutMs = 4000 } = {}) {
  return new Promise((resolve) => {
    let out = '';
    let done = false;
    const finish = (r) => { if (!done) { done = true; resolve(r); } };
    let child;
    try {
      // **不加 `-hide_banner`**：版本号只在 banner 里，`-devices` 本身不打印它
      child = spawn(ffmpegPath, ['-devices'], { windowsHide: true });
    } catch (err) {
      return finish({ ok: false, error: 'spawn-failed', detail: err.message });
    }
    const timer = setTimeout(() => {
      try { child.kill('SIGKILL'); } catch { /* ignore */ }
      finish({ ok: false, error: 'timeout' });
    }, timeoutMs);
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (s) => { out += s; });
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (s) => { out += s; });
    child.on('error', (err) => {
      clearTimeout(timer);
      finish({ ok: false, error: err.code === 'ENOENT' ? 'not-found' : 'spawn-failed', detail: err.message });
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code !== 0 && !out) return finish({ ok: false, error: `exit-${code}` });
      const devices = [];
      // **只在 `Devices:` 之后解析**：banner 里的 `built with gcc 13.2.0`、
      // `libavcodec 61. 3.100` 这些行也是「空格 + 若干 token」，会被一起吃进来
      const body = out.slice(Math.max(0, out.indexOf('Devices:')));
      for (const line of body.split('\n')) {
        // **不要解析列位**：两列状态是 `D`/`E`/`.`/空格 混排（`D.` `DE` ` E` `D `），
        // 宽度还随 ffmpeg 版本变。只取「第一个 token 是状态位、第二个是设备名」这条规律。
        if (!/^\s+\S/.test(line)) continue;
        const parts = line.trim().split(/\s+/);
        const name = parts[1];
        if (!name || name === '=' || name === '---' || parts[0].startsWith('-')) continue;
        devices.push({ demux: parts[0].includes('D'), mux: parts[0].includes('E'), name });
      }
      finish({ ok: true, version: /ffmpeg version (\S+)/.exec(out)?.[1] ?? null, devices });
    });
  });
}
