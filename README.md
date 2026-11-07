# SeeNow Phone Version

> **手机版 see-now**：保留桌面版原有的「打分 → 挑帧 → 多帧宫格 → 时间线」能力，
> 只把采集源从电脑屏幕换成手机摄像头。
>
> 导盲是**可选模式**（`mode:"guide"`），后续再定制；默认 `mode:"general"`，
> 行为和桌面 `see_now` 一致 —— 其他用户装上去就是原来那套东西。

- npm：`dsh-see-now-phone`
- 工具名：`see_phone`
- 依赖：Node ≥ 20；手机场景另需 Termux + Termux:API

---

## 1. 和 dsh-see-now 的关系

**同一套引擎，两个采集源。**

| | `dsh-see-now`（桌面） | `dsh-see-now-phone` |
|---|---|---|
| 采集 | 屏幕：ddagrab / gdigrab / portal / PipeWire | **摄像头**：Termux 抓拍 / MJPEG / RTSP |
| 引擎 | 打分、切点、装箱 DP、拼图、PNG、token 换算 | **整块 vendor 过来，一行没改** |
| 输出 | 多帧宫格（按画面变化挑帧） | **一样** |
| 动作 | watch / peek / status / stop | **一样**（另有 `look` 取单帧） |
| 语音 | 可选 `say` | 可选 `say`（有 TTS 就念） |

引擎 `src/engine/` 的 9 个文件来自 `dsh-see-now` 0.1.5，零宿主依赖（只用 `node:zlib`）。
**采集层整个重写**：Android 上普通进程看不到 `/dev/video*`，桌面那套 ffmpeg 设备枚举一个都用不了。

### 为什么独立成包而不是给 see-now 加 `--camera`

采集配置、默认值、发版节奏都不同；导盲那套后续还要按真机反馈反复调阈值，
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
  "width": 336,
  "height": 448,
  "fps": 8,
  "grabIntervalMs": 70,
  "mode": "general"
}
```

---

## 4. 工具用法

```js
see_phone({ action: "watch" })                    // 默认：等多帧宫格（和桌面 see_now 一样）
see_phone({ action: "peek" })                     // 不等待，领走已攒好的图
see_phone({ action: "look" })                     // 单帧「此刻」，用户插话时用
see_phone({ action: "status" })                   // 后端、帧率、抓拍成功/失败数、上次错误
see_phone({ action: "stop" })                     // 放掉摄像头，别一直耗电
see_phone({ action: "look", mode: "guide" })      // 导盲话术（后续定制，默认不用）
```

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
- [x] 插件 `see_phone` 已装进 dsh-go（`plugin_manager install_bundle`）
- [x] 插件已升级到 `http-mjpeg`（`remove_bundle` → `install_bundle`），
      配置已切到 `127.0.0.1:4444/video/mjpeg`
- [x] **Termux 整层退役**：采集链只剩 IP Camera App 一个外部程序

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
