/**
 * 端到端冒烟：任意后端 → rgb24 帧 → 打分/切点 → 拼图 → PNG。
 * 不依赖手机，用 testsrc 合成源验证 vendored 引擎没坏。
 *
 *   node scripts/smoke.mjs [backend] [seconds]
 *   node scripts/smoke.mjs file 20 ../some.mp4
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createPipeline } from '../src/engine/pipeline.js';
import { openCamera } from '../src/cam/camera.js';

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, '..', '.tmp');
mkdirSync(outDir, { recursive: true });

const backend = process.argv[2] ?? 'testsrc';
const seconds = Number(process.argv[3] ?? 4);
const filePath = process.argv[4] ?? null;

// 手机竖屏：4:3 竖持（盲人导盲时手机通常竖着拿）
const W = 336;
const H = 448;

const pipeline = createPipeline({
  width: W, height: H, fps: 8,
  // 导盲先按"持续观察"跑：不做长时差，越新鲜越好
  queueDepth: 4,
});

const frames = [];
const cam = openCamera({
  backend,
  width: W, height: H, fps: 8,
  size: '640x480',
  path: filePath ?? undefined,
  onFrame: (f) => {
    frames.push(f);
    pipeline.push({ t: f.t, buf: f.buf });
  },
  onError: (e) => {
    console.error('[cam error]', e.message);
    if (e.code === 'backend-unavailable') process.exitCode = 2;
  },
  onExit: (info) => console.error('[cam exit]', info.code, info.stderrTail?.slice(-300)),
});

const info = await cam.start();
console.log('backend =', info.backend, `${info.width}x${info.height}`);

await new Promise((r) => setTimeout(r, seconds * 1000));
const { stats, sheets } = pipeline.finish();
await cam.stop();

console.log('frames in :', frames.length, ' fps =', cam.snapshot().fps);
console.log('stats     :', JSON.stringify(pipeline.stats));
console.log('sheets    :', sheets.length);

sheets.forEach((s, i) => {
  const p = join(outDir, `smoke-${String(i).padStart(2, '0')}.png`);
  writeFileSync(p, s.png);
  console.log(`  #${i} ${s.w}x${s.h} frames=${s.frames.length} png=${(s.png.length / 1024).toFixed(1)}KB -> ${p}`);
});
if (!sheets.length) console.log('⚠️  一张图都没出：检查 frames 是否为 0（采集没起来）');
