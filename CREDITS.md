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

本仓库的历史里没有第二个人提交过代码。外部朋友提的问题与建议记在
[`dsh-see-now` 的 CREDITS](https://github.com/SAOS-3624/dsh-see-now/blob/main/CREDITS.md) 里
（那边的判帧算法是从那些 issue / PR 里长出来的，这个 App 继承了它）。

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

## 3. 算法与思想的来源（**未复制任何代码**）

这几条是"照着想出来的"，不是"照抄过来的"。按 BSD / MIT 的字面要求，用思想并不需要署名 ——
但既然本项目的每一处判断都能追到源头，就写在这儿。

| 来源 | 许可 | 我们取了什么 | 对应实现 |
|---|---|---|---|
| [PySceneDetect](https://github.com/Breakthrough/PySceneDetect)（Breakthrough） | BSD-3-Clause | `ContentDetector` 的**加权打分思想**：把 HSV 三通道的差异加权成一个 0–255 的"变化分"。网格多大、阈值怎么定、怎么确认尖峰，全是我们自己试出来的 | `app/src/com/dsh/eye/Cuts.java`（`scan.js` → `cuts.js` → 这里，三代都是独立实现，没有读过它的源码） |
| DeepSeek 官方《图像理解 · Token 用量》文档 | 文档 | 图像 token 的换算公式（14px patch 对齐 / 42px token 格 / 每行 +1 / 整图 +2） | `app/src/com/dsh/eye/Tokens.java` |

> PySceneDetect 那条要特别说明：**思想可以借用，代码不行**。它用 OpenCV + numpy 在原始分辨率上算，
> 我们在 64×36 的降采样网格上手写，连哈希和阈值都是另一套。所以这不是"移植"，是"读了结论自己实现"。

## 4. 随仓库分发的第三方代码

只有一个：`app/src/com/dsh/eye/Drm.java` + `EdgeTts.java`，**LGPL-3.0**（见 §4.1）。
其余部分刻意保持干净：

- App 侧：零三方库、零 AndroidX，只用 `android.*` framework（Camera2 / TextToSpeech / ServerSocket / Canvas）。
  构建也不需要 Gradle，`javac → d8 → aapt2 → apksigner` 一条 `build.sh`。
- 插件侧：0.4.0 起删掉了 `src/engine/`（4137 行 vendor 引擎）和 `src/cam/vendor/jpeg-js/`；
  拼图改由手机 App 做，电脑只收一张 JPEG。

### 4.1 在线音色：`Drm.java` / `EdgeTts.java`（LGPL-3.0）

App 里那 5 个 Edge 在线音色（小轩 / 晓晓 / 晓伊 / 云希 / 云扬）要靠 [rany2/edge-tts](https://github.com/rany2/edge-tts)
那套 `Sec-MS-GEC` 令牌算法才能连上：URL 上必须带一个五分钟一格的令牌，而它的两个哈希**都被改写过的**
（初始状态由前 7 字节推出、附带一堆调整常量），没有"简化版"可走。

这两个文件就是那份 Python 实现的 **Java 翻译** —— 著作权上属于**演绎作品**，所以它们是 LGPL-3.0，
**不是 Apache-2.0**，文件头有 `SPDX-License-Identifier: LGPL-3.0-or-later`。

⚠️ **LGPL 不是 GPL，别搞混。** LGPLv3 有「组合作品」例外：把 LGPL 代码和别的代码编进同一个程序，
**不会**让整个程序变成 LGPL/GPL。所以：

- 仓库里除这两个文件以外的所有代码，仍然是 **Apache-2.0**；
- 这两个文件按 **LGPL-3.0-or-later** 分发，源码就在仓库里；
- 不想要它：删掉这两个文件、把 `Speaker` 里 `edge:` 那条分支去掉即可，其余代码一行不动。

**为什么还是留着**：这是唯一能出「小轩」这类音色的路子，用户明确要它。代价是它连的是微软
**没有公开**的消费级接口 —— 随时可能失效，也可能不符合微软的服务条款。所以：默认**不启用**，
设置页随时能切回系统 TTS，合成失败会自动回落，不会让 App 哑掉。用不用、担不担这个风险，交给使用者。

> 2.1.0 曾把这条链路整段删掉（当时的说法是"GPL 会污染整个 APK"——**那个说法不准确**，
> LGPL 不传染；见上面的 4.1）。2.3.0 按用户要求加了回来，并把许可标对。

## 5. 历史版本里有、现在已经没有了

> 下面这段是**为了让拿到旧版本的人还能查到出处**。当前代码树里没有它的任何字节。

### 5.1 `jpeg-js`（BSD-3 + Apache-2.0）—— 插件 0.3.x 的 JPEG 解码，0.4.0 已删除

插件 0.3.x 曾内置 [jpeg-js](https://github.com/jpeg-js/jpeg-js) 0.4.4
（封装 BSD-3-Clause © 2014 Eugene Ware；`lib/decoder.js` Apache-2.0 © 2011 notmasteryet）。
0.4.0 起拼图改由手机端完成，电脑只收一张现成的 JPEG，这份 vendor 连同 `src/cam/` 一起删了。
许可说明保留在此，以满足历史分发的署名要求。

## 6. 运行时外部组件（**不随本仓库分发**）

| 组件 | 许可 | 说明 |
|---|---|---|
| Android SDK（`android.jar` / `d8` / `aapt2` / `apksigner`） | Apache-2.0 / 各自条款 | 只在构建时用，不打包进 APK |
| Android 系统 TTS 引擎（讯飞 / 搜狗 / Google TTS / 厂商自带） | 各自条款 | App 2.0 起**只**调系统 TTS；引擎由用户自己装，我们不分发、不打包 |
| FFmpeg / ffprobe | LGPL / GPL（用户自选构建） | 插件**不需要**；个别脚本用它转码用户自己的素材 |
| `ffplay` / `mpv` | LGPL / GPL | 电脑侧播报语音时才需要；以独立进程调用，不链接 |
| IP Camera App（DigitallyRefined/android-ip-camera） | MIT | 可选的第三方替代摄像头源，用户自行安装 |

## 7. 图像与素材

| 素材 | 出处 |
|---|---|
| App 图标（`app/art/icon-source.jpg`） | 由项目作者提供的原创作品，© SAOS-3624，随本仓库以 Apache-2.0 分发 |
| 界面图标（`app/src/com/dsh/eye/Glyph.java`） | **手写的 Path**：在 24×24 栅格上用 `addRoundRect`/`addCircle`/`moveTo` 画出来，不是从图标库拷的 path 数据 |
| 界面截图（`app/docs/*.png`） | 本项目实机截图 |
| 示例宫格（`app/docs/sheet-*.jpg`） | 本项目对作者自有素材跑出来的输出 |

## 8. 许可怎么随二进制走

- **npm 包**：`package.json` 的 `files` 里有 `LICENSE` / `NOTICE` / `CREDITS.md`，装包就带着。
- **APK**：许可全文与 NOTICE 打进 `res/raw/license.txt` / `res/raw/notice.txt`，
  手机上进「设置 → 关于 → 许可」就能看到。**别把这两个文件从 `res/raw/` 删掉** ——
  Apache-2.0 §4(a) 要求随分发附一份许可，而 APK 没有"随包塞一份文档"的默认位置。

## 9. 如果你要往这里加代码

1. 复制 MIT / BSD / Apache-2.0 代码是合法的，但要**随分发附带原版权声明与许可全文**；
2. 在 §4 加一行，并把对方的 LICENSE 全文放进 `app/src/.../vendor/<组件>/` 或 `src/vendor/<组件>/`；
3. 被复制文件头部的版权行**原样保留**，一个字都别改；
4. GPL / AGPL 的代码**不要**贴进来。真要"移植"（翻译成另一种语言也算），先想清楚：
   同一个 APK 里的 Apache-2.0 代码会被一起拖进 GPL —— 1.x 的在线音色就是这么被砍掉的。
