/**
 * `http-mjpeg` 后端：插件自己直连 IP Camera App 的 MJPEG 流。
 *
 * 为什么要有它：早期版本把「拉流 → 解码 → rgb24」放在 Termux 的一个 python
 * 进程里（外加 ffmpeg 子进程 + watchdog）。华为锁屏会把这几个后台进程一次性
 * 全清掉，整条链就断。现在把解码器 vendor 进插件（jpeg-js，BSD-3-Clause，
 * 40KB，纯 JS），采集链只剩 IP Camera 一个外部程序，插件跟着 dsh-go 活。
 *
 * 帧从字节流里按 SOI/EOI 抠：JPEG 熵编码里 0xFF 会转义成 0xFF00，
 * 所以 0xFFD9 只可能是真的帧结束符，不用解析 multipart boundary。
 */
import http from 'node:http';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { decode } = require('./vendor/jpeg-js/index.js');

const SOI = Buffer.from([0xff, 0xd8, 0xff]);
const EOI = Buffer.from([0xff, 0xd9]);

/** 等比 cover 缩放（最近邻）。输出正好 W*H*3 字节。 */
function coverToRgb(rgb, sw, sh, W, H) {
  const scale = Math.max(W / sw, H / sh);
  const nw = Math.max(W, Math.round(sw * scale));
  const nh = Math.max(H, Math.round(sh * scale));
  const left = (nw - W) >> 1;
  const top = (nh - H) >> 1;
  const out = Buffer.allocUnsafe(W * H * 3);
  let di = 0;
  for (let y = 0; y < H; y += 1) {
    const sy = Math.min(sh - 1, Math.floor((y + top) / scale));
    const row = sy * sw * 3;
    for (let x = 0; x < W; x += 1) {
      const sx = Math.min(sw - 1, Math.floor((x + left) / scale));
      const si = row + sx * 3;
      out[di] = rgb[si];
      out[di + 1] = rgb[si + 1];
      out[di + 2] = rgb[si + 2];
      di += 3;
    }
  }
  return out;
}

/**
 * IP Camera App 的控制端点：`GET /?camera=back&rotate=90`。
 *
 * App 支持一批 GET 参数（`camera` / `rotate` / `resolution` / `torch` /
 * `exposure` / `contrast` / `mirror` …），改完**持久化**在 App 里。
 *
 * 为什么必须下发 rotate：摄像头 sensor 的成像相对手机自然方向转了 90°
 * （华为 nova 11 实测），不转的话：
 *   - 600×800 的竖构图会被当成 800×600 横图，`cover` 缩放要横向裁掉 44% 画面；
 *   - 模型描述的"左/右/前"全部错位，导盲直接失效。
 * 转 90° 之后源是 600×800 = 3:4，和 336×448 完全同比例，`cover` 零裁切。
 */
function controlEndpoint(url) {
  try {
    const u = new URL(url);
    return `${u.protocol}//${u.host}/`;
  } catch {
    return null;
  }
}

export function createMjpegReader({
  url, width, height, intervalMs = 70, preset = null, onFrame, onError,
}) {
  let stopped = false;
  let buf = Buffer.alloc(0);
  let req = null;
  let retryTimer = null;
  let lastAt = 0;
  let startedAt = 0;
  const stats = {
    frames: 0, fails: 0, ms: 0, bytes: 0, presetOk: null, presetTried: null,
  };
  let warned = false;

  function scheduleReconnect() {
    if (stopped || retryTimer) return;
    retryTimer = setTimeout(() => { retryTimer = null; connect(); }, 500);
  }

  /**
   * 把 preset 下发给 App。失败**不阻塞**取流 —— rotate 已经在 App 里持久化过，
   * 第二次启动就算这一步失败，画面照样是正的。
   */
  function applyPreset() {
    const base = controlEndpoint(url);
    if (!base || !preset) return Promise.resolve(null);
    const qs = Object.entries(preset)
      .filter(([, v]) => v !== null && v !== undefined && v !== '')
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
      .join('&');
    if (!qs) return Promise.resolve(null);
    stats.presetTried = qs;
    return new Promise((resolve) => {
      let done = false;
      const finish = (v) => {
        if (done) return;
        done = true;
        stats.presetOk = v;
        resolve(v);
      };
      let r = null;
      try {
        r = http.get(`${base}?${qs}`, { timeout: 4000 }, (res) => {
          res.resume();
          res.on('end', () => finish(res.statusCode < 400));
          res.on('error', () => finish(false));
        });
      } catch {
        return finish(false);
      }
      r.on('error', () => finish(false));
      r.on('timeout', () => { r.destroy(); finish(false); });
    });
  }

  function publish(jpg) {
    const now = Date.now();
    // 节流：源可能 20fps，但插件只按 grabIntervalMs 的节奏吃帧，
    // 纯 JS 解码没必要把每个 JPEG 都解一遍。
    if (now - lastAt < intervalMs) return;
    lastAt = now;
    try {
      const t0 = process.hrtime.bigint();
      const img = decode(jpg, { useTArray: true, formatAsRGBA: false });
      const rgb = coverToRgb(img.data, img.width, img.height, width, height);
      stats.ms = Number(process.hrtime.bigint() - t0) / 1e6;
      stats.frames += 1;
      stats.bytes += jpg.length;
      onFrame({
        buf: rgb,
        t: +((now - startedAt) / 1000).toFixed(3),
        wall: now,
      });
    } catch {
      stats.fails += 1;
    }
  }

  function onData(chunk) {
    buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
    // 防止异常流把内存吃光
    if (buf.length > 8 * 1024 * 1024) buf = buf.subarray(buf.length - 3);
    for (;;) {
      const i = buf.indexOf(SOI);
      if (i < 0) {
        buf = buf.length > 3 ? Buffer.from(buf.subarray(buf.length - 3)) : Buffer.alloc(0);
        return;
      }
      const j = buf.indexOf(EOI, i + 3);
      if (j < 0) {
        buf = Buffer.from(buf.subarray(i));
        return;
      }
      const jpg = Buffer.from(buf.subarray(i, j + 2));
      buf = buf.subarray(j + 2);
      publish(jpg);
    }
  }

  function connect() {
    if (stopped) return;
    try {
      // 6s 不是随便定的：IP Camera 有帧时首帧 ~1.6s 就到；没帧时（App 停了 /
      // 锁屏后停推）socket 建起来但一个字节都不来，15s 会把这个动作整个卡死。
      req = http.get(url, { timeout: 6000 }, (res) => {
        if (res.statusCode !== 200) {
          res.resume();
          scheduleReconnect();
          return;
        }
        res.on('data', onData);
        res.on('end', scheduleReconnect);
        res.on('error', scheduleReconnect);
      });
      req.on('error', () => {
        stats.fails += 1;
        if (!warned && stats.fails >= 3) {
          warned = true;
          onError?.(Object.assign(
            new Error(`连不上 ${url}：把 Android IP Camera App 打开并留在前台。`),
            { code: 'camera-unavailable' },
          ));
        }
        scheduleReconnect();
      });
      req.on('timeout', () => { req.destroy(); scheduleReconnect(); });
    } catch (e) {
      stats.fails += 1;
      onError?.(e);
      scheduleReconnect();
    }
  }

  async function start() {
    startedAt = Date.now();
    // 先下发 rotate/camera，再取流 —— 首帧就是正的，不用等第二次调用
    if (preset) await applyPreset();
    connect();
  }

  function stop() {
    stopped = true;
    if (retryTimer) { clearTimeout(retryTimer); retryTimer = null; }
    if (req) { try { req.destroy(); } catch { /* ignore */ } req = null; }
  }

  function snapshot() {
    const elapsed = startedAt ? (Date.now() - startedAt) / 1000 : 0;
    return {
      running: !stopped,
      frames: stats.frames,
      fps: elapsed > 0 ? +(stats.frames / elapsed).toFixed(2) : 0,
      decodeMs: +stats.ms.toFixed(1),
      grab: { shots: stats.frames, fails: stats.fails, bytes: stats.bytes },
      preset: { tried: stats.presetTried, ok: stats.presetOk },
    };
  }

  return { start, stop, snapshot };
}
