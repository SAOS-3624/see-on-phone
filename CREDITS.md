# CREDITS.md

> 许可证与"这个项目不做什么"的声明在 [LICENSE](LICENSE) 与 [README](README.md) 的「开源与商用」段。
> 本文件只记**代码是谁的**。

## 1. 作者

[@SAOS-3624](https://github.com/SAOS-3624) —— 项目发起与维护。

## 2. 代码来源

| 部分 | 来源 | 许可 |
|---|---|---|
| `src/engine/`（打分、切点、装箱 DP、拼图、PNG、token 换算） | 整块 vendor 自 [dsh-see-now](https://github.com/SAOS-3624/dsh-see-now)，同作者 | Apache-2.0 |
| `src/cam/`（MJPEG 抠帧、缩放、抓拍、ffmpeg 后端） | 本仓库自写 | Apache-2.0 |
| `src/index.js`、`guide.js`、`mode.js`、`inbox.js`、`tts.js` | 本仓库自写 | Apache-2.0 |

## 3. 第三方代码（**唯一真正 vendor 进仓库的一处**）

| 组件 | 版本 | 许可 | 位置 |
|---|---|---|---|
| [jpeg-js](https://github.com/jpeg-js/jpeg-js) | 0.4.4 | **封装 BSD-3-Clause**（© 2014 Eugene Ware）+ **`lib/decoder.js` Apache-2.0**（© 2011 notmasteryet） | `src/cam/vendor/jpeg-js/` |

- 两份许可全文都在 vendor 目录：`LICENSE`（BSD-3）、`LICENSE-APACHE.txt`（Apache-2.0）。
- 上游 npm 包只带 BSD-3 的 `LICENSE`，Apache-2.0 那一半没有附全文；Apache-2.0 §4(a) 要求再分发时
  给接收者一份许可证副本，所以本仓库补了 `LICENSE-APACHE.txt`。逐条说明见
  [`src/cam/vendor/jpeg-js/NOTICE.md`](src/cam/vendor/jpeg-js/NOTICE.md)。
- `lib/decoder.js` 头部的版权与许可声明**原样保留**，未删改。

## 4. 运行时外部组件（**不随本仓库分发**）

| 组件 | 许可 | 说明 |
|---|---|---|
| 「看见」App（`com.dsh.eye`） | 本项目的另一部分 | 提供 `:4444/video/mjpeg`，插件只是 HTTP 客户端 |
| IP Camera App（DigitallyRefined/android-ip-camera） | MIT | 可选替代源，用户自行安装 |
| FFmpeg / ffprobe | LGPL/GPL（用户自选构建） | 可选后端，通过 `PATH` 以独立进程调用，不打包不链接 |

## 5. 如果将来真的要复制代码

复制 MIT / BSD / Apache-2.0 代码是合法的，但要**随分发附带原版权声明与许可全文**：

1. 被复制的文件头保留原始版权行；
2. 在上表新增一行；
3. 把该组件的 LICENSE 全文放进 `src/<路径>/vendor/<组件>/`。

空着就代表没有。
