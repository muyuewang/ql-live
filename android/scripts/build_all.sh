#!/usr/bin/env bash
# 全量重建：从 dump 出的 apk 重新解包 -> 打补丁 -> 打包 -> 签名
#
# 2026-10-27 目录重组后：中间产物统一放 悠兔/out/，工具在 code/tools/。
# 这里按脚本自身位置推导根目录，不再硬编码绝对路径。
set -e

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID="$(cd "$HERE/.." && pwd)"        # code/android
CODE="$(cd "$ANDROID/.." && pwd)"        # code
WS="$(cd "$CODE/.." && pwd)"             # 悠兔（工作空间）
cd "$CODE"

# out/ 按月归档（out/2026-10/...）。默认取最新月份目录，
# 需要写历史月份时用 OUT_MONTH=2026-09 bash build_all.sh 覆盖。
OUTMONTH="${OUT_MONTH:-$(ls -1d "$WS"/out/20??-?? 2>/dev/null | sort | tail -1 | xargs -r basename)}"
[ -n "$OUTMONTH" ] || { echo "找不到 out/YYYY-MM 目录，请先 mkdir -p \"$WS/out/$(date +%Y-%m)\""; exit 1; }
OUT="$WS/out/$OUTMONTH"
echo "out 归档月：$OUTMONTH"

PY="${PYTHON_BIN:-/c/Users/fengxin/.workbuddy/binaries/python/versions/3.13.12/python.exe}"
APK="C:/Users/fengxin/Tools/apktool/apktool.jar"
DUMPED="$OUT/artifacts/youtu_dumped.apk"
PROJ="$OUT/decompile/proj2"
OUTAPK="$OUT/artifacts/build/youtu_v2.apk"
export OUT_MONTH="$OUTMONTH"   # 传给被调用的 python 脚本

echo "==== 1) 合成 dumped apk ===="
"$PY" android/scripts/build_v2.py

echo "==== 2) apktool 解包(仅资源) ===="
rm -rf "$PROJ"
java -Xmx4g -jar "$APK" d -s -f "$DUMPED" -o "$PROJ" >/dev/null 2>&1

echo "==== 3) 打清单补丁 ===="
"$PY" android/scripts/patch_manifest.py

echo "==== 3b) 裁剪界面 ===="
"$PY" android/scripts/patch_ui.py

echo "==== 4) 构建 ===="
mkdir -p "$WS/out/artifacts/build"
rm -rf "$PROJ/build"
java -Xmx4g -jar "$APK" b "$PROJ" -o "$OUTAPK" 2>&1 | tail -2

echo "==== 5) 签名 ===="
jarsigner -keystore android/keys/ks.jks -storepass 123456 -keypass 123456 \
  -sigalg SHA256withRSA -digestalg SHA-256 "$OUTAPK" youtu >/dev/null 2>&1
ls -la "$OUTAPK"