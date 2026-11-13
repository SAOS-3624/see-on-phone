/**
 * storyboard 的积分假切点会不会随 fps 漂？
 *
 * `integralMarks` 的阈值是 `T = INTEGRAL_REF_SCORE(4) × fps × equivSeconds`，
 * 而 `acc` 累的是逐帧分。逐帧分与 fps 成反比（帧间隔越短差越小），
 * 所以 **Σscore 与 fps 无关** —— 那 T 就不该乘 fps。
 */
const { integralMarks } = await import(
  new URL('../../../../dsh-video-storyboard/src/storyboard/tiers.js', import.meta.url).href
);

const DURATION = 60;
/** 一段"中等强度"的画面：每秒累计 120 分（= REF_SCORE 4 × 30fps，或 10 × 12fps）。 */
const PER_SEC = 120;

function makeSeries(fps) {
  const n = Math.round(DURATION * fps);
  const per = PER_SEC / fps;              // 每帧分：帧越密分越小
  const out = [];
  for (let i = 0; i < n; i += 1) out.push({ t: i / fps, score: per });
  return out;
}

console.log(`素材：${DURATION} 秒，画面强度恒定（每秒累计 ${PER_SEC} 分）`);
console.log('若阈值对帧率无关，各 fps 的采样点数和间隔应该一致。\n');
console.log('fps   Σscore   阈值 T   采样点数   平均间隔(秒)');
console.log('----------------------------------------------------');

for (const fps of [12, 15, 24, 30, 60]) {
  const series = makeSeries(fps);
  const sum = series.reduce((a, s) => a + s.score, 0);
  const marks = integralMarks({ duration: DURATION, series, cuts: [], fps });
  const T = 4 * fps * 6;
  const gap = marks.length > 1 ? (marks[marks.length - 1] - marks[0]) / (marks.length - 1) : NaN;
  console.log(
    `${String(fps).padStart(3)}   ${String(Math.round(sum)).padStart(6)}   ${String(T).padStart(6)}   `
    + `${String(marks.length).padStart(8)}   ${Number.isFinite(gap) ? gap.toFixed(2) : '—'}`
  );
}
