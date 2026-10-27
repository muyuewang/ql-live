"""工作空间路径解析（供 scripts/*.py 共用）。

out/ 按月归档为 out/YYYY-MM/{decompile,artifacts,screenshots,test}。
取值优先级：环境变量 OUT_MONTH > out/ 下最新的 YYYY-MM 目录。
需要操作历史月份时：OUT_MONTH=2026-09 python patch_ui.py
"""
import os
import glob

WS = r"C:/Users/fengxin/WorkBuddy/悠兔"


def out_month():
    m = os.environ.get("OUT_MONTH", "").strip()
    if m:
        return m
    cand = sorted(os.path.basename(p) for p in glob.glob(os.path.join(WS, "out", "20??-??")))
    if not cand:
        raise SystemExit(
            "找不到 out/YYYY-MM 目录，请先创建：mkdir -p \"%s/out/%s\""
            % (WS, __import__("datetime").date.today().strftime("%Y-%m"))
        )
    return cand[-1]


OUT = os.path.join(WS, "out", out_month())
DECOMPILE = os.path.join(OUT, "decompile")
ARTIFACTS = os.path.join(OUT, "artifacts")
SCREENSHOTS = os.path.join(OUT, "screenshots")
TESTS = os.path.join(OUT, "test")

PROJ2 = os.path.join(DECOMPILE, "proj2")          # apktool 重打包工程
DEXDUMP = os.path.join(DECOMPILE, "dexdump")      # 脱壳出的原始 dex
DUMPED_APK = os.path.join(ARTIFACTS, "youtu_dumped.apk")

LOG = os.path.join(WS, "log")
LOG_RUN = os.path.join(LOG, "run")
LOG_DEBUG = os.path.join(LOG, "debug")

DIST = os.path.join(WS, "dist")


def dist_version(platform, version):
    """dist/<platform>/<version> 目录，不存在则创建。"""
    p = os.path.join(DIST, platform, version)
    os.makedirs(p, exist_ok=True)
    return p