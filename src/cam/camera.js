/**
 * 手机摄像头采集门面。
 *
 * 与 see-now（PC 版）的根本差别：PC 版有"整块屏幕"这个天然稳定的 12fps RGB 源；
 * 手机上这个源**不存在** —— Android 不允许普通进程读 /dev/video*，能拿到画面的
 * 只有两条路：
 *
 *   ① Termux:API 的 `termux-camera-photo`：一次一张 JPEG，150~500ms，2~4fps 上限
 *   ② 外部 App 开出来的流（IP Webcam 的 MJPEG / DroidCam 的 RTSP）：接近原生帧率
 *
 * 所以采集层做成**后端可插拔**，对外只暴露一种东西：rgb24 帧 + 真实墙钟时间戳。
 * 下游 pipeline 完全不用知道帧是从哪来的。
 */
import { spawn } from 'node:child_process';
import { existsSync, unlinkSync, readFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { request as httpRequest } from 'node:http';
import { createFrameReader } from './ffmpeg.js';

/** @typedef {'termux'|'mjpeg'|'rtsp'|'v4l2'|'avfoundation'|'dshow'|'file'|'testsrc'} Backend */

export const BACKENDS = Object.freeze([
  'termux', 'http-raw', 'http-jpg', 'mjpeg', 'rtsp', 'v4l2', 'avfoundation', 'dshow', 'file', 'testsrc',
]);

/** 手机上真正能干活的（其余是桌面调试/回归用） */
export const MOBILE_BACKENDS = Object.freeze(['http-raw', 'termux', 'http-jpg', 'mjpeg', 'rtsp']);

/**
 * 后端 → ffmpeg 输入参数。
 *
 * `pushMode: 'jpgpipe'` 表示"这条流由插件自己往 stdin 塞 JPEG"（termux 抓拍），
 * 其余后端是 ffmpeg 自己去拉流。
 */
export function resolveSource(backend, opts = {}, platform = process.platform) {
  const {
    fps = 8, device = null, url = null, path = null, size = null, transport = 'tcp',
  } = opts;

  switch (backend) {
    case 'termux':
      // image2pipe + mjpeg demuxer：连写多张 JPEG，ffmpeg 靠 SOI/EOI 自己切边界。
      // **不要**每抓一张就起一次 ffmpeg（进程启动 30~60ms，手机上更慢）。
      return {
        backend, pushMode: 'jpgpipe', live: true,
        inputArgs: ['-f', 'image2pipe', '-c:v', 'mjpeg', '-i', '-'],
      };

    case 'http-jpg': {
      // 拿 JPEG 再本地解码。**手机场景不推荐** —— dsh-go 的 PRoot 里通常没有 ffmpeg，
      // 而且 image2pipe 喂多张 JPEG 时 ffmpeg 会把"数据停顿"判成 EOF 提前退出。
      if (!url) throw new RangeError('http-jpg 后端需要 url（例：http://127.0.0.1:8123/jpg）');
      return {
        backend, pushMode: 'jpgpipe', live: true,
        inputArgs: ['-f', 'image2pipe', '-c:v', 'mjpeg', '-i', '-'],
      };
    }

    case 'http-raw': {
      // **手机上的默认后端**：Termux 侧（cam_server.py）抓拍 + 缩放 + 转 rgb24，
      // 这里只收字节。好处是插件侧零依赖 —— 不需要 ffmpeg，PRoot 里也能跑。
      if (!url) throw new RangeError('http-raw 后端需要 url（例：http://127.0.0.1:8123/frame）');
      return {
        backend, pushMode: 'raw', live: true, inputArgs: [],
      };
    }

    case 'mjpeg':
      if (!url) throw new RangeError('mjpeg 后端需要 url（例：http://192.168.1.5:8080/video）');
      return {
        backend, pushMode: 'stream', live: true,
        inputArgs: ['-f', 'mjpeg', '-i', String(url)],
      };

    case 'rtsp':
      if (!url) throw new RangeError('rtsp 后端需要 url（例：rtsp://192.168.1.5:8080/h264_ulaw.sdp）');
      return {
        backend, pushMode: 'stream', live: true,
        inputArgs: ['-rtsp_transport', transport, '-i', String(url)],
      };

    case 'v4l2': {
      const dev = device ?? '/dev/video0';
      // v4l2 的帧率要给在设备侧（-framerate 不认，用 -input_format + 滤镜节奏）
      return {
        backend, pushMode: 'stream', live: true,
        inputArgs: ['-f', 'v4l2', '-framerate', String(fps), '-i', dev],
      };
    }

    case 'avfoundation':
      if (platform !== 'darwin') throw new RangeError('avfoundation 只在 macOS 上可用');
      // mac 摄像头：`-framerate` 是设备侧采样率，必须给；音频写 none 免权限弹窗
      return {
        backend, pushMode: 'stream', live: true,
        inputArgs: ['-f', 'avfoundation', '-framerate', String(fps), '-i', `${device ?? 0}:none`],
      };

    case 'dshow':
      if (platform !== 'win32') throw new RangeError('dshow 只在 Windows 上可用');
      return {
        backend, pushMode: 'stream', live: true,
        // **不要加 -thread_queue_size**：部分官方 build 会把它判成输出选项直接报错
        inputArgs: ['-rtbufsize', '64M', '-f', 'dshow', '-i', `video=${device ?? 'HD Webcam'}`],
      };

    case 'file':
      if (!path) throw new RangeError('file 后端需要 path');
      return {
        backend, pushMode: 'stream', live: false,
        inputArgs: ['-re', '-i', String(path)],
      };

    case 'testsrc':
      // 合成源：手机上没装摄像头 / 桌面没摄像头时也能把整条管线跑通
      return {
        backend, pushMode: 'stream', live: true,
        inputArgs: ['-f', 'lavfi', '-i', `testsrc2=size=${size ?? '640x480'}:rate=${fps}`],
      };

    default:
      throw new RangeError(`unknown camera backend: ${backend}`);
  }
}

/**
 * 完整采集参数。
 *
 * 等比 cover 而不是拉伸：导盲看的是"前方障碍有多宽/多高"，拉伸会让形状失真，
 * 距离判断跟着错。所以先按覆盖比例放大再居中裁切。
 */
export function captureArgs({ source, width, height }) {
  const filters = [
    `scale=${width}:${height}:force_original_aspect_ratio=increase:flags=fast_bilinear`,
    `crop=${width}:${height}`,
  ];
  // 返回的是**参数列表**，不含可执行文件本身 —— 调用方 spawn(ffmpegPath, args)
  return [
    '-hide_banner', '-v', 'error', '-nostdin',
    // `-fflags nobuffer` 只对**直播式**输入有意义，而且它会把 `-re` 的文件回放直接堵死：
    // 加上它 + `-re`，8 秒一个字节都不出（dsh-see-now 实测过同一条）。
    ...(source.live ? ['-fflags', 'nobuffer'] : []),
    '-flags', 'low_delay',
    ...source.inputArgs,
    '-an', '-sn',
    '-vf', filters.join(','),
    '-pix_fmt', 'rgb24', '-f', 'rawvideo', '-',
  ];
}

const TERMUX_BIN = '/data/data/com.termux/files/usr/bin/camera-photo';

/** Termux:API 的可执行文件在哪。PRoot 里路径可能被 bind 到不同位置，都试一遍。 */
export function findTermuxCamera(explicitPath = null) {
  const candidates = [
    explicitPath,
    process.env.DSH_TERMUX_CAMERA,
    'termux-camera-photo',
    TERMUX_BIN,
    '/usr/bin/termux-camera-photo',
  ].filter(Boolean);
  for (const c of candidates) {
    if (c === 'termux-camera-photo') continue; // 交给 PATH 解析，见下方 which
    if (existsSync(c)) return c;
  }
  return null;
}

/**
 * 用 `command -v` 式的探测（不依赖 existsSync，PATH 里的也能找到）。
 * 手机上这步失败 = 后端不可用，要把话说明白，不能让用户看到 "ffmpeg exit 1"。
 */
export function whichTermuxCamera(bin = 'termux-camera-photo', { timeoutMs = 2500 } = {}) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (!done) { done = true; resolve(v); } };
    let child;
    try {
      child = spawn('sh', ['-c', `command -v ${bin}`], { windowsHide: true });
    } catch { return finish(null); }
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

/** 通用 `which`（tts.js 也用它找 termux-tts-speak）。 */
export const whichBin = whichTermuxCamera;

/**
 * 摄像头会话。统一返回 rgb24 帧。
 *
 * @param {object} opts
 * @param {Backend} [opts.backend]  不传则自动挑（手机优先 termux → mjpeg → rtsp）
 * @param {number} [opts.width]
 * @param {number} [opts.height]
 * @param {number} [opts.fps]       仅对支持设备侧定帧率的后端生效
 * @param {number} [opts.grabIntervalMs] termux 抓拍间隔
 * @param {number} [opts.cameraId]  termux 摄像头 id（0 后置 / 1 前置）
 * @param {(f:{buf:Buffer,t:number,wall:number})=>void} opts.onFrame
 */
export function openCamera(opts) {
  const {
    backend: wantBackend = null,
    width = 448, height = 336, fps = 8,
    grabIntervalMs = 70, cameraId = 0,
    url = null,
    ffmpegPath = 'ffmpeg',
    onFrame, onError, onExit,
  } = opts;

  const backend = wantBackend ?? 'termux';
  const source = resolveSource(backend, { ...opts, fps });

  let reader = null;
  let grabTimer = null;
  let grabChild = null;
  let stopped = false;
  let grabPath = null;
  let dir = null;
  let grabFails = 0;
  const grabStats = { shots: 0, fails: 0, bytes: 0 };

  function ensureDir() {
    if (dir) return dir;
    dir = join(tmpdir(), 'dsh-see-phone');
    try { mkdirSync(dir, { recursive: true }); } catch { dir = tmpdir(); }
    return dir;
  }

  /**
   * 抓一张 → 塞进 ffmpeg stdin。
   *
   * **串行**：上一张没结束不抓下一张。termux-camera-photo 走的是 Android camera2
   * 的 open/close，两个并发调用会互相抢摄像头，结果是两张都超时。
   */
  function grabOnce(bin) {
    if (stopped || grabChild) return;
    ensureDir();
    grabPath = grabPath ?? join(dir, 'frame.jpg');
    grabChild = spawn(bin, ['-c', String(cameraId), grabPath], { windowsHide: true });
    let err = '';
    grabChild.stderr.on('data', (s) => { err += s; });
    const finish = () => {
      grabChild = null;
      if (stopped) return;
      try {
        const buf = readFileSync(grabPath);
        if (buf.length < 512) throw new Error('empty');
        grabStats.shots += 1;
        grabStats.bytes += buf.length;
        reader?.writeToStdin(buf);
        grabFails = 0;
      } catch (e) {
        grabStats.fails += 1;
        grabFails += 1;
        // 连续 5 次拿不到画面基本是权限没给 / 摄像头被别的 App 占着，
        // 这时候继续空转只会更烫，直接把原因抛上去
        if (grabFails === 5) {
          onError?.(Object.assign(
            new Error(`termux-camera-photo 连续 5 次没拿到画面：${err.trim() || e.message}`),
            { code: 'camera-unavailable' },
          ));
        }
      }
    };
    grabChild.on('error', (e) => {
      grabChild = null;
      grabStats.fails += 1;
      onError?.(Object.assign(new Error(`termux-camera-photo 起不来：${e.message}`), { code: 'ENOENT' }));
    });
    grabChild.on('close', finish);
  }

  /**
   * HTTP 抓一张 → 塞进 ffmpeg stdin。
   *
   * 服务端是 Termux 里的一个 python 服务：每次 GET 现抓一张 JPEG 返回
   * （见 `phone/cam_server.py`）。**串行**：服务端背后是 camera2 的
   * open/close，并发请求只会让两张都超时。
   */
  let httpBusy = false;
  function grabHttp() {
    if (stopped || httpBusy || !url) return;
    httpBusy = true;
    const done = (ok, bytes) => {
      httpBusy = false;
      if (ok) {
        grabStats.shots += 1;
        grabStats.bytes += bytes;
        grabFails = 0;
      } else {
        grabStats.fails += 1;
        grabFails += 1;
        if (grabFails === 5) {
          onError?.(Object.assign(
            new Error(`连续 5 次取不到画面：${url} 没响应。检查 Termux 里的 cam_server.py 是否在跑。`),
            { code: 'camera-unavailable' },
          ));
        }
      }
    };
    const req = httpRequest(url, { method: 'GET', timeout: 10000 }, (res) => {
      if (res.statusCode !== 200) { res.resume(); done(false, 0); return; }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const buf = Buffer.concat(chunks);
        if (buf.length < 512) { done(false, 0); return; }
        reader?.writeToStdin(buf);
        done(true, buf.length);
      });
      res.on('error', () => done(false, 0));
    });
    req.on('error', () => done(false, 0));
    req.on('timeout', () => { req.destroy(); done(false, 0); });
    req.end();
  }

  /** `http-raw`：直接收 rgb24，不经过 ffmpeg。 */
  const rawStats = { frames: 0, fails: 0, bytes: 0 };
  let rawBusy = false;
  let rawStartedAt = 0;

  function grabRaw() {
    if (stopped || rawBusy || !url) return;
    rawBusy = true;
    const q = url.includes('?') ? '&' : '?';
    const full = `${url}${q}w=${width}&h=${height}`;
    const fail = () => { rawBusy = false; rawStats.fails += 1; };
    const req = httpRequest(full, { method: 'GET', timeout: 15000 }, (res) => {
      if (res.statusCode !== 200) { res.resume(); fail(); return; }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        rawBusy = false;
        const buf = Buffer.concat(chunks);
        // 长度必须**正好**是 w*h*3：短了会被下游当成错位的帧，
        // 画面上表现为斜条纹，比直接丢弃难查得多
        if (buf.length !== width * height * 3) { rawStats.fails += 1; return; }
        rawStats.frames += 1;
        rawStats.bytes += buf.length;
        const now = Date.now();
        onFrame({ buf, t: +((now - rawStartedAt) / 1000).toFixed(3), wall: now });
      });
      res.on('error', fail);
    });
    req.on('error', fail);
    req.on('timeout', () => { req.destroy(); fail(); });
    req.end();
  }

  async function start() {
    const piped = source.pushMode === 'jpgpipe';
    const raw = source.pushMode === 'raw';

    if (!raw) {
      reader = createFrameReader({
        ffmpegPath, args: captureArgs({ source, width, height }),
        width, height, label: backend, onFrame, onError, onExit,
      });
      reader.start();
      // 等 ffmpeg 把 mjpeg demuxer 初始化完再灌第一张，否则首张会被当探测数据吞掉
      if (piped) await new Promise((r) => setTimeout(r, 120));
    } else {
      rawStartedAt = Date.now();
    }

    if (backend === 'termux') {
      const bin = (await whichTermuxCamera()) ?? findTermuxCamera();
      if (!bin) {
        await reader?.stop();
        reader = null;
        throw Object.assign(
          new Error('没找到 termux-camera-photo。手机需要：Termux 里 `pkg install termux-api`，'
            + '并安装 Termux:API App，然后在系统设置里给 Termux 相机权限。'),
          { code: 'backend-unavailable' },
        );
      }
      const loop = () => {
        if (stopped) return;
        grabOnce(bin);
        grabTimer = setTimeout(loop, grabIntervalMs);
      };
      loop();
    } else if (backend === 'http-jpg') {
      const loop = () => {
        if (stopped) return;
        grabHttp();
        grabTimer = setTimeout(loop, grabIntervalMs);
      };
      loop();
    } else if (backend === 'http-raw') {
      const loop = () => {
        if (stopped) return;
        grabRaw();
        grabTimer = setTimeout(loop, grabIntervalMs);
      };
      loop();
    }

    return { backend, width, height };
  }

  async function stop() {
    stopped = true;
    if (grabTimer) { clearTimeout(grabTimer); grabTimer = null; }
    if (grabChild) { try { grabChild.kill('SIGKILL'); } catch { /* ignore */ } grabChild = null; }
    await reader?.stop();
    if (grabPath) { try { unlinkSync(grabPath); } catch { /* ignore */ } }
  }

  function snapshot() {
    const isRaw = source.pushMode === 'raw';
    const elapsed = rawStartedAt ? (Date.now() - rawStartedAt) / 1000 : 0;
    return {
      backend, width, height,
      running: isRaw ? (!stopped && rawStartedAt > 0) : Boolean(reader?.running),
      frames: isRaw ? rawStats.frames : (reader?.stats.frames ?? 0),
      fps: isRaw
        ? (elapsed > 0 ? +(rawStats.frames / elapsed).toFixed(2) : 0)
        : (reader?.stats.fps ?? 0),
      firstFrameMs: isRaw ? null : (reader?.stats.firstFrameMs ?? null),
      grab: isRaw
        ? { shots: rawStats.frames, fails: rawStats.fails, bytes: rawStats.bytes }
        : { ...grabStats },
      stderrTail: reader?.stderrTail || null,
    };
  }

  return { start, stop, snapshot, get reader() { return reader; } };
}
