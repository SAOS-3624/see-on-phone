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
import { createFrameReader } from './ffmpeg.js';

/** @typedef {'termux'|'mjpeg'|'rtsp'|'v4l2'|'avfoundation'|'dshow'|'file'|'testsrc'} Backend */

export const BACKENDS = Object.freeze([
  'termux', 'mjpeg', 'rtsp', 'v4l2', 'avfoundation', 'dshow', 'file', 'testsrc',
]);

/** 手机上真正能干活的（其余是桌面调试/回归用） */
export const MOBILE_BACKENDS = Object.freeze(['termux', 'mjpeg', 'rtsp']);

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
    grabIntervalMs = 220, cameraId = 0,
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

  async function start() {
    if (backend === 'termux') {
      const bin = (await whichTermuxCamera()) ?? findTermuxCamera();
      if (!bin) {
        throw Object.assign(
          new Error('没找到 termux-camera-photo。手机需要：Termux 里 `pkg install termux-api`，'
            + '并安装 Termux:API App，然后在系统设置里给 Termux 相机权限。'),
          { code: 'backend-unavailable' },
        );
      }
      reader = createFrameReader({
        ffmpegPath, args: captureArgs({ source, width, height, ffmpegPath }),
        width, height, label: 'termux', onFrame, onError, onExit,
      });
      reader.start();
      // 等 ffmpeg 把 mjpeg demuxer 初始化完再灌第一张，否则首张会被当探测数据吞掉
      await new Promise((r) => setTimeout(r, 120));
      const loop = () => {
        if (stopped) return;
        grabOnce(bin);
        grabTimer = setTimeout(loop, grabIntervalMs);
      };
      loop();
    } else {
      reader = createFrameReader({
        ffmpegPath, args: captureArgs({ source, width, height, ffmpegPath }),
        width, height, label: backend, onFrame, onError, onExit,
      });
      reader.start();
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
    return {
      backend, width, height, running: Boolean(reader?.running),
      frames: reader?.stats.frames ?? 0,
      fps: reader?.stats.fps ?? 0,
      firstFrameMs: reader?.stats.firstFrameMs ?? null,
      grab: { ...grabStats },
      stderrTail: reader?.stderrTail || null,
    };
  }

  return { start, stop, snapshot, get reader() { return reader; } };
}
