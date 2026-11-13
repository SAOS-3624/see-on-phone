/**
 * 对拍用：用 dsh-see-now 的 pipeline.js 跑同一批 rgb24 帧，输出与 PipelineCli 同格式。
 */
import { readFileSync } from 'node:fs';
const { createPipeline } = await import(new URL('../../../../dsh-see-now/src/pipeline.js', import.meta.url).href);

const [rawPath, wArg, hArg, fpsArg] = process.argv.slice(2);
const width = Number(wArg);
const height = Number(hArg);
const fps = Number(fpsArg);

const raw = readFileSync(rawPath);
const frameSize = width * height * 3;
const n = Math.floor(raw.length / frameSize);

const preset = process.argv[6] ?? 'default';
const PRESETS = {
  default: {},
  sensitive: { cutAbs: 3, cutIsolatedAbs: 2, cutMinGap: 0.2, maxWaitSec: 3 },
  dense: { cutAbs: 5, maxFrames: 40, maxWaitSec: 2, flushGain: 0.005 },
  weight: { tierMode: 'weight', cutAbs: 4, maxWaitSec: 4, markBoost: 2 },
  scene: { maxFrames: 1, strategy: 'single', cutAbs: 7 },
  gap: { floorGapK: 2, floorMin: 2, floorMax: 20, silentFloorSec: 3 },
  curve: { tierCurve: 'perceptual', maxWaitSec: 3, cutAbs: 5 },
};
const p = createPipeline({ width, height, fps, queueDepth: 0, ...(PRESETS[preset] ?? {}) });

const f = (v) => v.toFixed(4);
const out = [];
const drain = () => {
  let s;
  while ((s = p.take())) {
    out.push([
      'S', f(s.at), f(s.from), f(s.to), s.why, s.frames.length,
      s.frames.map((x) => f(x.t)).join(','),
      s.frames.map((x) => x.tier).join(','),
      s.frames.map((x) => x.via).join(','),
      s.frames.map((x) => x.level).join(','),
    ].join('\t'));
  }
};

for (let i = 0; i < n; i += 1) {
  const buf = Buffer.from(raw.subarray(i * frameSize, (i + 1) * frameSize));
  p.push({ t: i / fps, buf });
  drain();
}
p.finish();
drain();
process.stdout.write(out.join('\n') + (out.length ? '\n' : ''));
const st = p.stats;
console.error(`frames=${n} kept=${st.kept} cuts=${st.cuts} marks=${st.marks} floors=${st.floors} sheets=${st.sheets}`);
