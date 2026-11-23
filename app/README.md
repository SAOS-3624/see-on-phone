# 看见 · DSH Eye

**v4（0.14.0）起，它是「手机上的 DSH」**：一个会话页、一个输入框、一套自己会用的工具。

```
你说话 → 它想（思考四档）→ 需要就自己 look 一眼 / tune 调档 / 记一笔 → 回答
```

三条和 0.13 及以前**完全不同**的地方：

| | 0.13 及以前 | v4 |
|---|---|---|
| 相机 | 常驻开，界面就是取景器 | **工具**：只在 `look` / `/frame.jpg` / `/video/mjpeg` 要的时候开，没人要 3 秒后关 |
| 模式 | 正常 / 导盲 两套 prompt | 删了。只剩一种形态 |
| 档位 | 用户在设置里调 6~8 个采集参数 | 模型自己用 `tune` 调（糊了调清楚、贵了调省），设置页只剩 12 行 |
| 主界面 | 通话页（画面铺满 + 字幕 + 「看一眼」「开始看」） | 会话页（消息流 + 输入框 + 「思考 3.2s」）；**画面单独做成通话页，点顶栏 📹 进** |
| 记忆 | 只能人写文件 | 它自己能 `memory_read` / `memory_write`，设置页能单条删 |
| 音色 | 只有系统 TTS（华为上 3 个中文嗓子） | **小轩（`zh-CN-XiaoxuanNeural`）直接在这台手机上合成**，另带晓晓/晓伊/云希/云扬 |

**没变的（故意）**：`Packer / Sheet / Tiers / Tokens / Cuts` 一个字没动（与桌面 `pack.js`
逐字节对拍，0 差异）；对外 HTTP 端点一个没删（电脑上的 `see_phone` 插件还靠它们工作）。

## 一页架构

| 文件 | 管什么 |
|---|---|
| `MainActivity` | 会话页：消息流 / 输入框 / 话筒 / 工具状态条 / 每条回答下面的「思考 N.Ns」 |
| `EyeService` | 谁在什么时候该发生什么：会话属于谁、相机借给谁、说完话念不念 |
| `Asker` | 一轮的执行者：说话 → 想 → 干活 → 再说（≤3 轮 / 90 秒总预算） |
| `Brain` | 只发字节：拼请求、发 HTTP、把回答拆成 正文 / 思考 / 工具调用 |
| `Tools` | 四个工具：`look` / `tune` / `memory_read` / `memory_write` |
| `Looker` | 「看一眼」：开相机 → 喂 2~4 秒 → 封箱 → 拼宫格 → 关相机 |
| `Conv` / `ConvStore` | 上下文：预算裁剪 + 图 offload + 滚动摘要；一行一条落盘 |
| `CallActivity` | 通话页：实时预览 + 字幕（**离开就还相机**） |
| `Drm` / `EdgeTts` | 小轩的音色：`Sec-MS-GEC` 令牌 + 手写 RFC6455 + 常驻连接 + MP3 缓存，失败回落系统音 |
| `Mp3Stream` | 流式播放：`MediaCodec` 解 MP3 → `AudioTrack`，收到一块响一块；`Speaker` 负责按句切 + 打断 |
| `Portable` | 会话/记忆的摊平导出与增量导入（`/export`、`/import`） |
| `Pipeline` / `Packer` / `Sheet` / `Tiers` / `Tokens` / `Cuts` | 切点检测 + 排布 + 拼图（**别碰**，对拍基线） |

## 两个"看得见/听得见"的细节

**通话页（📹）**：会话页平时没有画面（相机是工具，模型要看才开）。想自己看着的时候点
顶栏那个摄像头图标 —— 全屏实时预览 + 最后一句字幕 + 输入框。**退出就还相机**（3 秒后关）。
预览用 `FIT_CENTER` 不裁切：宁可上下留黑边，也要保证"你看到的 = 模型看到的"。

**小轩（在线音色）**：设置 → 语音 → 音色，前五个是在线的（小轩打头）。它是**逆向接口**：
`Sec-MS-GEC` 令牌五分钟一格、服务端会校验（假令牌 403），WebSocket 帧是手写的。
三层兜底：本地缓存 `DSH-Go/tts/<sha1>.mp3` → 在线合成 → **失败回落系统 TTS**。
令牌算法和 edge-tts 7.3.1 逐位对拍（`.tmp/drm/vec.txt` 12 组全过）——
它随时可能被微软改掉，所以**必须可关**（选「系统默认」就完全不走网络）。

**流式出声**（2.9.2）：以前是"整段合成完 → 存成 mp3 → 交给播放器"，一段 500 字的回答
要等六七秒才有第一个字。现在按句切开、**收到第一块音频就播**（`Mp3Stream`：`MediaCodec`
解 MP3 直接写 `AudioTrack`，零第三方库），而且合成连接**跨句复用**。实测（桌面探针跑同一份
`EdgeTts`）：冷启动首块 1.3~1.5 秒，复用连接时 **200~300ms**。
"别念了"也真的能立刻停：每句话/每次打断都给一个代际号，旧任务看到自己过期就收工，
连 WebSocket 一起关掉（只停播放的话，正在收的那句还会收完再开口）。

## 工作区（它能自己写文件）

它有一块地：`Download/DSH-Go/workspace/`。说「帮我记下来」「存成文件」「写个脚本」，
它就写进去 —— 你在文件管理器里**能直接看到、能拷走、能删**。

| 工具 | 作用 |
|---|---|
| `files({action:"list"})` | 列目录（名字、大小、是不是目录） |
| `files({action:"read", path})` | 读文本（超 64KB 截断并告诉它还剩多少） |
| `files({action:"write", path, text})` | 整篇覆盖 |
| `files({action:"append", path, text})` | 追加 |
| `files({action:"delete", path})` | 删一个文件（目录非空不给删） |

**边界只有这一个目录。** Android 11+ 的分区存储下，普通应用全盘写要么用
`MANAGE_EXTERNAL_STORAGE`（商店会拒）要么让用户一个个目录授权。而真实需求是
"把我说的东西存下来"，所以只开一块固定的地 —— Download 底下，**不需要任何额外权限**。

三道防线防越界：拒绝对路径 → 拒绝任何 `..` 段 → 解析完再确认 `canonicalPath` 还在根里
（第三道才真正管用，前两道只是让报错好懂）。一次写上限 512KB、读上限 64KB。

> 和 `memory_write` 的分工：**以后每轮都要自动看到**的 → 记忆；**存下来给我用的** → 工作区。
> 记忆是每轮重发的，工作区不是。

设置 → 数据 → 工作区，能看清单、点开读、删掉、清空。

## 手机 ⇄ 电脑（会话和记忆搬运）

两端只交换**最笨的东西**：一行一条的 jsonl、纯文本的记忆、一堆 jpg。摊平成一个目录，
`adb pull` 一个目录就完事 —— 电脑上不用装 zip 库、出问题拿记事本就能看。

```bash
node tools/phone-bridge.mjs pull     # 手机 → 电脑（落到 .workbuddy/phone-data/）
node tools/phone-bridge.mjs push     # 电脑 → 手机
node tools/phone-bridge.mjs status
```

| 内容 | 合并规则 |
|---|---|
| 会话 `conv/<id>.jsonl` | **按时间戳增量追加**，只补本地没有的 → 来回搬不会重复也不会丢 |
| 会话图片 | 只补缺的（同名就是同一张） |
| 记忆 `memory/*` | 本地没有 → 写；内容一样 → 跳过；**内容不同 → 不覆盖**，另存 `import/skipped/` 并报出来 |

最后一条是刻意的：记忆是**人写的话**，每轮都进 prompt，自动覆盖最容易悄悄把手机上手改的那段冲掉。

手机上也可以直接点：设置 → 数据 → 备份与迁移。导出时顺手在 `export/` 生成一个
`phone-<时间>.zip`，那个是给你发微信用的，命令行这条路不用它。

## HTTP（电脑把手机当眼睛，这一层长期保留）

```
GET /status                  版本 / 相机是否开着 / think / conv / mem
GET /frame.jpg               单帧 JPEG（按需开相机，≤1.5s）
GET /video/mjpeg             MJPEG 流（连着才开相机，断开 3 秒关）
GET /say?text=               朗读
GET /ask?text=               让手机当场回答（走完整工具循环）
GET /look?seconds=           **v4 新增**：现场拼一张宫格，返回 {images:[b64],frames,tokens,why,canvas}
GET /config?key=&model=&think=   改接口/模型/思考
GET /?peek=1                 排障：把所有配置键原样吐出来
```

端口固定 **4444**，只绑 `127.0.0.1`。
PC 侧调试走 `adb forward tcp:14444 tcp:4444`
（**forward 和用它的命令必须写在同一条 shell 里**，adb server 被回收后转发会静默消失）。

---

> **以下章节写于 0.13.x，描述的是"相机助手"那个形态。**
> 相机 / 拼图 / 布局对拍那几节仍然有效（那部分代码没动）；涉及模式、连续看、
> 「看一眼」按钮、"通话页"的部分**已经过时**，读的时候以本节为准。


## 为什么能这么小

- 只用 framework 原生 API（Camera2 / TextToSpeech / ServerSocket），**零三方库、零 AndroidX**。
- 不用 Gradle：`javac → d8 → aapt2 → apksigner` 一条 `build.sh`，只依赖 `phone/sdk/android-13`。
- 图标是脚本画的 PNG（`tools/make-icon.py`，纯标准库手写 PNG），不为一张图引 Pillow。

## HTTP 协议（故意和 IP Camera 同名同义）

插件 **0.1.x 一行都不用改**就能用：

```
GET /video/mjpeg             multipart/x-mixed-replace 连续流（SOI/EOI 可抠帧）
GET /frame.jpg               抓一张
GET /control/status          状态 JSON
GET /?camera=back&rotate=90&torch=on&resolution=low&fps=15
GET /say?text=...            朗读（新增；替掉"写 say.txt + 轮询"）
GET /tts/status              语音状态 JSON
```

只绑 `127.0.0.1:4444`：这是给同机进程用的管道，不暴露到局域网。
PC 侧调试走 `adb forward tcp:18150 tcp:4444`（**forward 和用它的命令必须写在同一条 shell 里**，
adb server 被回收后转发会静默消失）。

## 关键实现取舍

- **像素级旋转在这儿做，不在插件里**：插件用的 jpeg-js 不认 EXIF。源 640×480 横 → 转 90° →
  480×640，正好 3:4，插件 `cover` 到 336×448 **零裁切**。
- **rotate 按摄像头分别记**：`?rotate=90` 是插件给后摄下发的（配置默认 90）。切前摄还照搬 90 就歪，
  所以前摄默认走「自动」= `SENSOR_ORIENTATION`。
- **预览用 ImageView，直接显示引擎转好的那张位图**（不是 SurfaceView / TextureView）：
  - SurfaceView 在这台华为上截屏全黑，害我误判"摄像头没起来"；
  - TextureView 能截屏，但要在自己的 transform 里手算旋转 + 缩放，照抄经典配方仍然
    转歪、且 `cover` 把 3:4 裁成一条（用户："转了 90°、视场角特别小"）。
  - 现在引擎 `transform()` 里那张 `rot` 位图**直接交给界面**（所有权转移），
    屏幕上看到的就是插件拿到的那一帧 —— 旋转和取景范围不可能再对不上。
    显示用 `FIT_CENTER`（整幅 + 黑边），导盲要"看得见全部"，不许裁。
- **三层保活**：`START_STICKY` + 开机广播 + 10 分钟一次的 `AlarmManager`。
  华为会把后台一次性清掉，只靠 STICKY 不够。
- **targetSdk 25**：Android 12 的后台启动限制只管 targetSdk ≥ 31 的 App，我们还要在开机/被杀后自启。

## 编译 / 安装

```bash
bash build.sh                                   # → dsh-eye.apk (37KB)

# 推上去 + 静默装（能过就别用 adb install）
adb push dsh-eye.apk /data/local/tmp/dsh-eye.apk
adb shell pm install -r -g /data/local/tmp/dsh-eye.apk
adb shell am start -n com.dsh.eye/.MainActivity
```

`-g` 会把 CAMERA / 存储权限一次授完，不用点运行期弹窗。

## 踩过的坑

| 坑 | 症状 | 解决 |
|---|---|---|
| `RandomAccessFile.readLine()` | 中文全变 `èæä»¶` 再喂给 TTS | 按字节读全文件，`new String(bytes,"UTF-8")` |
| SurfaceView 预览 | 界面全黑但 4444 有帧 | 换 TextureView |
| 预览挂载时序 | 服务比 Surface 晚起，`attachPreview` 丢在空里 | 界面每 800ms "问一次"，`setPreview` 幂等 |
| fps 改 12 没用 | 实测还是 14.9 | 本机只提供 `[15,15]/[30,30]` 固定档，12 落到 15 |
| 华为侧载安装 | 风险提示 → 备案勾选 → **滑块验证码** | 滑块位置随机，`input swipe` 能不能过看运气；关校验开关（`package_verifier_enable`）**不生效** |
| `adb install` | 卡在安装器 UI 不回 | 用 `pm install`；被 SIGTERM 打断会留下 `InstallStaging` 残窗 |

## 文件

| 文件 | 作用 |
|---|---|
| `src/com/dsh/eye/EyeService.java` | 常驻前台服务：装配相机/HTTP/语音、帧总线、控制参数、say.txt 兼容、通知、保活 |
| `src/com/dsh/eye/CameraEngine.java` | Camera2 采集：挑尺寸、会话、旋转、手电筒、断线重开 |
| `src/com/dsh/eye/HttpServer.java` | 裸 ServerSocket 的 HTTP 服务（MJPEG/控制/朗读） |
| `src/com/dsh/eye/Speaker.java` | TextToSpeech（QUEUE_FLUSH 打断优先） |
| `src/com/dsh/eye/MainActivity.java` | 一个屏幕 + 四个按钮 + TextureView 预览 |
| `src/com/dsh/eye/BootReceiver.java` | 开机 / 更新 / 闹钟 → 拉起服务 |
| `tools/make-icon.py` | 画图标（纯标准库写 PNG） |
| `build.sh` | 一键编译签名 |

## 悬浮窗（载体）：踩坑记

悬浮窗不是装饰，它是"能在模型阻塞等待时把话塞进去"的那个外挂载体。但 Android 的
overlay 有几个坑，全都在这台华为上真机踩过：

| 坑 | 症状 | 正解 |
|---|---|---|
| 漏 `FLAG_NOT_TOUCH_MODAL` | 悬浮窗把**窗口外**的触摸也吃掉，下面的 App 点不动 | 加上 |
| 在 overlay 里放 `EditText` | 摘 `NOT_FOCUSABLE`、抬窗口、抢焦点、收键盘…在 EMUI 上时好时坏，还会让窗口变可聚焦 | **别放**：点一下开 `TypeActivity`，键盘在 Activity 里最可靠 |
| 按钮监听"事后 set" | 窗口重建（隐藏后重显示/服务重启）后新按钮没有监听 → 点「说话」毫无反应，而同一窗口的 ✕ 正常 | 监听写在 `build()` 里，结构上不可能漏 |
| 开 Activity 前不收起窗口 | 悬浮窗永远在最上层，挡住键盘和「发送」 | `pauseWindow()` → 开 Activity → `isFinishing()` 时 `resumeWindow()` |
| 用 `onDestroy` 无条件恢复 | 键盘弹出会触发配置变更重建 Activity，窗口在输入页上反复冒出来 | 只在 `isFinishing()` 时恢复 |
| EditText 自动抢焦点 | 窗口一挂上就变可聚焦、吞掉下层点击 | 默认 `setFocusable(false)`（本版已不需要 EditText） |

### 语音输入：这台机器没有引擎

`RecognizerIntent.ACTION_RECOGNIZE_SPEECH` 在 nova 11 上直接
`ActivityNotFoundException: No Activity found to handle Intent` —— 没有装任何识别组件，
常驻 `SpeechRecognizer` 也只会回 `ERROR_RECOGNIZER_BUSY(8)`。
所以**打字是主入口**，「说话」只在有引擎的机器上才有用（点它会提示"没有语音输入，用「打字」"）。

### 自检日志（logcat 被屏蔽时的唯一手段）

release 包在这台华为的 logcat 里一条都看不到（连 crash buffer 都空），所以窗口/服务的关键
节点都写 `/sdcard/Download/DSH-Go/eye.log`：

```bash
adb exec-out cat /sdcard/Download/DSH-Go/eye.log | tail -20
```

### 装更新：把应用市场禁掉，绕开滑块验证码

```bash
# 一次禁掉，**就一直禁着，不要再 enable** —— 这台机是测试机，用户不常用市场
adb shell pm disable-user --user 0 com.huawei.appmarket

# ⚠️ **先停 App 再装**：服务在跑（相机被占着）时 pm install 会一直不返回，
#    实测卡过 90 秒以上，看起来像"装不上"，其实只是没停进程。
adb shell am force-stop com.dsh.eye

adb push dsh-eye.apk /data/local/tmp/dsh-eye.apk
adb shell pm install -r -d /data/local/tmp/dsh-eye.apk    # 直接 Success，无弹窗
```
禁了之后 `pm install` 直接返回 **Success**，既不弹滑块也不用划"继续安装"。
（`adb install` 在这台机上会被 SIGTERM，走 push + pm install。）
装完补一次悬浮窗权限：`adb shell appops set com.dsh.eye SYSTEM_ALERT_WINDOW allow`。

⚠️ 用户原话："每次都要我帮你划，不然你还以为是你划的吗" —— 别再把 enable 加回去。

## 主界面 = 通话页（2026-11-09 改）

参照物是**视频通话 / 直播间**，不是相机设置页：

```
┌──────────────────────────┐
│ ● 后摄 · 14.9 fps · 正常  悬浮 正常│  ← 一行状态（含模式），压在画面上
│                          │
│        画面铺满全屏        │  ← ImageView + FIT_CENTER（整幅不裁）
│                          │
│  门口有两级台阶，我先扶你一把  │  ← AI 台词当字幕（19sp，最近 3 句）
│                          │
│ ┌────────────────────┐   │
│ │ 说点什么…     发送 │   │  ← 输入条，跟聊天一样
│ └────────────────────┘   │
│  前后摄   手电筒   说话    │  ← 工具行
└──────────────────────────┘
```

悬浮窗**默认不再弹**（`winWanted=false`）：它的定位变成"寄居在 dsh-go 上时用的外挂"，
只有点顶栏「悬浮」才挂出去。App 内不需要 overlay —— 焦点、键盘、触摸穿透那一堆坑直接消失了。

两个必须记住的细节：
- 输入框会自动抢焦点弹键盘 → 根布局 `setFocusableInTouchMode(true)` + `requestFocus()`，
  清单里再配 `windowSoftInputMode="adjustResize|stateAlwaysHidden"`。
- 顶栏状态要短：`statusShort()`（一行）；`statusLine()` 那版多行只给排障。

## 两种模式：正常 / 导盲（2026-11-09 23:10 加）

起因是用户一句话：**"我为什么点一眼看按键马上就默认我是盲人？"**

三个地方同时把读者写死成盲人，缺一不可地都要改：

| 层 | 0.2.x 的行为 | 现在 |
|---|---|---|
| 直连 `Brain.SYSTEM` | 只有一套「给视障人士做实时导盲」 | 两套：normal 是普通看图对话，guide 才是导盲 |
| 「看一眼」按钮的固定句 | `看一下我前面有什么，用一句话说` | normal：`看一眼，我前面是什么？`；guide 保持原句 |
| 朗读 | 每句都念 | **独立的「朗读」开关，默认开**（0.3.2 修：0.3.0 把朗读绑在模式上，被用户当场发现"怎么不播报了"） |
| 插件 `mode` 默认值 | `general`（但工具描述写着"用户看不见"） | `normal`，工具描述不再预设读者 |

**开关在顶栏右侧**（"正常 / 导盲"），设置页里也有一份（顶栏按钮小，看不见的人摸不到）。切完立刻写 `/sdcard/Download/DSH-Go/mode.txt` —— 插件每次工具调用都读它，
所以下一次调用就换口吻，**不用重启插件、也不用重连 dsh-go**。

顺带两个必须记住的：

- **`subtitle` 要封顶**。导盲回答一两句，正常回答可以几百字；不封顶时面板会一路长到
  顶栏上、把画面全盖住（第一次跑就踩到）。现在 `maxHeight = 50% 屏高` + 可滚动，
  并且 normal 模式只留最新一条台词（guide 留 3 条）。
- **`mode.txt` 是唯一跨进程通道**。App 和插件不同 uid，广播/am start/socket 都试过不行
  （见插件 `src/inbox.js` 顶部）。写文件最笨但半年没失败过。

### 朗读是独立开关（0.3.2 修）

**说什么（模式）和念不念（朗读）是两件事。** 0.3.0 一度把"normal 不念"写死在模式里，
用户下一句就是"现在怎么好像不会语音播报了"——所以拆开：

- 默认 **开**（和 0.2.x 一致）；设置页里点一下切；命令行 `GET /?speak=off`；
- 静音时顶栏和通知栏都显示「静音」，不是假装一切正常；
- **长文本按句切段排队**（120 字一段、最多 8 段，第一段打断旧话、后面接着念）。
  以前是整段硬截 500 字，正常模式的长回答后半句直接被吞，用户以为是"没播报"。
- 显式的 `/say`（插件让念的那句）不受开关影响——那是有人明确要求出声。

## 连续看（0.4.3 起 = 电脑版的 watch 循环）

「看一眼」是**一帧**；「开始看」是**一段时间**。0.4.3 起这条链路**整条就是电脑版的
`pipeline.js`**，App 里不再有自己的采样/阈值/窗口逻辑：

```
摄像头每帧 → 解码 240×320 rgb24 → Pipeline.push()
                              → 逐帧判：切点 / 积分帧 / 兜底帧
                              → 装不下 / 时间到 / 碎格 / 满帧 → 封箱
           → take() 拿到就发模型
```

0.4.2 之前的写法（每 200ms 抽一帧算 60×80 平均绝对差、过 6 分才留、最多 4 帧、
2.5 秒窗口到就发）**已整体删除**。对照表在 `Watcher.java` 的头注释里。

| 概念 | 谁定 | 说明 |
|---|---|---|
| 什么时候算"画面变了" | `cutAbs`（节奏档） | 尖峰 2.5× / 台阶 / 运动补偿 / 孤立弱切点，四选一 |
| 变化不够大怎么办 | 积分帧 | 分数累到 `4 × fps × 3 秒` 才留一帧 |
| 一直没变化 | 兜底帧 | 静默 `floorGapK × 近期间隔`，夹在 2~20 秒 |
| 一帧占多大格子 | 档位 1..8 | 密度算出（`tiers.js`），再按 `tierStyle` 映射 |
| 什么时候封箱 | `checkFlush` | 装不下 / 3 帧满 15 秒 / 碎格 / 满 40 帧 |

### 攒几张一起发（`wakeBatch`，0.4.6）

对应电脑版 `~/.dsh/see-now.json` 的 `wakeBatch`（`runner.js` 的 `takeSheets()` +
`loop.js` 的 `pending >= batch`），设置页叫「攒几张」：**1（默认）/ 2 / 3 / 4**。

- `1` = 拼好一张就发（老行为）。
- `> 1` = **一次请求发多张图**（不是拼成一张大图）—— 省的不是图的钱（一张 322 token，
  多带两张几乎不要钱），省的是"每一轮都要把整个会话上下文重过一遍"那笔。
- `queueDepth` 必须 ≥ 攒的张数，否则第 2 张一出来第 1 张就被挤掉了（电脑版也是这么设的）。
- **攒不满会先发**：第一张进队列后超过 30 秒还没攒够，就把现有的发出去。
  静止画面 20 秒才出一张，硬等 3 张要一分钟 —— 那不叫省，叫卡住。日志会写
  `攒批超时（1/3），先发这些`。

⚠️ **静止场景下基本攒不满**，实测 70 秒：`frames 1062 / kept 5 / dup 586 / sheets 1`
（去重闸把几乎一样的帧全挡了，一箱本身就难攒）。**动态画面才有意义** —— 切点密集时
几秒一箱，3 张只要十几秒。

### 帧率会不会影响阈值？（0.4.8）

**结论：主阈值全部是时间，不受帧率影响；但有三处内部窗口是按帧写的，已按 fps 折算。**

| 参数 | 单位 | 帧率影响 |
|---|---|---|
| `cutAbs` / `cutSpike` / `cutJumpAbs` / `isolated.*` | 分数（无量纲） | 无 |
| `cutMinGap` 0.4 | **秒** | 无 |
| `integralSeconds` 3 | **秒**（`need = 4 × fps × 3`，用 fps 换算） | 无 |
| `silentFloorSec` 20 / `floorMin` / `floorMax` | **秒** | 无 |
| `maxWaitSec` 15 / `markMinGap` 0.5 / `cutFutureDelay` 0.5 | **秒** | 无 |
| **档位 7 道阈值** | **帧/秒**（不是每帧概率） | 无 |
| 密度窗 `dtFrames / fps` | **秒** | 无 |
| ⚠️ `smoothFrames` 6 | **帧**（12fps 下 = 0.5 秒） | **有**，已折算 |
| ⚠️ `BAR_WINDOW` 24 | **帧**（12fps 下 = 2 秒） | **有**，已折算 |
| ⚠️ `cutBgWindow` 6 | **帧**（12fps 下 = 0.5 秒） | **有**，已折算 |

后三个是电脑版"固定 12fps，所以帧数 = 时间"的遗留写法。手机帧率是 8/15/30 档位，
照抄就是"窗口时长随帧率变"（15fps 时平滑只剩 0.4 秒、黑边窗 1.6 秒）。
App 用 `timeNormalizeFrames` 按 fps 折算，**秒数恒定**；关掉则与电脑版逐帧一致
（对拍跑的就是关闭口径，7 组参数 × 720 帧仍全绿）。

**比这更要紧的是 `fps` 本身准不准。** `CONTROL_AE_TARGET_FPS_RANGE` 只能选固定档，
这台机请求 12 会落到 15 —— 如果 pipeline 用"设置页的值"当 fps，所有秒↔帧换算整体偏 25%。
所以现在取的是**实测帧率**（`EyeService.measuredFps()`，每 2 秒统计一次）：
`?fps=30` 之后 `/status` 的 `echo.fps` 报 30，而设置页还是 15，一眼看出用的是实测。

### 高级设置：`tuning.json`（0.4.7，键名与电脑版完全一致）

`/sdcard/Download/DSH-Go/tuning.json`。**这就是 `~/.dsh/see-now.json` 的那份文档** ——
在电脑上调好的参数原样拷过来就生效，一份文档管两个端。

为什么不是"再多加几个开关"：`pipeline.js::DEFAULTS` 有 **60 个参数**，`tuning.js` 有
**14 个键**。手写 74 行开关一定有几个漏在角落，而"感觉算法还是不一样"往往就来自漏掉的那一个。
所以 `Tuning.applyTo()` 用**反射**遍历 `Pipeline.Opts` 的字段 —— **JSON 里能写什么，管线上就能改什么**。

| 键 | 说明 |
|---|---|
| `tierStyle` | `standard`（默认）/`native`/`full`/`compact`/`single`/`custom` |
| `tierMap` | `custom` 时的逐档表，8 个数 |
| `tierMode` | `density`（按密度）/ `weight`（按变化分切面积） |
| `tierCurve` | `docx` / `perceptual` / `custom` |
| `tierThresholds` | 7 道阈值（帧/秒，严格递增）；写了就盖过曲线 |
| `scoreNormalize` | 亮度归一 + 黑边补偿 |
| `defaultPace` | `calm`/`normal`/`dense`/`game-economic` |
| `wakeBatch` | 攒几张一起发 |
| 其余 60 个 | `cutAbs` / `cutSpike` / `cutMinGap` / `cutMcAbs` / `integralSeconds` / `silentFloorSec` / `floorGapK` / `dupMin` / `markBoost` / `floorBoost` / `boostMinFrames` / `flushGain` / `maxFrames` / `maxWaitSec` / `binCost` / `packMode` … |

**优先级**：`tuning.json` > 设置页 > `Pipeline.Opts` 的默认值（= 电脑版的 DEFAULTS）。
没写的键一个都不动。改完不用重启，下一次点「开始看」就按新值建管线。

例：

```json
{
  "tierStyle": "native",
  "tierMode": "weight",
  "tierCurve": "perceptual",
  "defaultPace": "dense",
  "cutAbs": 5,
  "silentFloorSec": 8,
  "wakeBatch": 2
}
```

验证生效：`/status` 里的 `echo` 报的是**管线实际用的值**（不是设置页的），改完刷新一眼就知道：

```json
"tuning":"已加载 163 字节、7 个键",
"echo":{"tierStyle":"native","tierMode":"weight","tierCurve":"perceptual","cutAbs":5.0,
        "integralSeconds":3.0,"silentFloorSec":8.0,"maxFrames":40,"maxWaitSec":15.0,
        "scoreNormalize":true,"tierThresholds":null}
```

`width` / `height` 故意不给改：那是打分分辨率（240×320），改了 `gridRgb` 的采样点就变，
跟电脑版的对拍结论作废。

### 换帧率之后结果会差多少（实测）

脚本 `tools/parity/multi-fps.sh`，两类素材 × 8/12/15/24/30fps，各 40 秒。

| 素材 | 指标 | 8 | 12 | 15 | 24 | 30 |
|---|---|---|---|---|---|---|
| 真实视频（几乎静止） | 留帧 / 出图 | 2/1 | 2/1 | 2/1 | 2/1 | 2/1 |
| 切点密集（每 0.3 秒换画面） | **切点** | 43 | 44 | 44 | 44 | 44 |
| 同上 | 留帧 | 52 | 48 | 50 | 45 | 45 |
| 同上 | **出图** | **5** | 9 | 9 | **12** | **12** |

**切点数几乎不变**（判据是分数 + 秒，与帧率无关）；**出图张数会差 2.4 倍** —— 但根因不是帧率。

`tierMode: "density"`（默认）把"每秒留几帧"拿去查一张**硬阈值表**
`[0.26, 0.6, 0.9, 1.2, 1.8, 2.4, 3.0]`。这批素材 8fps 的密度是 1.3、30fps 是 1.125，
**第 4 道阈值正好是 1.2**：一个落 4 档（格子 1/16）、一个落 3 档（1/9），
一箱能装的帧数差 78%，出图张数就翻倍。关掉 `markBoost` 后仍是 6/8/9/12/12 —— boost 只是加剧。

**同样的跳变在同帧率下也会发生**：画面节奏在 1.2 帧/秒附近晃的时候，它照样一张/两张地跳。
这是"密度分档"的设计特性，不是移植误差。

**要跨帧率稳定就用 `tierMode: "weight"`** —— 实测五档**全部 8 张，完全一致**。
它按每帧变化分切面积，不查阈值表（连续映射，不是跳变）。代价：一张图覆盖时长变长、
出图变慢、同图内大小对比更大。

`width` / `height` 故意不给改：那是打分分辨率（240×320），改了 `gridRgb` 的采样点就变，
跟电脑版的对拍结论作废。

**验证**：`tools/parity/pipeline-js.mjs`（JS 侧）对 `PipelineCli.java`（Java 侧），
七组参数（默认 / sensitive / dense / weight / scene / gap / curve）× 720 帧，逐张比
`at/from/to/why` + 每帧的时刻/档位/理由 —— **全部逐字节一致**。
打分器另外单测过：160 帧 × 16 个字段（score/raw/gain/bar/meanV/mc/bg/…）逐字节一致。

省钱/保命的两个闸仍然在 App 层：**自动停**（`idleStopMin`）和 **APP 请求报错立刻停**。
另有一个**心跳**：每 5 秒查一次"最近有没有帧"，超过 15 秒没画面就停并说明原因 ——
摄像头被别的 App 抢走时，光看"有没有变化"是发现不了的。

排障（不用连电脑也能看）：

```bash
adb exec-out cat /sdcard/Download/DSH-Go/eye.log        # watch start/round N sheets=… frames=… why=… → ok: …
adb pull /sdcard/Download/DSH-Go/watch-sheet.jpg        # 最近一张真正发出去的宫格
# 不调模型、不花钱，把"现在攒着的这一箱"立刻封箱落盘（需要先点「开始看」）：
adb forward tcp:18130 tcp:4444 && curl "http://127.0.0.1:18130/control?burst=1"
# 改「攒几张」不用点设置页（排障口，和 ?fps= / ?mode= 一个套路）：
adb forward tcp:18130 tcp:4444 && curl "http://127.0.0.1:18130/control?batch=3"
```

`/status` 里带排障摘要，一眼看出"管线为什么不动"：

```json
"pipe":{"frames":1062,"kept":5,"cuts":0,"marks":4,"floors":0,"dup":586,"sheets":1,"pending":0},
"decode":{"ok":1062,"fail":0},"wantBatch":3
```

- `kept` 远小于 `frames` 且 `dup` 很大 → 画面几乎没变，去重闸在正常工作（不是坏了）
- `cuts: 0` → 一次切镜头都没有；`floors` 一直涨说明它只是在"定时交差"
- `decode.fail > 0` → JPEG 解不出来（内存紧张或采集坏了）

## 界面：宫格预览（0.4.4）

连续看每拼好一张，**那张图会同步显示在主界面右上角**（标签写着「几帧 · 为什么封箱」）。
以前这张图只存在于一次 HTTP 请求里，想复查只能 `adb pull watch-sheet.jpg`。

- 点一下：放大到整屏（再点还原）。全屏时会 `bringToFront()` 盖住底部面板。
- 顶栏「宫格」：显示 / 隐藏这张预览。关掉就只剩相机画面。
- 尺寸跟着图的比例走（2 帧并排是宽扁的、9 帧近方），不写死长宽 —— 否则上下留黑。
- 服务里存着"最近一张"，所以**退出再进来会补显示**，不用等下一箱（静止画面可能要几十秒）。

数据通路：`Watcher.send` 拼好 → `Host.onSheet` → 服务后台线程解码 → `SheetSink` → 界面。
**解码不在主线程**（一张图可能 600KB，会卡一帧界面）。

## 三层线程与空闲降帧（0.4.4）

| 线程 | 干什么 | 为什么单独一条 |
|---|---|---|
| 采集（CameraEngine） | 只把 JPEG 丢进单槽队列 | 以前解码挂在这条线程上，每帧拖 8~10ms |
| 打分（`see-watch`） | 解码 240×320 → `Pipeline.push` | 慢了就丢一帧（单槽 = 永远处理最新那张） |
| 网络（`io`） | 拼图 + 发 HTTP | 它慢不能挡住打分，否则错过的是画面 |

**空闲降帧**：相机是服务启动就开的（预览 / 插件的 MJPEG 流 / 「看一眼」都要它），
但没人看时没必要满速跑 —— 没开连续看、也没有 HTTP 客户端连着，就降到 **5fps**；
有人看回到设置里的帧率。回满速有 **30 秒迟滞**（`setFps` 会重建 capture session，来回切更费）。

## 宫格排布（0.4.2）：Packer = 电脑版 `pack.js` 的逐行移植

以前的 `Sheet` 是**等分格**：1 帧整幅、2 帧并排、3~4 帧 2×2 —— 按**帧数**定版式。
三个硬伤（前两个是用户实测报的，第三个是数学问题）：

1. **5 帧以上放不下**，只能截断，等于白丢观众看到的画面；
2. **3 帧时右下角固定空 25%**，怎么排都补不上；
3. 帧的大小和"这帧有多重要"无关，而宫格的信息量全靠"大的给关键画面"。

现在排版交给 `Packer.java` —— 桌面版 `dsh-see-now/src/pack.js` 的移植（行式 DP 装箱：
每行可以是任意连续的一段帧，行高取该行最宽的格，目标 `Σ面积 − 箱数×1.02`）。
**464 组用例（400 条随机 + 边界）跟 JS 原版逐字节对拍通过**，格子坐标、箱数、占用率一致。

档位**不再由这里决定**（0.4.3 起由 `Pipeline` 按密度算，`tierStyle` 再映射一遍）。
`Sheet` 只负责把 `Pipeline` 带来的 cells 画出来 —— 重算会让 `binCost` 口径漂掉。

| 帧数 | 档位 | 版面 | 占用率 |
|---|---|---|---|
| 1 | 1 | 整幅 | 100% |
| 2 | 2 | 并排，各占一半 | 100%（画布按内容裁到半高） |
| 3~4 | 2 | 2×2 | 100%（4 帧）/ 75%（3 帧） |
| 5~7 | 2 | 1 大 + 2 中 + 3 小 | 83% / 75% / 86% |
| 8 | 3 | 2 箱边界，自动升档 | — |
| 9 | 2 | 3×3 满铺 | 100% |

**4 帧以内看不出变化，是数学决定的**：4 个同尺寸格子的最优填充就是 2×2，谁来排都一样。
差别从 5 帧起才出现 —— 所以「每轮最多」放开了 6 / 9 两档。

代价要说清楚：**6 帧那个版面右上角会空一块**（行式排布的老毛病，`pack.js` 的注释里写着
"行式排布和按重要性给大小天生打架"，`skyline` 就是为补它写的）。实测 `skyline` 在
**等档位**输入下反而要 2 箱（5 帧 fill 0.625 / 6 帧 0.750 但 bins=2），所以没搬 ——
电脑版默认也是行式。要整齐就选 4 帧或 9 帧（都满铺）。

**加帧不加价**：一张图的 token 由**画布尺寸**决定（630×840 ≈ 322 token），跟塞几帧无关。
所以 4 帧 → 9 帧是同一个价钱看到 2.25 倍的画面。

另外缝从 `GUTTER=4` 改成 `SEAM=1`（跟电脑版 `sheet.js` 一样：少画 1px、底色透出来），
画布高度按内容实际用到的高度裁，不留整块底色。
## 设置页（0.4.0 起，0.4.3 换过连续看那组）

桌面 see-now 的设置是**宿主渲染的面板**，背后是 `/api/see-now/status|config` 那几条路由
（`dsh-see-now/src/settings-api.mjs` + `tuning.js`）。手机上这套不存在，只能自己画——
`SettingsActivity`：一个可滚动的页面，**点一行换一个值，立刻生效**。

| 分组 | 项（每项下面都有一行灰字说明） |
|---|---|
| 采集 | 分辨率 低640/中1024/高1600、帧率 8/12/15、摄像头 后/前 |
| 连续看 | 节奏 calm/normal/dense/game-economic、档位 standard/native/full/compact、自动停 0/3/5/10 分、最多轮数 0/10/30/50 |
| 拼图 | 画布宽度 480/630/840、JPEG 质量 65/78/90、补「此刻」帧 开/关 |
| 对话 | 模式、朗读、语速 0.8~1.4×、回答长度 200/500/800、变化日志 开/关 |
| 直连 | 请求超时 20/30/60/90s、key / 端点 / 模型 |

- **每行下面那行小字就是说明**：这些数字光看名字没人猜得出来（"每轮最多 4 帧"到底指什么？）。
- 采集/语速/超时改完**立刻生效**（`applySettings()`，`setResolution`/`setFps`/`setFront` 都是幂等的）；
  连续看那批在**下一轮起点**重新读一次配置，不用重启。
- 底部有「恢复默认设置」：只重置调参，**不动 key / 端点 / 模型 / 模式 / 朗读**。

刻意**没做**的（不是漏了）：采集后端 URL、画布尺寸、档位曲线、ASR、region、悬浮窗样式 ——
这些是**插件**或桌面才有的东西（`~/.dsh/see-phone.json`），在 App 里改不到那边去，
放上来只会骗人。要改插件就改那个文件，插件每次调用都会比对重载。

实现上踩的两个坑（都写在代码注释里了）：
- 设置页一打开就 AutoFocus 到 API key、键盘弹起、页面滚到底 → 根布局要
  `FOCUS_BEFORE_DESCENDANTS` + `requestFocus()`，光设 `windowSoftInputMode` 不够；
- `setResolution`/`setFps` 必须幂等，否则每次点行都重开一次相机。

## 直连模式（2026-11-09 晚）：不经过 dsh-go

走 dsh-go 那条路是「App → 文件 → PRoot 里的 Node → 插件 steer → 模型回合 → 工具 look
→ 再生成 → say.txt → App」，两个进程 + 一次完整工具回合，**十几秒是结构性的**。

所以另开一路：`Brain.java` 直接把「当前这一帧 JPEG + 你这句话」发给
`/chat/completions`（OpenAI 形状），一次 HTTP 回来就念。

| | 经 dsh-go | 直连 |
|---|---|---|
| 一轮 | 十几秒（工具回合） | 1~3 秒（一次 API） |
| 语音 | 靠模型记得调 say | 回答直接念 |
| 依赖 | dsh-go 必须后台跑 | 不需要 |

**开关就是"有没有填 key"**：填了走直连，清空就退回 dsh-go 的收件箱。两套并存，不冲突。

### 填 key 不用戳手机

```bash
adb forward tcp:18150 tcp:4444
curl "http://127.0.0.1:18150/config"                       # 看当前（key 脱敏）
curl "http://127.0.0.1:18150/config?key=sk-xxxx"           # 写入
curl "http://127.0.0.1:18150/config?model=deepseek-chat"   # 换模型
curl "http://127.0.0.1:18150/config?key="                  # 清空 → 退回 dsh-go
```

也可以点界面顶栏那行状态文字，弹窗里填。

⚠️ 实测（假 key）：`brain ask len=15 jpeg=25577` → `HTTP 401 … api key ****-123 is invalid`，
往返 **193ms** —— 管道、base64、JSON、错误回显全都通，只等真 key。
如果真 key 报"不支持图片"，那就是端点/模型不支持视觉，改 `base`/`model` 即可（代码不用动）。

## 结构（22:30 解耦后）

原来 EyeService 什么都干（相机/HTTP/TTS/悬浮窗/写文件/直连/配置），"回答从哪来"还是
if/else 硬编码。现在按职责切开：

| 类 | 职责 | 不认识谁 |
|---|---|---|
| `EyeService` | **只做编排**：一句话进来 → 回显 → 交给 Answerer → 结果走 `say()` | 不认识 HTTP 细节、不认识模型 |
| `Answerer` | 「回答从哪来」的接口（`ask/text/reply`） | 不认识相机、不认识 UI |
| `DirectAnswerer` | 快路：当前帧 + 一句话 → `/chat/completions` | 只认 `Config` + `Frames` |
| `BridgeAnswerer` | 慢路：写 inbox，等 dsh-go 的回合 | 只认一个 `File` |
| `Config` | key / base / model 的唯一出口 + `direct()` 开关 + 脱敏描述 | — |
| `Brain` | 纯 HTTP + JSON（手写转义，不引库） | — |
| `CameraEngine` | 采集（顺带留 `lastJpeg` 给直连） | — |
| `FloatWindow` / `TypeActivity` / `VoiceActivity` | 输入/输出的其它形态 | — |

收益：加第三条路（本地模型）= 多一个 `Answerer` 实现，`EyeService` 一行不改；
换配置只碰 `Config`，不再全仓 grep `"apiKey"`。

⚠️ **教训**：验证直连时我用假 key 覆盖了用户刚在界面里填的真 key（`?key=` 是覆盖不是追加），
用户只能重填。**以后测试前先 `GET /config` 把当前 key 记下来，或直接拿现有 key 试。**

---

## 2.6.0–2.6.2（2026-11-22）：能力清单、`http`、时间、计价重做、PDF 预算

起因是模型自己列了六条「我没有的能力」（执行/网络/点击/时间/记忆/声音）。逐条查代码 +
`eye.log` 之后，结论跟它的自述**不完全一样**：

- `web_search` / `read_url` **一直在**，11-19 还成功调过四次；
- 但它在同一个会话里写下「我应该调工具。**但我这边只能输出文本**」，然后拿记忆里的旧答案顶。
  这段自我否定进了历史，之后每轮重读 —— 越用越退化。

所以这一版先解决"它不信自己有手"，再补真缺的那部分。

### 能力清单不再手写

`AGENT_NOTE` / `HYBRID_NOTE` 两份手写清单**删了**。现在 `Asker.agentNote()` 用
`Tools.capacity()` 从 `Tools.spec()` 的**名字**生成清单：加工具自动多一行，删工具自动少一行。
配套三条硬规则（说没有之前先调一次 / 历史里那句"我只能输出文本"是错的 / 用户点名就必须调），
外加一道**代码闸**：用户话里点名了某个工具（`forcedTool()`），模型却只回文字 → 强制重试一次。

### `http`：它自己发起的请求

`Net.java`，只在「Agent + 联网」的工具表里。闸门是给 prompt 注入准备的，不是给正常请求准备的：
只 https、解析后校验 IP（禁回环/私网/CGNAT/链路本地/云元数据）、禁 `user:pass@`、
手动跟重定向（≤2 跳、每跳重查）、4KB 回传、8 秒。

> 实测：`api.github.com/zen` 先 415（它自己加了 `Accept: text/plain`）再 200，它主动撤回了
> 上一轮凭记忆编的那句 —— 这就是我们要的行为。

### 时间

每轮在**用户消息尾部**追加 `〔现在 22:44 周日〕`。为什么不写 system：system 在最前面，
改一个字符整段前缀缓存就失效（命中价只有 1/50），而时间每分钟都在变。

### 计价（用户说"不准"）

对着官方价目表核了一遍，旧版有五个问题，全在 `Cost.java` 里改：

1. **只算最后一轮** —— 一次 4 轮的工具调用，旧版显示的是第 4 轮的输入，实际付了 4 份；
2. **不认缓存命中** —— `usage` 只读 `prompt_tokens`，命中价只有 1/50；
3. **没算峰谷** —— 高峰是空闲的 2×（UTC 01-04 / 06-10，工作日）；
4. **搜索计费口径过时** —— 2026-11-18 起服务端 `web_search` 不再按次收费，
   但原子接口 `/v1/tools/search` 仍然 $0.75/千次，两者必须分开；
5. **漏了文件解析费** —— $0.225/千页，一本 300 页的书是 $0.0675。

### 回答长度

默认 800 → **2000**（上限 8000）。800 token ≈ 500 汉字，讲一个知识点必被切在半句，
用户看到的就是那句莫名其妙的「回答被 token 上限截断了」。设置页多了「回答长度」一行。

### PDF / 一本书

- `PdfRenderer`（系统自带，零体积）数页数 → 卡片直接写「240 页≈17万token」；
- `Att.tokens()` 不再封顶 2 万（那是"书只有 2 万 token"的根源），改为页数 × 700；
- `contextFor()` 先给文档留位置（`docTokensThisTurn`），历史不再把书的位置吃掉；
- 上传成功时按页记**解析费**。

⚠️ **还没做**：编目（一次生成目录+要点、之后只带笔记、按需展开原文）。这是"上传一本书让它
教知识点"的关键一步，排在下一批。

### 2.6.2 顺手挖出来的三个真 bug

1. **「工具」总开关是死的**：`Config.tools()` 只在设置页和 `/status` 里显示，
   请求端从来没读过 —— 关掉之后 9 个工具照发。现在 `Caps.mute(plan)` 真的清空工具表。
2. **带附件的消息会被裁掉**：`Conv.trim` 从最新往回攒、超预算丢更早的，而"上传一本书"那条
   是最早的 → 聊几轮书就没了。现在钉住附件消息（书本身不超过预算一半时）。
3. **时间戳每轮都加**：工具循环里「继续。」后面也带 `〔现在 …〕`，白烧 token。现在只加第一句。

## 2.7.0–2.7.2（2026-11-23）：输出上限默认「自动」

用户的原话：**"我不明白你为什么要设置这个上限"** —— 他是对的。800/2000 那种默认是 v4 的
"手机上一句话别太长"，省的是几厘钱，代价是**回答被切在半句**。而 `max_tokens` 是
**含思考**的总输出额度，开"高"思考先花 1500~7664 token，2000 的额度连想都不够。

现在：

- `max_tokens = 1M − 本次输入估算 − 16K`，夹到官方 384K（**自动，默认**）；
  手设固定值仍然可以（设置 → 回答长度：自动/8K/16K/32K/65K/131K）。
- 估算输入时**跳过 base64**（一张宫格几十万字符，按字符算会把上限压到 4K ——
  那是另一种"没多少字却被截断"）。
- 兼容端点嫌 384K 太大 → 识别 400 后自动降到 16384 重试一次，不把错误丢给用户。
- 读超时 60→180 秒（socket 空闲，不是总时长）；`/ask` 90→600 秒；
  两轮之间的总预算 90/300→300/900 秒。
- 日志里多了 `max=`，一眼能看出这次到底给了多少额度。

实测（高思考 + 要求四千字）：`max=384000` / `reason_len=7664` / `lastOut=8301` / `finish=stop`。

## 2.8–2.9（2026-11-23）：对话里真的能画图了 + DSH 式用量面板

### SVG 示意图（`Svg.java`，+8KB）

模型写 ```svg 代码块 → 气泡里出现**图片**（浅底、中文标签、按屏宽缩放）。
支持 `rect/circle/ellipse/line/polyline/polygon/path(M L H V C Q Z)/text`，
`fill/stroke/stroke-width/opacity/font-size/text-anchor/style="…"`，`translate/scale`。
不支持就整块回退成代码块 —— 绝不画半张。

三个必须记下来的坑：

1. **`ImageSpan` 画不出来**：占位、行高、宽度都对，`Drawable.draw()` 就是不落屏（换
   `BitmapDrawable` 也一样）。最终把 SVG 段抽出来用 `ImageView` 装 —— 一次就对了。
2. **图形自己的 `fill` 从没被读**：只给 `<g>` 读了样式，于是每个框都按默认黑色填充，
   整张图黑成一片（界面上看就是"气泡空了一块"）。
3. **`<text>` 的 `x/y` 取晚了**：`text(p)` 把解析器推到 `</text>` 之后才读坐标，
   所有字都画在 (0,0) 叠成一坨。先取坐标、再读正文。

### 用量面板（顶栏圆环）

```
本轮输入 4,948
　· 缓存命中 128　· 未命中 4,820　· 命中率 3%
本轮输出 1,659 · 工具 0 次
本轮花费 $0.001719（缓存省下 $0.000019）
累计：输入 4,948（命中 128） · 输出 1,659
```

`usage.prompt_cache_hit_tokens`（兼容 `prompt_tokens_details.cached_tokens`）→ 命中；
`prompt_tokens − 命中` → 未命中；`completion_tokens` → 输出（**含思考**）。
累计三行落在 `Config`，重启不丢。
