#!/bin/bash
# 合成帧流探针：静止 / 平移 / 硬切 三种素材跑一遍 CallSheet.fromStream（纯 Java）。
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$HERE/.work-stream"

find "$WORK" -type f -delete 2>/dev/null || true
mkdir -p "$WORK/com/dsh/eye"
for f in Packer Tiers Tokens Pipeline Cuts CallSheet CallStream CallCapture EyeLog; do
  cp "$ROOT/src/com/dsh/eye/$f.java" "$WORK/com/dsh/eye/"
done
cp "$HERE/StreamProbe.java" "$HERE/TierProbe.java" "$WORK/com/dsh/eye/"

cd "$WORK"
javac -encoding UTF-8 -d out com/dsh/eye/*.java
java -Dfile.encoding=UTF-8 -cp out com.dsh.eye.StreamProbe
