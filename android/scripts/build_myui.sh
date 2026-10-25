#!/bin/bash
# Build youtu myui APK: compile -> dex -> apktool b -> inject dex+asset -> sign
set -e
cd /c/Users/fengxin/youtu_work/android
J=/d/devtool/jdk-21.0.4/bin
echo "== javac stubs =="
rm -rf out_stub && mkdir out_stub
$J/javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "../toolchain/android-all.jar" -d out_stub $(find src_stub -name "*.java") 2>&1 | head -3
[ -f out_stub/com/jiaoxiang/fangnale/utils/AesUtil.class ] || { echo STUB_COMPILE_FAILED; exit 1; }
echo "== javac =="
rm -rf out_cls && mkdir out_cls
$J/javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "out_stub;../toolchain/android-all.jar" -d out_cls $(find src -name "*.java") 2>&1 | grep -v "deprecat\|Xlint" | head -5
ls out_cls/com/muyue/ql/myui/MainActivity.class > /dev/null || { echo COMPILE_FAILED; exit 1; }
ls out_cls/com/muyue/ql/myui/PlayerEngine.class > /dev/null || { echo COMPILE_FAILED_ENGINE; exit 1; }
ls out_cls/com/muyue/ql/myui/OnlineSource.class > /dev/null || { echo COMPILE_FAILED_SOURCE; exit 1; }
echo "== d8 =="
rm -f classes7.dex classes.dex
$J/java -cp ../toolchain/d8.jar com.android.tools.r8.D8 --release --min-api 21 --output . $(find out_cls -name "*.class") 2>/dev/null | head -3 || true
mv classes.dex classes7.dex
cd /c/Users/fengxin/youtu_work
echo "== apktool b =="
rm -rf proj2/build
java -jar C:/Users/fengxin/Tools/apktool/apktool.jar b proj2 -o youtu_myui_base.apk > apktool_myui.log 2>&1 || { tail -5 apktool_myui.log; exit 1; }
echo "== inject =="
C:/Users/fengxin/.workbuddy/binaries/python/envs/default/Scripts/python.exe - << 'EOF'
import zipfile
base = 'youtu_myui_base.apk'
out = 'youtu_myui_signed.apk'
zin = zipfile.ZipFile(base)
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as zo:
    for info in zin.infolist():
        zo.writestr(zipfile.ZipInfo(info.filename, (2026, 10, 16, 0, 0, 0)), zin.read(info.filename))
    zi = zipfile.ZipInfo('classes7.dex', (2026, 10, 16, 0, 0, 0))
    zi.external_attr = 0o644 << 16
    zo.writestr(zi, open('android/classes7.dex', 'rb').read())
print('inject ok')
EOF
echo "== sign =="
java -jar uber-apk-signer.jar -a youtu_myui_signed.apk --ks ks_new.jks --ksAlias youtu --ksPass 123456 --ksKeyPass 123456 --overwrite 2>&1 | grep -E "sign success|ERROR"
echo BUILD_DONE
