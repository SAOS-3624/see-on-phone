#!/usr/bin/env node
/**
 * cli.mjs —— `dsh-see-on-phone` 的命令行入口。
 *
 * 为什么要有它：这套"搬会话"的能力以前是一堆零散脚本，各自读 `process.argv`、
 * 各自 `console.error` 然后 `process.exit`。装成 npm 包之后，用户需要**一个**命令。
 *
 * 设计上只做三件事：拆子命令、把参数原样递给下面、把输人话化。
 * 真正的逻辑都在 `bridge.mjs` / `to-dsh.mjs` / `doctor.mjs` / `repair.mjs` 里。
 */
import { runCommand as runBridge } from './bridge.mjs';
import { runCli as runDoctor } from './doctor.mjs';
import { runCli as runRepair } from './repair.mjs';
import { explain, dataDir, projectDir } from './paths.mjs';

const HELP = `
鱼眼 · See on Phone —— 手机和电脑之间搬会话

  第一次用（插着数据线，或者手机设置里开了「局域网」）：
    dsh-see-on-phone sync              一条命令：拉包 + 导进 DSH

  分步来：
    dsh-see-on-phone pull              只把手机上的包拉到电脑
    dsh-see-on-phone import            只把电脑上的包导进 DSH
    dsh-see-on-phone export latest     把 DSH 里最近一条会话导出来（给手机）
    dsh-see-on-phone push              把导出好的包推到手机
    dsh-see-on-phone status            看手机在不在

  出问题了：
    dsh-see-on-phone doctor            体检：哪些会话 DSH 打不开
    dsh-see-on-phone repair            修（默认只看不改，加 --write 才写盘）

  参数：
    --host 192.168.1.5:4444   走局域网，不插线
    -s <序列号>               插了两台手机时指定一台（adb devices 第一列）
    --dir <目录>              包放哪（默认 ${dataDir()}）
    --cwd <目录>              导进哪个项目（默认当前目录）
    --dsh / --wb / --both     导进哪边（默认 DSH；--wb 是 WorkBuddy 侧边栏，要 Node 22.5+）
    --force                   同一个包重导一次
    --dry                     只看会做什么，不写盘

  说明：
    · 导入是**只加不删**的：手机上已有的会话不会被覆盖，电脑上也一样
    · 图片不跟着搬（导出时换成一行文字占位），手机上的原图还在
    · 搬完要**完全退出并重开 DSH**，列表才会刷新
`.trim();

/** 拆出子命令和它后面的参数。 */
function split(argv) {
  const known = new Set(['sync', 'pull', 'import', 'export', 'push', 'status', 'doctor', 'repair', 'help']);
  const first = argv[0];
  if (!first || first === '--help' || first === '-h' || first === 'help') return { cmd: 'help', rest: [] };
  if (known.has(first)) return { cmd: first, rest: argv.slice(1) };
  // 不以子命令开头：当成给 bridge 的（兼容老写法 `dsh-see-on-phone pull --host ...`）
  return { cmd: first, rest: argv.slice(1) };
}

/** 给用户看的失败输出。多行原样保留（很多提示本身就是"第一步/第二步"）。 */
function fail(e) {
  const msg = String(e?.message ?? e);
  console.error(`\n✗ ${msg}`);
  const { plain, action } = explain(msg);
  if (plain) console.error(`  ${plain}`);
  if (action && action.startsWith('dsh-see-on-phone')) console.error(`  → ${action}`);
  process.exitCode = 1;
}

async function main(argv) {
  const { cmd, rest } = split(argv);

  if (cmd === 'help') {
    console.log(HELP);
    return;
  }

  if (cmd === 'doctor') {
    const r = await runDoctor(rest);
    if (r?.fails?.length) process.exitCode = 1;
    return;
  }

  if (cmd === 'repair') {
    await runRepair(rest);
    return;
  }

  if (cmd === 'sync') {
    // 一条命令：先拉包，再导进 DSH。`--wb` / `--both` 原样透传给 import。
    await runBridge(['pull', ...rest]);
    console.log('');
    await runBridge(['import', ...rest]);
    return;
  }

  // 其余全部交给 bridge（pull / push / import / export / status）
  await runBridge([cmd, ...rest]);
}

try {
  await main(process.argv.slice(2));
} catch (e) {
  fail(e);
}

// 有 hint 的一个地方：`export` 没给 id 时会拿到 undefined，bridge 会报"没找到会话"，
// 这里补一句用户能照做的。放在最后，不侵入 bridge。
export { main, HELP };
