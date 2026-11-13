// 生成对拍用例：固定边界 + 随机
import { writeFileSync } from 'node:fs';
const cases = [];
// 边界
for (let n = 1; n <= 8; n += 1) cases.push(new Array(n).fill(2));
cases.push([1], [1, 1], [1, 1, 1], [1, 2], [1, 2, 2], [1, 2, 2, 2], [2, 1, 2], [3, 3], [1, 3, 3], [0, 2, 0]);
for (const k of [1, 2, 3, 4, 5, 6, 7, 8]) {
  cases.push([k], [k, k], [k, k, k], [k, k, k, k], [k, k, k, k, k, k, k, k]);
}
cases.push([1, 2, 3], [3, 2, 1], [1, 4, 2, 8], [8, 1, 8, 1], [2, 2, 1, 2, 2], [4, 4, 4, 4, 4, 4]);
// 随机
let seed = 20261110;
const rnd = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; };
for (let i = 0; i < 400; i += 1) {
  const n = 1 + Math.floor(rnd() * 8);
  const a = [];
  for (let j = 0; j < n; j += 1) a.push(1 + Math.floor(rnd() * 8));
  cases.push(a);
}
writeFileSync('cases.txt', cases.map((c) => c.join(',')).join('\n') + '\n');
console.log('cases =', cases.length);
