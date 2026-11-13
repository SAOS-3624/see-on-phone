// 用 see-now 的 pack.js 跑同一批用例，输出与 PackerCli 同格式
import { readFileSync, writeFileSync } from 'node:fs';
const { solve } = await import(new URL('../../../../dsh-see-now/src/pack.js', import.meta.url).href);

const lines = readFileSync('cases.txt', 'utf8').trim().split('\n');
const out = [];
const f = (v) => v.toFixed(6);
for (const line of lines) {
  const tiers = line.split(',').map(Number);
  const r = solve(tiers, { stretch: 0.3, binCost: 1.02 });
  out.push(`CASE ${line} bins=${r.bins} area=${f(r.area)}`);
  for (const c of r.cells) {
    out.push(`  ${c.seq},${c.t},${c.bin},${f(c.x)},${f(c.y)},${f(c.w)},${f(c.h)}`);
  }
}
writeFileSync('js.txt', out.join('\n') + '\n');
console.log('lines =', out.length);
