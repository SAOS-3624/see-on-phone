/**
 * 验证"帧率归一化"：同一段视频、同样的秒数，抽成 12fps 和 15fps 两批帧，
 * 出的图在**时间轴**上应该对得上（切点还是那些切点）。
 *
 * 三组对比：
 *   A. fps=12（基准）
 *   B. fps=15 + timeNormalizeFrames=true   ← 应该和 A 接近
 *   C. fps=15 + timeNormalizeFrames=false  ← 老口径，三处窗口变短
 */
import { readFileSync, writeFileSync } from 'node:fs';
const { createPipeline } = await import(new URL('../../../../dsh-see-now/src/pipeline.js', import.meta.url).href);

function run(rawPath, fps, width, height, timeNormalizeFrames) {
  const raw = readFileSync(rawPath);
  const frameSize = width * height * 3;
  const n = Math.floor(raw.length / frameSize);
  const p = createPipeline({ width, height, fps, queueDepth: 0, timeNormalizeFrames });
  const out = [];
  for (let i = 0; i < n; i += 1) {
    const buf = Buffer.from(raw.subarray(i * frameSize, (i + 1) * frameSize));
    p.push({ t: i / fps, buf });
    let s;
    while ((s = p.take())) out.push(`${s.from.toFixed(2)}~${s.to.toFixed(2)}(${s.frames.length}帧)`);
  }
  p.finish();
  let s;
  while ((s = p.take())) out.push(`${s.from.toFixed(2)}~${s.to.toFixed(2)}(${s.frames.length}帧)`);
  return { frames: n, sheets: out, st: p.stats };
}

const W = 240, H = 320;
const a = run('f12.raw', 12, W, H, true);
const b = run('f15.raw', 15, W, H, true);
const c = run('f15.raw', 15, W, H, false);

const fmt = (r) => `帧 ${r.frames} / 图 ${r.sheets.length} / 留帧 ${r.st.kept}（cut ${r.st.cuts} mark ${r.st.marks} floor ${r.st.floors}）`;
console.log('A fps=12 归一化开 :', fmt(a));
console.log('B fps=15 归一化开 :', fmt(b), '← 应接近 A');
console.log('C fps=15 归一化关 :', fmt(c), '← 老口径');
console.log();
console.log('A:', a.sheets.join(' '));
console.log('B:', b.sheets.join(' '));
console.log('C:', c.sheets.join(' '));
