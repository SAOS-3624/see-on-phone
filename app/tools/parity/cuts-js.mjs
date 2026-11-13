/**
 * 对拍用：用 dsh-see-now 的 cuts.js 跑同一批 rgb24 帧，输出与 CutsCli 同格式。
 * 参数必须跟 CutsCli 里写的一模一样（DEFAULTS 口径）。
 */
import { readFileSync } from 'node:fs';
const { createScorer } = await import(new URL('../../../../dsh-see-now/src/cuts.js', import.meta.url).href);

const [rawPath, wArg, hArg, fpsArg] = process.argv.slice(2);
const width = Number(wArg);
const height = Number(hArg);
const fps = Number(fpsArg);

const raw = readFileSync(rawPath);
const frameSize = width * height * 3;
const n = Math.floor(raw.length / frameSize);

const score = createScorer({
  width, height,
  scoreMode: 'abs',
  mcTau: 15, mcRadius: 2,
  gateMcRadius: 4,
  bgWindow: 6,
  bgStableRange: 24,
  bgTau: 15,
  bgModel: true,
  bgRefWindow: 24,
  futureMinScore: 12,
  scoreNormalize: true,
});

const f = (v) => (v === null || v === undefined || Number.isNaN(v) ? 'null' : v.toFixed(6));
const out = [];
for (let i = 0; i < n; i += 1) {
  const buf = raw.subarray(i * frameSize, (i + 1) * frameSize);
  const s = score(buf);
  out.push([
    i, f(s.score), f(s.rawScore), f(s.gain), f(s.barFrac), f(s.meanV),
    f(s.mcScore), f(s.mcBox), f(s.mcFill), f(s.mcSpanY),
    f(s.activeY), f(s.guardY), f(s.bgScore), s.bgStable, s.bgChanged,
  ].join('\t'));
}
process.stdout.write(out.join('\n') + '\n');
console.error(`frames=${n} fps=${fps}`);
