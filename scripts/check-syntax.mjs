/**
 * 语法体检：递归 `node --check` 所有源码。
 * 手机（PRoot）上装之前跑一遍，比等到宿主里加载失败再看日志快得多。
 */
import { execFileSync } from 'node:child_process';
import { readdirSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const DIRS = ['src', 'scripts', 'test'];

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (['.js', '.mjs'].includes(extname(p))) out.push(p);
  }
  return out;
}

let files = 0;
let failed = 0;
for (const d of DIRS) {
  let list;
  try { list = walk(join(ROOT, d)); } catch { continue; }
  for (const f of list) {
    files += 1;
    try {
      execFileSync(process.execPath, ['--check', f], { stdio: 'pipe' });
    } catch (err) {
      failed += 1;
      console.error(`✗ ${f}\n${err.stderr?.toString() ?? err.message}`);
    }
  }
}
console.log(failed ? `✗ ${failed}/${files} 个文件语法错误` : `✓ ${files} 个文件语法通过`);
process.exit(failed ? 1 : 0);
