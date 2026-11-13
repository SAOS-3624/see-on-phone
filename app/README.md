# 鱼眼 App

> 手机上的 AI：会聊天，也会自己看世界。纯 Java、零三方库、无 Gradle，APK 约 220 KB。
>
> 构建方式见 [`docs/BUILD.md`](docs/BUILD.md)；电脑侧插件在仓库根目录。

## 一页架构

```
┌─────────────────────────────────────────────┐
│ MainActivity      会话页：消息流 / 输入栏 / 状态 │
│ CallActivity      通话页：实时预览 + 字幕      │
│ SettingsActivity  设置：分组卡片               │
│ ConvActivity      会话列表                     │
├─────────────────────────────────────────────┤
│ EyeService        前台服务：谁在什么时候该发生什么 │
│  ├ Asker          一轮的执行者：说→想→干活→再说  │
│  ├ Brain          只发字节：HTTP 流式 + 拆包    │
│  ├ Tools          look / tune / memory / files │
│  └ Looker         借相机 → 喂 2~4 秒 → 拼宫格   │
├─────────────────────────────────────────────┤
│ Conv / ConvStore  上下文：预算裁剪 + 滚动摘要   │
│ Pipeline / Packer 切点 → 档位 → 行 DP → 拼图   │
│ HttpServer        127.0.0.1:4444（给电脑插件）  │
│ Drm / EdgeTts     在线音色（失败回落系统 TTS）   │
└─────────────────────────────────────────────┘
```

界面全是**代码拼 View**，没有 layout XML、没有 ViewModel、没有 DataBinding。
`Theme / Glyph / Anim` 是三个自写的小件：设计令牌、纯 Path 矢量图标、动效工具。

## 一次"看一眼"发生了什么

```
模型决定调 look
  └─ Tools → Looker.look(seconds)
       ├─ camAcquire()            借相机（计数 +1，3 秒留手）
       ├─ 采样 2~4 秒（12 fps，缩到 64×36 网格打分）
       ├─ Pipeline.feed()         逐帧算变化分 → 找镜头切点
       ├─ 封箱：按切点密度分档（1/1、1/4、1/9…）
       ├─ Packer.pack()           行 DP 装箱：动得多的给大格子
       ├─ Sheet.render()          画成一张 471×628 的宫格 JPEG
       ├─ Tokens.fit()            算这张图要花多少 token
       └─ camRelease()            还相机
  └─ 一张 base64 JPEG 回到模型
```

一条宫格 ≈ **197 token**，4~7 KB。它**会留在会话历史里每轮重发** ——
所以只在真要"看"的时候调，别轮询。

## 相机是"借来的"

界面**从不申请相机**。只有两种情况会 `open`：

1. 模型调用 `look`（或电脑插件打 `/frame.jpg`、`/video/mjpeg`、`/look`）；
2. 你手动进入通话页。

有引用计数 + 3 秒留手：最后一个用户走了 3 秒后才真正 `close`，
这样连续两次 `look` 不会反复开关相机（华为上重开一次要 600ms 左右）。
**改这套之前先想清楚**：搞错了相机会被永久占用，别的 App 就打不开了。

## 工作区（它能自己写文件）

它有一块地：`Download/DSH-Go/workspace/`。说"帮我记下来""存成文件"，它就写进去 ——
你在文件管理器里**能直接看到、能拷走、能删**。

| 工具 | 作用 |
|---|---|
| `files({action:"list"})` | 列目录（名字、大小、是不是目录） |
| `files({action:"read", path})` | 读文本（超 64 KB 截断并告诉它还剩多少） |
| `files({action:"write", path, text})` | 整篇覆盖 |
| `files({action:"append", path, text})` | 追加 |
| `files({action:"delete", path})` | 删一个文件（目录非空不给删） |

**边界只有这一个目录。** Android 11+ 的分区存储下，普通应用全盘写要么用
`MANAGE_EXTERNAL_STORAGE`（商店会拒），要么让用户一个个目录授权。而真实需求是
"把我说的东西存下来" —— 一块固定的地就够，还不需要任何额外权限。

三道防线防越界：拒绝对路径 → 拒绝任何 `..` 段 → 解析完再确认 `canonicalPath` 还在根里
（第三道才真正管用，前两道只是让报错好懂）。一次写上限 512 KB、读上限 64 KB。

> 和 `memory_write` 的分工：**以后每轮都要自动看到**的 → 记忆；**存下来给我用的** → 工作区。

## HTTP：电脑把手机当眼睛

端口固定 `4444`，**只绑 `127.0.0.1`**。从电脑访问要 `adb forward tcp:14444 tcp:4444`。

| 端点 | 作用 |
|---|---|
| `GET /status` | 版本 / 相机是否开着 / 思考档 / 当前会话 / 记忆条数 |
| `GET /frame.jpg` | 单帧 JPEG（按需开相机，≤1.5s） |
| `GET /video/mjpeg` | MJPEG 流（连着才开相机，断开 3 秒关） |
| `GET /look?seconds=` | **现场拼一张宫格**，返回 `{images:[b64], frames, tokens, why, canvas}` |
| `GET /say?text=` | 朗读 |
| `GET /ask?text=` | 让手机当场回答（走完整工具循环） |
| `GET /config?key=&model=&think=` | 改接口 / 模型 / 思考档 |
| `GET /?peek=1` | 排障：把所有配置键原样吐出来 |

`/video/mjpeg` 和 `/frame.jpg` 的语义刻意与 IP Camera 那类 App 对齐 ——
所以第三方客户端也能直接连它。

## 会话与记忆

- 会话落成 **一行一条的 jsonl**（`filesDir/conv/<id>.jsonl`），出错拿记事本就能看；
- 上下文预算可调（2k~900k），超了先裁旧消息、图片单独 offload、再不行滚动摘要；
- 记忆是纯文本文件，每轮自动进 prompt；设置页能单条删；
- 和桌面互通只交换**最笨的东西**：jsonl + 纯文本 + 一堆 jpg，`adb pull` 一个目录就完事。

## 已知限制

- **`minSdk 21` / `targetSdk 29`**：见 [BUILD.md](docs/BUILD.md) 的说明，不是懒得升级。
- **语音输入只有单句**（系统 `RecognizerIntent`）。常驻 `SpeechRecognizer` 在部分机型上
  会一直 `ERROR_RECOGNIZER_BUSY`，试过了，不划算。
- **在线音色是逆向接口**：`Sec-MS-GEC` 令牌五分钟一格，服务端会校验。
  它随时可能被改掉，所以**必须可关**（选"系统默认"就完全不走网络）。
- **导盲模式不是辅具。** 见 [../NOTICE](../NOTICE)。

## 许可

Apache-2.0。代码出处与署名见 [../CREDITS.md](../CREDITS.md)。
