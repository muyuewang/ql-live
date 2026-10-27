#!/bin/bash
# 只做「改 android/src → classes7.dex → android/lite_smali/smali_classes7」这三步，
# 不碰 proj2 / 不碰 apk，方便在 myui 层做增量改动。
#
# 2026-10-27 目录重组后：源码在 code/android/src，工具在 code/tools/。
# 这里按脚本自身位置推导根目录，不再硬编码绝对路径。
set -e

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID="$(cd "$HERE/.." && pwd)"        # code/android
CODE="$(cd "$ANDROID/.." && pwd)"        # code
cd "$CODE"

J=${JAVA_HOME_BIN:-/d/devtool/jdk-21.0.4/bin}
SRC=android/src
STUB=android/src_stub
AJ=tools/toolchain/android-all.jar
BUILD=android/build_tmp

mkdir -p "$BUILD"

echo "== javac stubs =="
rm -rf "$BUILD/out_stub" && mkdir -p "$BUILD/out_stub"
$J/javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$AJ" -d "$BUILD/out_stub" \
  $(find "$STUB" -name "*.java") 2>&1 | grep -v "^Note:" | head -5 || true

echo "== javac myui =="
rm -rf "$BUILD/out_cls" && mkdir -p "$BUILD/out_cls"
$J/javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$BUILD/out_stub;$AJ" -d "$BUILD/out_cls" \
  $(find "$SRC" -name "*.java") 2>&1 | grep -vi "deprecat\|Xlint\|^Note:" | head -10 || true

for c in MainActivity PlayerEngine OnlineSource JsDec; do
  [ -f "$BUILD/out_cls/com/muyue/ql/myui/$c.class" ] || { echo "COMPILE_FAILED: $c"; exit 1; }
done

echo "== d8 =="
rm -f "$BUILD/classes.dex" "$BUILD/classes7.dex"
$J/java -cp tools/toolchain/d8.jar com.android.tools.r8.D8 --release --min-api 21 \
  --output "$BUILD" $(find "$BUILD/out_cls" -name "*.class") 2>&1 | head -5 || true
mv "$BUILD/classes.dex" "$BUILD/classes7.dex"
ls -l "$BUILD/classes7.dex" | awk '{print "classes7.dex", $5, "bytes"}'

echo "== baksmali -> android/lite_smali/smali_classes7 =="
rm -rf android/lite_smali/smali_classes7
$J/java -jar tools/toolchain/baksmali.jar d "$BUILD/classes7.dex" -o android/lite_smali/smali_classes7 2>&1 | head -5 || true
[ -f android/lite_smali/smali_classes7/com/muyue/ql/myui/JsDec.smali ] || { echo "BAKSMALI_FAILED"; exit 1; }
echo "smali 文件数: $(find android/lite_smali/smali_classes7 -name '*.smali' | wc -l)"
echo DEX_DONE