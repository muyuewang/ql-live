#!/bin/bash
# 把 android/patches/ 下的手工 smali 补丁覆盖回 apktool 解包工程。
#
# 背景：lite_smali 整体不入库（84MB / 7752 文件，绝大多数是 apktool 反编译出的
# 第三方代码）。但其中有少量**手工改过的 smali**，一旦重解包就会被覆盖丢失。
# 这些文件唯一真源在 android/patches/，本脚本负责把它们贴回去。
#
# 用法：
#   bash android/scripts/apply_patches.sh [lite_smali 目录]
# 默认目标为 code/android/lite_smali
set -e

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID="$(cd "$HERE/.." && pwd)"
TARGET="${1:-$ANDROID/lite_smali}"

if [ ! -d "$TARGET" ]; then
  echo "目标目录不存在: $TARGET"
  echo "先用 apktool d 解包出 lite_smali 再跑本脚本。"
  exit 1
fi

# 补丁文件名 -> 它在工程里的相对路径
# 新增补丁时，在这里加一行即可。
apply() {
  local name="$1" rel="$2"
  local src="$ANDROID/patches/$name"
  local dst="$TARGET/$rel"
  if [ ! -f "$src" ]; then
    echo "  [跳过] 补丁不存在: $name"
    return
  fi
  mkdir -p "$(dirname "$dst")"
  cp "$src" "$dst"
  echo "  [已贴] $rel"
}

echo "== 应用 smali 补丁 -> $TARGET =="
apply "JsLayer.smali" "smali_classes3/com/jiaoxiang/fangnale/jsparse/JsLayer.smali"
echo "PATCH_DONE"

# 自检：确认关键补丁确实在目标文件里
if grep -q "QL perf patch" "$TARGET/smali_classes3/com/jiaoxiang/fangnale/jsparse/JsLayer.smali" 2>/dev/null; then
  echo "自检通过：JsLayer 的 init 复用补丁已就位"
else
  echo "自检失败：JsLayer 补丁未生效"
  exit 1
fi