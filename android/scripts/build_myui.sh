#!/bin/bash
# Build youtu myui APK: compile -> dex -> apktool b -> inject dex -> sign
#
# 2026-10-27 目录重组后：源码在 code/android/src，工具在 code/tools/，
# 反编译工程在 悠兔/out/<YYYY-MM>/decompile/proj2，产物落 悠兔/out/<YYYY-MM>/artifacts/build。
# 这里按脚本自身位置推导根目录，不再硬编码绝对路径。
set -e

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID="$(cd "$HERE/.." && pwd)"        # code/android
CODE="$(cd "$ANDROID/.." && pwd)"        # code
WS="$(cd "$CODE/.." && pwd)"             # 悠兔（工作空间）
cd "$CODE"

J=${JAVA_HOME_BIN:-/d/devtool/jdk-21.0.4/bin}
PY="${PYTHON_BIN:-/c/Users/fengxin/.workbuddy/binaries/python/versions/3.13.12/python.exe}"
APKTOOL="C:/Users/fengxin/Tools/apktool/apktool.jar"
AJ=tools/toolchain/android-all.jar
BUILD=android/build_tmp

# out/ 按月归档（out/2026-10/...）。默认取最新月份目录，
# 需要写历史月份时用 OUT_MONTH=2026-09 bash build_myui.sh 覆盖。
OUTMONTH="${OUT_MONTH:-$(ls -1d "$WS"/out/20??-?? 2>/dev/null | sort | tail -1 | xargs -r basename)}"
[ -n "$OUTMONTH" ] || { echo "找不到 out/YYYY-MM 目录，请先 mkdir -p \"$WS/out/$(date +%Y-%m)\""; exit 1; }
OUT="$WS/out/$OUTMONTH"
echo "out 归档月：$OUTMONTH"

PROJ="$OUT/decompile/proj2"
OUTDIR="$OUT/artifacts/build"
LOGDIR="$WS/log/debug/build"

mkdir -p "$BUILD" "$OUTDIR" "$LOGDIR"

echo "== javac stubs =="
rm -rf "$BUILD/out_stub" && mkdir -p "$BUILD/out_stub"
$J/javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$AJ" -d "$BUILD/out_stub" \
  $(find android/src_stub -name "*.java") 2>&1 | head -3
[ -f "$BUILD/out_stub/com/jiaoxiang/fangnale/utils/AesUtil.class" ] || { echo STUB_COMPILE_FAILED; exit 1; }

echo "== javac =="
rm -rf "$BUILD/out_cls" && mkdir -p "$BUILD/out_cls"
$J/javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$BUILD/out_stub;$AJ" -d "$BUILD/out_cls" \
  $(find android/src -name "*.java") 2>&1 | grep -v "deprecat\|Xlint" | head -5
for c in MainActivity PlayerEngine OnlineSource; do
  [ -f "$BUILD/out_cls/com/muyue/ql/myui/$c.class" ] || { echo "COMPILE_FAILED: $c"; exit 1; }
done

echo "== d8 =="
rm -f "$BUILD/classes7.dex" "$BUILD/classes.dex"
$J/java -cp tools/toolchain/d8.jar com.android.tools.r8.D8 --release --min-api 21 \
  --output "$BUILD" $(find "$BUILD/out_cls" -name "*.class") 2>/dev/null | head -3 || true
mv "$BUILD/classes.dex" "$BUILD/classes7.dex"

echo "== apktool b =="
BASE="$OUTDIR/youtu_myui_base.apk"
rm -rf "$PROJ/build"
java -jar "$APKTOOL" b "$PROJ" -o "$BASE" > "$LOGDIR/apktool_myui.log" 2>&1 \
  || { tail -5 "$LOGDIR/apktool_myui.log"; exit 1; }

echo "== inject =="
SIGNED="$OUTDIR/youtu_myui_signed.apk"
"$PY" - "$BASE" "$SIGNED" "$BUILD/classes7.dex" << 'EOF'
import sys, zipfile
base, out, dexpath = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(base)
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as zo:
    for info in zin.infolist():
        zo.writestr(zipfile.ZipInfo(info.filename, (2026, 10, 16, 0, 0, 0)), zin.read(info.filename))
    zi = zipfile.ZipInfo('classes7.dex', (2026, 10, 16, 0, 0, 0))
    zi.external_attr = 0o644 << 16
    zo.writestr(zi, open(dexpath, 'rb').read())
print('inject ok')
EOF

echo "== sign =="
java -jar tools/uber-apk-signer.jar -a "$SIGNED" \
  --ks android/keys/ks_new.jks --ksAlias youtu --ksPass 123456 --ksKeyPass 123456 --overwrite \
  2>&1 | grep -E "sign success|ERROR|Successfully"
echo BUILD_DONE