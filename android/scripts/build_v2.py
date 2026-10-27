import zipfile, os, glob, hashlib, zlib, struct

# out/ 按月归档：优先用环境变量 OUT_MONTH，否则取 out/ 下最新的 YYYY-MM 目录
_WS = r"C:/Users/fengxin/WorkBuddy/悠兔"
_m = sorted(os.path.basename(p) for p in glob.glob(os.path.join(_WS, "out", "20??-??")))
_O = os.path.join(_WS, "out", os.environ.get("OUT_MONTH") or _m[-1])

SRC = r"C:/Users/fengxin/Downloads/悠兔电视.apk"
DUMP = os.path.join(_O, "decompile/dexdump")
OUT = os.path.join(_O, "artifacts/youtu_dumped.apk")

def drop(name):
    if name.startswith("META-INF/") and (name.endswith((".RSA", ".SF", ".MF", ".DSA")) or "PANGLEDY" in name):
        return True
    if name.startswith("assets/baiduprotect"):
        return True
    if name.startswith("lib/") and "libbaiduprotect" in name:
        return True
    if name.startswith("assets/libbaiduprotect"):
        return True
    return False

def fix_dex(d):
    end = struct.unpack_from("<I", d, 32)[0]
    d = bytearray(d[:end])
    d[12:32] = hashlib.sha1(bytes(d[32:])).digest()
    struct.pack_into("<I", d, 8, zlib.adler32(bytes(d[12:])) & 0xffffffff)
    return bytes(d)

# 要补入 real code 的 dump（选中校验自洽的 classes03；以及两对里较小的那些 app 相关）
ADD = [
    "classes03.dex",   # 应用全部业务代码(829类)，校验自洽
    "classes06.dex",   # app 相关库(较小副本)
    "classes11.dex",   # 投屏/tvbus 等 (校验自洽)
    "classes13.dex",   # 小库 (校验自洽)
]

zin = zipfile.ZipFile(SRC)
date = (2026, 10, 15, 0, 0, 0)
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zo:
    for info in zin.infolist():
        n = info.filename
        if n.endswith("/") or drop(n):
            continue
        zo.writestr(zipfile.ZipInfo(n, date), zin.read(n))
    # 原始 classes.dex / classes2.dex 已保留(合法, 含第三方库)
    for i, f in enumerate(ADD):
        raw = open(os.path.join(DUMP, f), "rb").read()
        data = fix_dex(raw)
        arc = "classes%d.dex" % (i + 3)     # classes3.dex ...
        zi = zipfile.ZipInfo(arc, date)
        zi.external_attr = 0o644 << 16
        zo.writestr(zi, data)
        print("add", arc, "<-", f, len(data))

print("OUT", OUT, os.path.getsize(OUT))
with zipfile.ZipFile(OUT) as z:
    print("dex:", [n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")])
