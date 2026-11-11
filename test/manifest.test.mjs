/**
 * 三份清单里的包名必须是同一个。
 *
 * 0.3.1 改名（`dsh-see-now-phone` → `dsh-see-on-phone`）时漏了 `cordis.patch.yml`，
 * 插件装上去引擎会按旧名字找模块 —— 表现是"装了但工具不在"，和没装一模一样，
 * 而且不报错。这种错只有跑起来才发现，所以在这里钉住。
 *
 * 没有 yaml 依赖，且这两行格式固定，直接按行匹配。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (p) => readFileSync(join(ROOT, p), 'utf8');

const pkg = JSON.parse(read('package.json'));
const manifest = JSON.parse(read('dsh.plugin.json'));

test('包名在 package.json / dsh.plugin.json / cordis.patch.yml 三处一致', () => {
  assert.equal(manifest.name, pkg.name, 'dsh.plugin.json 的名字和 package.json 不一致');
  assert.equal(manifest.entry?.name, pkg.name, 'dsh.plugin.json 的 entry.name 和包名不一致');

  const patch = read('cordis.patch.yml');
  const names = [...patch.matchAll(/^\s*name:\s*(\S+)\s*$/gm)].map((m) => m[1]);
  assert.ok(names.length > 0, 'cordis.patch.yml 里没有 name: —— 引擎找不到要加载的模块');
  for (const n of names) {
    assert.equal(n, pkg.name, `cordis.patch.yml 里的 name: ${n} 和包名 ${pkg.name} 不一致`);
  }
});

test('dsh.bundle.patch 指向的文件真的存在', () => {
  const rel = pkg.dsh?.bundle?.patch;
  assert.ok(rel, 'package.json 缺 dsh.bundle.patch —— 插件市场上架会被拒');
  assert.doesNotThrow(() => read(rel), `${rel} 读不到`);
});
