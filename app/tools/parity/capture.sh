#!/bin/bash
# 抓帧节奏离线探针（CallCapture，纯 Java，不碰 Android）。
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$HERE/.work-capture"

find "$WORK" -type f -delete 2>/dev/null || true
mkdir -p "$WORK/com/dsh/eye"
cp "$ROOT/src/com/dsh/eye/CallCapture.java" "$WORK/com/dsh/eye/"
cp "$HERE/CaptureProbe.java" "$WORK/com/dsh/eye/"

cd "$WORK"
javac -encoding UTF-8 -d out com/dsh/eye/*.java
java -Dfile.encoding=UTF-8 -cp out com.dsh.eye.CaptureProbe
