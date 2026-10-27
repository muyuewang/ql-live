import zipfile, os, glob, hashlib, zlib, struct

# out/ 按月归档：优先用环境变量 OUT_MONTH，否则取 out/ 下最新的 YYYY-MM 目录
_WS = r"C:/Users/fengxin/WorkBuddy/悠兔"
_m = sorted(os.path.basename(p) for p in glob.glob(os.path.join(_WS, "out", "20??-??")))
_O = os.path.join(_WS, "out", os.environ.get("OUT_MONTH") or _m[-1])

SRC = r"C:/Users/fengxin/Downloads/悠兔电视.apk"
DUMP = os.path.join(_O, "decompile/dexdump")
OUT = os.path.join(_O, "artifacts/youtu_dumped.apk")

def drop(name):
    if name in ("classes.dex", "classes2.dex"):
        return True
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
    """重算 dex 头的 checksum(adler32) 与 SHA1 signature，使 ART 校验通过"""
    if d[:4] != b"dex\n":
        return None
    end = struct.unpack_from("<I", d, 32)[0]
    if end > len(d) or end < 112:
        return None
    d = bytearray(d[:end])
    sig = hashlib.sha1(bytes(d[32:])).digest()
    d[12:32] = sig
    cs = zlib.adler32(bytes(d[12:])) & 0xffffffff
    struct.pack_into("<I", d, 8, cs)
    return bytes(d)

def app_score(d):
    return d.count(b"Lcom/jiaoxiang/fangnale/")

dexes, seen = [], set()
for p in glob.glob(os.path.join(DUMP, "*.dex")):
    raw = open(p, "rb").read()
    fixed = fix_dex(raw)
    if fixed is None or len(fixed) < 100000:      # 丢弃碎片/无效
        print("skip", os.path.basename(p), "raw", len(raw))
        continue
    h = hashlib.md5(fixed).hexdigest()
    if h in seen:
        print("dup ", os.path.basename(p))
        continue
    seen.add(h)
    dexes.append((app_score(fixed), len(fixed), os.path.basename(p), fixed))

dexes.sort(key=lambda x: (-x[0], -x[1]))

zin = zipfile.ZipFile(SRC)
date = (2026, 10, 15, 0, 0, 0)
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zo:
    for info in zin.infolist():
        n = info.filename
        if n.endswith("/") or drop(n):
            continue
        zo.writestr(zipfile.ZipInfo(n, date), zin.read(n))
    for i, (sc, sz, name, data) in enumerate(dexes):
        arc = "classes.dex" if i == 0 else "classes%d.dex" % (i + 1)
        zi = zipfile.ZipInfo(arc, date)
        zi.external_attr = 0o644 << 16
        zo.writestr(zi, data)

print("\nOUT", OUT, os.path.getsize(OUT))
for i, (sc, sz, name, data) in enumerate(dexes):
    arc = "classes.dex" if i == 0 else "classes%d.dex" % (i + 1)
    print(f"  {arc:14s} <- {name:16s} app={sc:5d} size={sz}")
