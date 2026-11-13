# CREDITS

> 许可证正文在 [LICENSE](LICENSE)，"这个项目不做什么"的声明在 [NOTICE](NOTICE)。
> **本文件只回答一个问题：每一块代码/算法是谁的。**

鱼眼（See on Phone）由两部分组成，代码同源、许可相同（Apache-2.0）：

| 部分 | 位置 | 语言 | 形态 |
|---|---|---|---|
| 鱼眼 App | [`app/`](app/) | Java（Android，零三方依赖） | 手机端，自己就能聊天、看图、说话 |
| 电脑插件 | 仓库根目录 | Node ≥ 20（零运行时依赖） | `dsh-see-on-phone`，让桌面上的 agent 借手机的眼睛 |

---

## 1. 作者

[@SAOS-3624](https://github.com/SAOS-3624) —— 项目发起与维护，两部分的主要作者。

## 2. 项目内部：同源移植（同一作者、同一许可）

拼图引擎在三个仓库里各有一份实现，**算法是同一套，代码按语言重写，不是各自长出来的**：

| 本仓库位置 | 移植自 | 移植方式 |
|---|---|---|
| `app/src/com/dsh/eye/Cuts.java` | [`dsh-see-now/src/cuts.js`](https://github.com/SAOS-3624/dsh-see-now) | 逐行移植（64×36 HSV 网格 + 因果尖峰确认） |
| `app/src/com/dsh/eye/Tiers.java` | `dsh-see-now/src/tiers.js` | 逐行移植（密度 → 平滑 → 7 道阈值 + 滞回 → 1..8 档） |
| `app/src/com/dsh/eye/Packer.java` | `dsh-see-now/src/pack.js` | 逐行移植（行 DP 装箱：面积优先、跨行代价） |
| `app/src/com/dsh/eye/Pipeline.java` | `dsh-see-now/src/pipeline.js` | 逐行移植（封箱时机 / 取帧 / 档位分配） |
| `app/src/com/dsh/eye/Tokens.java` | [`dsh-video-storyboard/src/storyboard/tiers.js`](https://github.com/SAOS-3624/dsh-video-storyboard) 的 `imageTokens` / `fitCanvas` | 照抄宿主真实计费口径（`rows × (cols+1) + 2`，42px 格） |
| `app/src/com/dsh/eye/Sheet.java` | 自写 | 拼图落盘（Bitmap + Canvas） |

三份实现曾被**逐字节对拍**过（`app/tools/parity/` 里留着对拍脚本与用例），
所以改其中一份、另外两份没跟上是会被发现的 —— 这不是巧合，是故意的。

## 3. 参考实现与协议（**未复制任何代码**）

| 组件 | 出处 | 许可 | 我们用了什么 |
|---|---|---|---|
| Edge 在线朗读（`zh-CN-XiaoxuanNeural` 等） | [rany2/edge-tts](https://github.com/rany2/edge-tts) 7.3.1 | GPL-3.0 | **只参考协议行为**：`Sec-MS-GEC` 令牌的构造方式、`Sec-MS-GEC-Version` 的格式、WebSocket 帧序列。`app/src/com/dsh/eye/Drm.java` 与 `EdgeTts.java` 是**独立 Java 实现**（自写 RFC 6455 帧、自写哈希），仓库里不含 edge-tts 的任何一行代码 |
| 图像 token 计费口径 | DeepSeek 官方《图像理解 · Token 用量》文档 | 文档 | 换算公式（14px patch 对齐 / 42px token 格 / 每行 +1 / 整图 +2） |

> **关于 GPL-3.0 的边界**：GPL 约束的是"复制与演绎代码"。这里做的是
> "按公开可见的协议行为写一份自己的实现"，属于互操作性使用；协议常量、URL 形状、
> 时间戳格式是**事实**，不受版权保护。如果你要往这个仓库里贴 edge-tts 的代码，
> 那整包就得跟着变 GPL-3.0 —— 请别这么做。

## 4. 随仓库分发的第三方代码

**没有。** 这是刻意的：

- App 侧：零三方库、零 AndroidX，只用 `android.*` framework（Camera2 / TextToSpeech / ServerSocket / Canvas）。
  构建也不需要 Gradle，`javac → d8 → aapt2 → apksigner` 一条 `build.sh`。
- 插件侧：0.4.0 起删掉了 `src/engine/`（4137 行 vendor 引擎）和 `src/cam/vendor/jpeg-js/`
  （唯一真正 vendor 过的第三方代码）—— 拼图改由手机 App 做，电脑只收一张 JPEG。

历史版本（插件 0.3.x）曾内置 [jpeg-js](https://github.com/jpeg-js/jpeg-js) 0.4.4
（封装 BSD-3-Clause © 2014 Eugene Ware；`lib/decoder.js` Apache-2.0 © 2011 notmasteryet）。
它已从当前代码树移除，许可说明保留在此以满足历史分发的署名要求。

## 5. 运行时外部组件（**不随本仓库分发**）

| 组件 | 许可 | 说明 |
|---|---|---|
| Android SDK（`android.jar` / `d8` / `aapt2` / `apksigner`） | Apache-2.0 / 各自条款 | 只在构建时用，不打包进 APK |
| FFmpeg / ffprobe | LGPL / GPL（用户自选构建） | 插件**不需要**；个别脚本用它转码用户自己的素材 |
| `ffplay` / `mpv` | LGPL / GPL | 电脑侧播报语音时才需要；以独立进程调用，不链接 |
| IP Camera App（DigitallyRefined/android-ip-camera） | MIT | 可选的第三方替代摄像头源，用户自行安装 |

## 6. 图像与素材

| 素材 | 出处 |
|---|---|
| App 图标（`app/art/icon-source.jpg`） | 由项目作者提供的原创作品，© SAOS-3624，随本仓库以 Apache-2.0 分发 |
| 界面截图（`app/docs/*.png`） | 本项目实机截图 |

## 7. 如果你要往这里加代码

1. 复制 MIT / BSD / Apache-2.0 代码是合法的，但要**随分发附带原版权声明与许可全文**；
2. 在 §4 加一行，并把对方的 LICENSE 全文放进 `app/src/.../vendor/<组件>/` 或 `src/vendor/<组件>/`；
3. 被复制文件头部的版权行**原样保留**，一个字都别改；
4. GPL / AGPL 的代码**不要**贴进来 —— 那会把整个仓库的许可拖走。
