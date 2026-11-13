#!/bin/bash
# 对拍：src/com/dsh/eye/Packer.java vs dsh-see-now/src/pack.js
#
# 用途：改了 Packer（或者升级了电脑版的 pack.js）之后跑一遍，确认两边还是同一套排布。
# **PackerClient 不复制 Packer.java** —— 直接编 src 里那份，避免副本漂移。
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"          # phone/dsh-eye
JS_SRC="$(cd "$ROOT/../../dsh-see-now/src" && pwd)"
WORK="$HERE/.work"
rm -rf "$WORK"; mkdir -p "$WORK/com/dsh/eye"
cp "$ROOT/src/com/dsh/eye/Packer.java" "$WORK/com/dsh/eye/"
cp "$HERE/PackerCli.java" "$WORK/com/dsh/eye/"
cp "$ROOT/../../dsh-see-now/src/pack.js" "$WORK/pack.js" 2>/dev/null || true

cd "$WORK"
javac -encoding UTF-8 -d out com/dsh/eye/Packer.java com/dsh/eye/PackerCli.java
"${NODE:-node}" "$HERE/gen-cases.mjs"
java -cp out com.dsh.eye.PackerCli < cases.txt > java.txt
"${NODE:-node}" "$HERE/js-side.mjs"
if diff --strip-trailing-cr js.txt java.txt > d.txt; then
  echo "✓ 对拍通过（$(wc -l < cases.txt) 组用例，逐字节一致）"
else
  echo "✗ 对拍失败，前 20 行差异："
  head -20 d.txt
  exit 1
fi
