# SeeNow Phone Version

> **手机版 see-now**：保留桌面版原有的「打分 → 挑帧 → 多帧宫格 → 时间线」能力，
> 只把采集源从电脑屏幕换成手机摄像头。
>
> **两种读者，显式选**：默认 `mode:"normal"` —— 用户自己看得见画面，按普通会话说；
> `mode:"guide"` 才是导盲（看不见 → 只给结论和动作）。0.2.x 把导盲当成唯一定位写死，
> 于是点一下「看一眼」也被当盲人接话 —— 0.3.0 修的就是这个。

- npm：`dsh-see-now-phone`
- 工具名：`see_phone`
- 依赖：Node ≥ 20；手机场景另需「看见」App（`phone/dsh-eye`，一个 App 顶摄像头 + TTS）

---

## 1. 和 dsh-see-now 的关系

**同一套引擎，两个采集源。**

| | `dsh-see-now`（桌面） | `dsh-see-now-phone` |
|---|---|---|
| 采集 | 屏幕：ddagrab / gdigrab / portal / PipeWire | **摄像头**：MJPEG / 抓拍 / RTSP |
| 引擎 | 打分、切点、装箱 DP、拼图、PNG、token 换算 | **整块 vendor 过来**，随桌面版一起升级 |
| 输出 | 多帧宫格（按画面变化挑帧） | **一样** |
| 动作 | watch / peek / status / stop | **一样**（另有 `look` 取单帧） |
| 语音 | 可选 `say` | 可选 `say`（有 TTS 就念） |
| 读者 | 看得见屏幕的人 | `normal` 看得见 / `guide` 看不见 |

引擎 `src/engine/` 的 9 个文件来自 `dsh-see-now` **0.2.1**（0.3.0 同步了
`pack.solveSkyline` / `packTiers` / `tierCurve` / `tierMode` 那批改进），零宿主依赖（只用 `node:zlib`）。
**采集层整个重写**：Android 上普通进程看不到 `/dev/video*`，桌面那套 ffmpeg 设备枚举一个都用不了。

### 为什么独立成包而不是给 see-now 加 `--camera`

采集配置、默认值、发版节奏都不同；导盲那套还要按真机反馈反复调阈值，
不该带着"看视频"的用户一起发版。

---

## 2. 手机上的架构（当前版本）

**采集链只剩一个外部程序。** 插件内置纯 JS 的 JPEG 解码器
（[jpeg-js](https://github.com/jpeg-js/jpeg-js) 0.4.4，BSD-3-Clause，40KB，已 vendor 到
`src/cam/vendor/jpeg-js/`），自己直连开源 IP Camera App 的 MJPEG 流：

```
Android IP Camera App (MIT, :4444/video/mjpeg, ~17fps)
  → 插件 http-mjpeg 后端：SOI/EOI 抠帧 → jpeg-js 解码 → cover 缩放 → rgb24
  → 打分 → 切点 → 装箱 DP → 多帧宫格 → PNG → 宿主附件
```

**Termux / python / ffmpeg / watchdog 全部不再需要**。这是刻意收敛的结果：
早期版本把解码放在 Termux 的几个后台进程里，华为锁屏会把它们一次性全清掉，
整条链就断。现在插件跟着 dsh-go 活，唯一要保住的是 IP Camera App（它有
`foregroundServiceType="camera"` 前台服务）。

锁屏回来只要**点开一下 Android IP Camera App**，相机就重新接管；
PC 侧也有 `phone/revive.sh` 一键拉起。

## 3. 后端

| 后端 | 帧率 | 需要 | 说明 |
|---|---|---|---|
| **`http-mjpeg`** | **8–13 fps** | 只要 IP Camera App | **手机默认**，插件内置解码，零外部进程 |
| `http-raw` | 12–20 fps | Termux + 帧服务 | 旧链路，保留作回退（8123） |
| `termux` | ~1 fps | Termux + Termux:API | 插件自己抓拍（只在插件直接跑在 Termux 里时用） |
| `mjpeg` / `rtsp` | 15–30 fps | 任意流源 | 插件侧需 ffmpeg（PRoot 里没有，慎用） |
| `v4l2` / `avfoundation` / `dshow` | 原生 | 桌面摄像头 | PC 调试 |
| `file` / `testsrc` | — | — | 回归测试 |

配置 `~/.dsh/see-phone.json`（或 `DSH_SEE_PHONE_CONFIG='{"backend":"testsrc"}'`）：

```json
{
  "backend": "http-mjpeg",
  "url": "http://127.0.0.1:4444/video/mjpeg",
  "fps": 8,
  "grabIntervalMs": 70,
  "rotate": 90,
  "camera": "back",
  "mode": "normal"
}
```

> `width` / `height` 不写就按模式取默认（normal 420×560、guide 336×448，**两者都是 197 token**）；
> 写死了就以你的为准，模式不再改画布。

### `rotate` 必须是 90（否则画面是歪的）

Camera2 的 sensor 成像相对手机自然方向转了 90°。不转的话 IP Camera App 输出
800×600 横图，`cover` 缩到 336×448 会**横向裁掉 44%** 的画面，而且图里的
"左右前后"全是错的。转 90° 后源是 600×800 = 3:4，和 336×448 **同比例，零裁切**。

插件启动时会把 `rotate` / `camera` / `resolution` / `torch` 拼成查询串发给
IP Camera App（`GET /?camera=back&rotate=90`，App 侧持久化），再开始取流。
`status` 里 `preset:{tried,ok}` 能看到下发结果。

换机型发现图歪了就改这个值（0 / 180 / 270），不用改代码。

---

## 4. 工具用法

```js
see_phone({ action: "watch" })                    // 默认：等多帧宫格（和桌面 see_now 一样）
see_phone({ action: "peek" })                     // 不等待，领走已攒好的图
see_phone({ action: "look" })                     // 单帧「此刻」，用户插话时用
see_phone({ action: "status" })                   // 后端、帧率、抓拍成功/失败数、模式、上次错误
see_phone({ action: "stop" })                     // 放掉摄像头，别一直耗电
see_phone({ action: "look", mode: "guide" })      // 导盲话术（用户看不见时才用）
```

### 两种模式（0.3.0）

| | `normal`（默认） | `guide` |
|---|---|---|
| 读者 | 用户自己看得见画面 | 用户看不见（导盲） |
| 附文 | 宫格时间线 + 「你自己看得见，别替他描述」 | 先风险、再通路、最后动作，一两句 |
| 画布 | 420×560 | 336×448 |
| App 朗读 | 只上字幕 | 默认念出来 |

优先级：**调用参数 > App 顶栏开关 > `see-phone.json` 的 `mode` > normal**。
App 的开关写在 `/sdcard/Download/DSH-Go/mode.txt`，插件每次工具调用都读一次 ——
点一下开关，下一次调用就换口吻，不用重启插件、也不用重连 dsh-go。
旧值 `general` 等同 `normal`（存量配置不会炸）。

`watch` 的阻塞就是"暂停"，回完话再调一次；静止画面会一直攒着不打扰你（省 token 的设计）。
配置改了不用再重载插件：每次调用会比对 `see-phone.json`，变了就自动重建采集会话；
也可以显式 `see_phone({action:"reload"})`。（0.1.x 早期版本只在启动时读一次配置，已修。）

---

## 5. 实测性能

真机：HUAWEI nova 11（FOA-AL00），HarmonyOS 4.2 / Android 12，arm64。

| 环节 | 实测 |
|---|---|
| IP Camera MJPEG 源 | **~17 fps**（3 秒 49 帧） |
| Termux ffmpeg → rgb24 | 336×448×3 = **451584 B**，精确对上 |
| `/frame` 端到端 | **~25 ms** |
| 帧服务内部计数 | **19.8 fps**（`ok=382 fail=0 age=0.03`） |
| **插件端到端（新后端）** | **51 帧 / 6 秒 = 8.47 fps**，出 4 帧宫格，0 失败 |

⚠️ `grabIntervalMs` 默认 **70ms**（`http-raw` 钳到 80ms）。抓拍时代的 220ms
会把 20fps 的源压成 4.4fps —— 这个坑实测踩过。
纯 JS 解码比 ffmpeg 稍慢（8.47 vs 12.95fps）；把 IP Camera App 的分辨率调到
640×480 能再提一截，因为 jpeg-js 的耗时跟源像素数成正比。

**悬浮预览**：手机浏览器直接打开 `http://127.0.0.1:4444/`（App 自带 Web 面板），
或从最近任务把浏览器切成小窗/悬浮窗，不用编译原生 overlay App。

其他边界：
- 单帧 336×448 ≈ 130 token，**图会留在会话历史里每轮重发**；长时间看要留意
- 保持 IP Camera App 在跑（别从最近任务划掉），断流时插件会自动重连
- 用完记得 `stop`；持续采集会耗电发热

## 6. 当前装机进度

- [x] dsh-go APK 装上（本机模式），引导走完、引擎启动
- [x] Termux + Termux:API + Pillow + ffmpeg（`libc++` 27c→30 后能装）
- [x] **开源 MIT 的 IP Camera App 装上**，关掉认证/TLS → 纯 HTTP MJPEG
- [x] `cam_server.py` 流模式跑起来：`/frame`、`/jpg`、`/stream`、`/view`
- [x] **端到端 12.95fps** 验证通过，多帧宫格出图
- [x] 插件 `see_phone` 已装进 dsh-go
- [x] 插件已升级到 `http-mjpeg`，配置已切到 `127.0.0.1:4444/video/mjpeg`
- [x] **Termux 整层退役**：采集链只剩 IP Camera App 一个外部程序
- [x] **0.1.2 真机验收（2026-11-07 20:45）**：6.3fps / 解码 25ms / 首帧 455ms /
      0 失败 / 出 8 帧宫格；`rotate=90` 修好方向，`preset.ok=true`

装/升走 `dsh plugin --profile web add <tgz>`，装完**必须重启引擎**。

## 开源与商用

摄像头流 App：MIT License，允许商用、修改、再分发，无 copyleft 义务。
从[官方 GitHub Releases](https://github.com/DigitallyRefined/android-ip-camera/releases) 下载，
校验 SHA-256 `0e9d5fb859b9dc2d77f401f31964c412b22d6d787b88556a3001ea3b79f099fc`。
插件本体 Apache-2.0，引擎同 `dsh-see-now`；内置
[jpeg-js](https://github.com/jpeg-js/jpeg-js) 0.4.4 为 BSD-3-Clause
（版权声明保留在 `src/cam/vendor/jpeg-js/LICENSE`）。三者均允许商用。

## 7. 开发

```bash
npm run check:syntax      # 全量语法体检
npm test                  # 宿主契约测试（假 ctx，6 条）
npm run smoke testsrc 6   # 合成源跑通整条管线
DSH_SEE_PHONE_CONFIG='{"backend":"http-raw","url":"http://127.0.0.1:8123/frame"}' npm run smoke http-raw 10
```

## License

Apache-2.0。`src/engine/` 来自 [dsh-see-now](https://github.com/SAOS-3624/dsh-see-now)，同一许可证。
