/**
 * 同上的对照：把 dt / 平滑窗 / 阈值三样都换算成"秒"之后，fps 还有没有影响。
 *
 *   integralFrames = 100 / 30 × fps      （保持 3.33 秒窗）
 *   smoothFrames   =  50 / 30 × fps      （保持 1.67 秒窗）
 *   threshold[i]   = 原表[i] × 30 / fps  （窗宽帧数变多了，同样的"刀/秒"对应的概率要变小）
 */
const { levelCurve, LEVEL_THRESHOLDS } = await import(
  new URL('../../../../dsh-video-storyboard/src/storyboard/tiers.js', import.meta.url).href
);

const DURATION = 60;
const BASE_FPS = 30;

const cutsEvery = (gap) => {
  const out = [];
  for (let t = gap; t < DURATION - 0.01; t += gap) out.push(+t.toFixed(3));
  return out;
};
const summary = (levels) => {
  const h = new Array(9).fill(0);
  for (const v of levels) h[v] += 1;
  const avg = levels.reduce((a, b) => a + b, 0) / levels.length;
  const main = h.slice(1).indexOf(Math.max(...h.slice(1))) + 1;
  return { avg: +avg.toFixed(2), main, hist: h.slice(1) };
};

console.log(`素材：${DURATION} 秒。基准 30fps，折算 = 帧数/30×fps\n`);
console.log('每刀间隔  fps   现状(平均/主档)   归一化后(平均/主档)');
console.log('----------------------------------------------------------------');

for (const gap of [1, 2, 3, 6]) {
  const cuts = cutsEvery(gap);
  const base = summary(levelCurve({ duration: DURATION, fps: BASE_FPS, cuts }).levels);
  for (const fps of [24, 30, 60, 120]) {
    const raw = summary(levelCurve({ duration: DURATION, fps, cuts }).levels);
    const norm = summary(levelCurve({
      duration: DURATION,
      fps,
      cuts,
      integralFrames: Math.round((100 / BASE_FPS) * fps),
      smoothFrames: Math.round((50 / BASE_FPS) * fps),
      thresholds: LEVEL_THRESHOLDS.map((v) => (v * BASE_FPS) / fps),
    }).levels);
    const mark = norm.main === base.main && Math.abs(norm.avg - base.avg) < 0.15 ? ' ✓' : '';
    console.log(
      `${String(gap).padStart(6)} 秒  ${String(fps).padStart(3)}   `
      + `${String(raw.avg).padStart(5)} / ${raw.main}      `
      + `${String(norm.avg).padStart(5)} / ${norm.main}${mark}`
    );
  }
  console.log(`  （30fps 基准：${base.avg} / ${base.main} 档）`);
  console.log('----------------------------------------------------------------');
}
