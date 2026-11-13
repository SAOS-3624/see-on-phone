/**
 * storyboard 的档位会不会随 fps 漂？
 *
 * `tiers.js` 的 dt 是 **100 帧**、阈值是 **"每帧的切点概率"** —— 两者都是帧，
 * 而切点时刻是秒。所以同一批切点在不同 fps 下算出的密度不一样：
 *
 *   30fps: dt = 100/30 = 3.33 秒窗
 *   60fps: dt = 100/60 = 1.67 秒窗
 *
 * 窗短了，窗内切点数就少一半 → 密度低一半 → 档位掉。
 */
const { levelCurve } = await import(new URL('../../../../dsh-video-storyboard/src/storyboard/tiers.js', import.meta.url).href);

const DURATION = 60;

/** 造一批切点：每 `gap` 秒一刀。 */
function cutsEvery(gap) {
  const out = [];
  for (let t = gap; t < DURATION - 0.01; t += gap) out.push(+t.toFixed(3));
  return out;
}

function hist(levels) {
  const h = new Array(9).fill(0);
  for (const v of levels) h[v] += 1;
  return h.slice(1);
}

function summary(levels) {
  const h = hist(levels);
  const total = levels.length;
  const avg = levels.reduce((a, b) => a + b, 0) / total;
  const main = h.indexOf(Math.max(...h)) + 1;
  return { avg: +avg.toFixed(2), main, hist: h };
}

console.log(`素材：${DURATION} 秒，切点按"每 N 秒一刀"生成\n`);
console.log('每刀间隔  fps   平均档位  主档位   档位分布(1..8)');
console.log('--------------------------------------------------------------');

for (const gap of [1, 2, 3, 6]) {
  const cuts = cutsEvery(gap);
  for (const fps of [24, 30, 60]) {
    const r = levelCurve({ duration: DURATION, fps, cuts });          // 默认 timeNormalize=true
    const s = summary(r.levels);
    console.log(
      `${String(gap).padStart(6)} 秒  ${String(fps).padStart(3)}   ${String(s.avg).padStart(6)}   ${String(s.main).padStart(4)}    ${s.hist.join(' / ')}`
    );
  }
  console.log('--------------------------------------------------------------');
}
