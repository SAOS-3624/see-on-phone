/**
 * paths.mjs —— 所有"东西放哪儿"的判断只在这一个文件里。
 *
 * 为什么单独抽出来：这套脚本原来是在我们自己的开发目录里长出来的，
 * `DEFAULT_SRC` 写成"项目根/.workbuddy/phone-data"、adb 写成某个写死的 SDK 路径，
 * 换个用户、换个系统，第一步就是 `没有这个目录`。装机的人不该猜路径。
 *
 * 三个原则：
 *   1. **默认值必须对陌生人成立**（`~/.dsh/...`、PATH 里的 adb、当前目录当项目）
 *   2. 每个都能用环境变量覆盖（CI / 多开 / 非标准安装）
 *   3. 找不到就给一句人能照做的话，不要把 `ENOENT` 甩出去
 */
import os from 'node:os';
import path from 'node:path';
import { existsSync } from 'node:fs';

/** 手机包的落地目录：`pull` 往这儿放，`import` 从这儿读。 */
export function dataDir() {
  return process.env.DSH_PHONE_DATA || path.join(dshHome(), 'phone-data');
}

/** DSH 的家目录（会话、缓存、workspace.json 都在这下面）。 */
export function dshHome() {
  return process.env.DSH_HOME || path.join(os.homedir(), '.dsh');
}

/**
 * 导进哪个项目。
 *
 * ⚠️ **默认是"你当前所在的目录"**，不是插件包自己的目录。
 * 装成 npm 包后 `import.meta.dirname` 指向 `node_modules/dsh-see-on-phone/`，
 * 拿它当项目会把会话挂到一个用户永远找不到的工作区里。
 */
export function projectDir() {
  return process.env.DSH_PROJECT_CWD || process.cwd();
}

/**
 * Git Bash 传进来的 `/d/foo/bar` 这种 POSIX 形态，Node 在 Windows 上不认（spawn 直接 ENOENT）。
 * 转成 `D:/foo/bar`。
 */
export function fixPosixDrive(p) {
  if (process.platform === 'win32' && /^\/[a-zA-Z]\//.test(p)) {
    return p[1].toUpperCase() + ':' + p.slice(2);
  }
  return p;
}

/**
 * adb 可执行文件。
 *
 * 顺序：`ADB` 环境变量 → PATH 里的 `adb`。
 * **不再猜 某个写死的 SDK 路径** —— 那是开发机的路径，别人的机器上不可能有。
 */
export function adbBin() {
  if (process.env.ADB) return fixPosixDrive(process.env.ADB);
  return 'adb';
}

/** adb 找不到时给的话（`spawn` 的 ENOENT 长得完全不像"没装 adb"）。 */
export const ADB_HINT =
  '没找到 adb。两种办法任选一个：\n'
  + '  · 把 Android platform-tools 加进 PATH（里面要有 adb 或 adb.exe）\n'
  + '  · 或者设一个环境变量 ADB 指到它，例如 ADB=/opt/platform-tools/adb\n'
  + '不插线也能用：手机 App → 设置 → 数据 → 打开「局域网」，然后加 --host <手机IP>:4444';

/**
 * DSH 装在哪（`app.asar`）。
 *
 * 体检要借 DSH 自己的校验代码，所以得先找到它的安装包。四个平台位置都试一遍，
 * 也可以用 `DSH_ASAR` 直接指定。
 *
 * @returns {string|null} 找不到返回 null（体检会降级，不会假装通过）
 */
export function findAsar() {
  const env = process.env.DSH_ASAR;
  if (env) return existsSync(env) ? env : null;

  const home = os.homedir();
  const candidates = [
    // Windows（安装版 / 便携版）
    path.join(process.env.LOCALAPPDATA || '', 'Programs', 'DeepSeek Harness', 'resources', 'app.asar'),
    path.join(process.env.PROGRAMFILES || '', 'DeepSeek Harness', 'resources', 'app.asar'),
    path.join(process.env['PROGRAMFILES(X86)'] || '', 'DeepSeek Harness', 'resources', 'app.asar'),
    // macOS
    '/Applications/DeepSeek Harness.app/Contents/Resources/app.asar',
    path.join(home, 'Applications', 'DeepSeek Harness.app', 'Contents', 'Resources', 'app.asar'),
    // Linux（deb / AppImage 解包 / 用户目录）
    '/opt/DeepSeek Harness/resources/app.asar',
    '/usr/lib/deepseek-harness/resources/app.asar',
    '/usr/share/deepseek-harness/resources/app.asar',
    path.join(home, '.local', 'share', 'deepseek-harness', 'resources', 'app.asar'),
  ];
  return candidates.find((p) => p && existsSync(p)) || null;
}

/**
 * 把 DSH 的英文报错翻成"下一步做什么"。
 *
 * 用户看到的原始报错长这样：
 *   `stored session "session-xxx" is corrupt: stored log is corrupt: SessionFormatError:
 *    session/title messageSeqs must cite distinct earlier human user/message events`
 * 这里面没有一条信息能告诉他"这跟我从手机导入有关"。
 *
 * @param {string} why 原始报错（取第一行）
 * @returns {{plain: string, action: string}} 人话 + 该跑什么
 */
export function explain(why) {
  const s = String(why || '');
  const rules = [
    [
      /must cite distinct earlier human user\/message/i,
      '这条会话的「标题来源」记错了：它指向的是一条流程记录，不是你说的话。',
      'dsh-see-on-phone repair',
    ],
    [
      /invalid settlement fields/i,
      '这条会话的某条回答缺「收尾信息」（这轮怎么结束的）。',
      'dsh-see-on-phone repair',
    ],
    [
      /producer-owned source kind|must have model source/i,
      '这条会话的某条消息没标「谁写的」（人 / 模型）。',
      'dsh-see-on-phone repair',
    ],
    [
      /is not dense|seq/i,
      '这条会话的记录编号断档了（中间丢了或多了几条）。',
      'dsh-see-on-phone repair',
    ],
    [
      /requires a protected first surface head|outside an open turn/i,
      '这条会话的「轮次」结构错位了（开始和结束对不上）。',
      'dsh-see-on-phone repair',
    ],
    [
      /unknown event type|newer harness/i,
      '这条会话是**更新版本**的 DSH 写的，你这版 DSH 不认识它的某些记录。不是坏了，是版本旧了。',
      '升级 DSH（或升级本插件）',
    ],
    [
      /Cannot find package|Cannot find module/i,
      '体检工具和你这版 DSH 的内部结构对不上（它升级过）。',
      'npm i -g dsh-see-on-phone@latest',
    ],
  ];
  for (const [re, plain, action] of rules) {
    if (re.test(s)) return { plain, action };
  }
  // 认不出来就不解释 —— 别把"adb 没装"掰成"会话结构坏了"，那比不解释更糟
  return { plain: '', action: '' };
}
