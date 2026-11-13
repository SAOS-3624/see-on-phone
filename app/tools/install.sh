#!/bin/bash
# install.sh —— 一条命令装完「鱼眼」：**全程自己点弹窗，绝不留下等人点的框**。
#
# 目标：从敲下命令到出结果 **≤ 40 秒**。
#
# 踩过的四个坑（都写进代码了）：
#   1. 华为装包有两道弹窗（风险提示 → 纯净模式），只点第一道会退回纯净模式，
#      表现像"装不上"（`dumpsys` 的 versionCode 不变）。**两道都要点**。
#   2. `pm install` 在本机沙箱里会被 SIGTERM，但**设备上的安装会话还在** ——
#      所以不能"装完再看"，只能**边等边点**。
#   3. 应用市场开着的时候，"纯净模式"那道是它**伪造的**：按钮 `enabled="false"`，
#      点了不装（看着像装上了，`lastUpdateTime` 不动）→ 先 `pm disable-user`。
#   4. ⚠️ **`uiautomator dump` 在界面动画期间会卡 8~25 秒**。之前脚本"每 2 秒 dump 一次
#      直到成功"，跑成十分钟，被外层超时 SIGTERM —— 弹窗正好留在屏幕上，
#      用户看到的就是"又要我点"。所以：**全程最多 dump 两次**，其余靠 `dumpsys` 轮询。
#
# 用法：
#   bash tools/install.sh                 # 装 ../dsh-eye.apk
#   bash tools/install.sh path/to/x.apk
#
# 退出码：0 = 装上了且版本对得上；1 = 没装上（已把弹窗关掉并打印原因）。
set -u

# Git Bash 会把 `/data/local/tmp/x.apk` 这种"看起来像路径"的参数改写成 Windows 路径
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

HERE="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-adb}"
# PATH 上没有 adb 就按 SDK 环境变量找（不写死任何本机路径）
if ! command -v "$ADB" >/dev/null 2>&1; then
  for c in "${ANDROID_HOME:-}/platform-tools/adb" "${ANDROID_SDK:-}/platform-tools/adb"; do
    [ -x "$c" ] && ADB="$c" && break
  done
fi
command -v "$ADB" >/dev/null 2>&1 || { echo "找不到 adb：装 platform-tools 或设 ADB=<路径>"; exit 1; }
PKG=com.dsh.eye
APK="${1:-$HERE/dsh-eye.apk}"
MANIFEST="$HERE/AndroidManifest.xml"

WANT_CODE=$(grep -o 'android:versionCode="[0-9]*"' "$MANIFEST" | head -1 | grep -o '[0-9]*')
WANT_NAME=$(grep -o 'android:versionName="[^"]*"' "$MANIFEST" | head -1 | sed 's/.*="//;s/"//')

WORK="$HERE/.tmp/install-work"
mkdir -p "$WORK"

say() { echo "[install] $*"; }
AS()  { timeout 15 "$ADB" shell "$@" 2>/dev/null; }

cur_code() { AS "dumpsys package $PKG | grep -m1 versionCode" | grep -o '[0-9]*' | head -1; }

# 抓一次 UI，返回「继续安装」按钮中心坐标（没有就空）。**硬超 6 秒**，卡住就算了。
button_center() {
  timeout 6 "$ADB" shell "uiautomator dump /sdcard/_inst.xml" >/dev/null 2>&1
  timeout 6 "$ADB" exec-out cat /sdcard/_inst.xml > "$WORK/ui.xml" 2>/dev/null
  [ -s "$WORK/ui.xml" ] || return 0
  python - "$WORK/ui.xml" <<'PY'
import re, sys
try:
    x = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
except Exception:
    sys.exit(0)
for m in re.finditer(r'text="继续安装"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
    break
PY
}

# 点一次「继续安装」：有坐标就用坐标；dump 不出来就退回已知位置（这台机器一直是这两个）
tap_continue() {
  local c
  c=$(button_center)
  if [ -z "$c" ]; then
    case "${1:-0}" in
      1) c="777 2157" ;;
      2) c="542 1996" ;;
      *) return 1 ;;
    esac
  fi
  set -- $c
  say "点「继续安装」($1,$2)"
  AS "input tap $1 $2"
  return 0
}

cleanup_dialog() {
  if [ -n "$(button_center)" ]; then
    say "收尾：把残留的安装弹窗关掉"
    AS "input keyevent 4"
    sleep 1
    AS "input keyevent 4"
  fi
}

# ---------------------------------------------------------------- 开始

[ -f "$APK" ] || { echo "[install] ✗ 找不到 APK：$APK" >&2; exit 1; }
timeout 10 "$ADB" get-state >/dev/null 2>&1 || { echo "[install] ✗ 设备没连上" >&2; exit 1; }
say "目标 $PKG $WANT_NAME (versionCode $WANT_CODE)"

if [ "$(cur_code)" = "$WANT_CODE" ]; then
  say "设备上已经是这个版本，跳过"
  cleanup_dialog
  exit 0
fi

# 市场开着就关掉（它会伪造一道点了没用的弹窗）
#
# ⚠️ 还要**先把市场进程干掉**：实测它前台的验证码界面（CaptchaActivity）会一直压在上面，
#    光 `disable-user` 只是禁止下次启动，眼前那个界面还在 —— 表现就是 pm install 返回空、
#    版本永远不变（我在 0.24.0 上卡了三轮才发现是验证码界面）。
AS "am force-stop com.huawei.appmarket" >/dev/null
AS "pm disable-user com.huawei.appmarket" >/dev/null

APK_WIN="$(cygpath -w "$APK" 2>/dev/null || echo "$APK")"
say "推送…"
timeout 45 "$ADB" push "$APK_WIN" /data/local/tmp/_dsh.apk >/dev/null 2>&1 \
  || { echo "[install] ✗ 推送失败" >&2; exit 1; }
AS "am force-stop $PKG" >/dev/null

say "安装…"
( timeout 55 "$ADB" shell "pm install -r -d /data/local/tmp/_dsh.apk" >"$WORK/pm.log" 2>&1 & )

sleep 4
tap_continue 1
sleep 3
tap_continue 2

ok=0
for i in $(seq 1 8); do
  if [ "$(cur_code)" = "$WANT_CODE" ]; then ok=1; break; fi
  sleep 2
  # 第 3、5 轮再补点一次（有些机器弹窗出现得慢）
  if [ "$i" = "3" ] || [ "$i" = "5" ]; then tap_continue "$i" >/dev/null 2>&1; fi
done

if [ "$ok" = "1" ]; then
  name=$(AS "dumpsys package $PKG | grep -m1 versionName" | sed 's/.*=//')
  # 装完可能停在"安装完成"页，按一次返回离开它
  AS "input keyevent 4"
  cleanup_dialog
  # ⚠️ 刻意**不**把应用市场 enable 回来：启用状态下那道"纯净模式"弹窗是它伪造的
  #    （按钮 enabled=false，点了不装）。装一次禁一次，别来回折腾。
  #    想恢复自己敲：adb shell pm enable com.huawei.appmarket
  say "✓ 装上了：versionCode=$WANT_CODE versionName=$name"
  exit 0
fi

echo "--- pm install 输出 ---" >&2
tail -5 "$WORK/pm.log" >&2 2>/dev/null
say "✗ 没装上（设备上还是 $(cur_code)）"
cleanup_dialog
AS "pm enable com.huawei.appmarket" >/dev/null
exit 1
