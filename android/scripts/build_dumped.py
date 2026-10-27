import zipfile, os, glob, hashlib, struct

SRC = r"C:/Users/fengxin/Downloads/悠兔电视.apk"
DUMP = r"C:/Users/fengxin/WorkBuddy/悠兔/work/dexdump"
OUT = r"C:/Users/fengxin/WorkBuddy/悠兔/work/youtu_dumped.apk"

# 原包里属于加固 / 旧 dex / 签名，全部剔除
def is_drop(name):
    if name in ("classes.dex", "classes2.dex"):
        return True
    if name.startswith("META-INF/") and (name.endswith(".RSA") or name.endswith(".SF")
        or name.endswith(".MF") or name.endswith(".DSA") or "PANGLEDY" in name):
        return True
    if name.startswith("assets/baiduprotect"):
        return True
    if name.startswith("lib/") and "libbaiduprotect" in name:
        return True
    if name.startswith("assets/libbaiduprotect"):
        return True
    return False

# 选要放进去的 dump dex：按内容去重，app 代码优先排前面
def app_score(d):
    return d.count(b"Lcom/jiaoxiang/fangnale/")

dexes = []
seen_md5 = set()
for p in glob.glob(os.path.join(DUMP, "*.dex")):
    data = open(p, "rb").read()
    if len(data) < 100000:            # 丢弃 <100KB 的碎片
        continue
    h = hashlib.md5(data).hexdigest()
    if h in seen_md5:
        continue
    seen_md5.add(h)
    dexes.append((app_score(data), len(data), os.path.basename(p), data))
dexes.sort(key=lambda x: (-x[0], -x[1]))   # 含 app 类的排前面

zin = zipfile.ZipFile(SRC)
# 用原包第一条的日期，保持一致
date = (2026, 10, 15, 0, 0, 0)
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
    for info in zin.infolist():
        n = info.filename
        if n.endswith("/"):
            continue
        if is_drop(n):
            continue
        zout.writestr(zipfile.ZipInfo(n, date), zin.read(n))
    # 写 dex
    for i, (sc, sz, name, data) in enumerate(dexes):
        arc = "classes.dex" if i == 0 else "classes%d.dex" % (i + 1)
        zi = zipfile.ZipInfo(arc, date)
        zi.external_attr = 0o644 << 16
        zout.writestr(zi, data)

print("OUT =", OUT, os.path.getsize(OUT))
print("dumped dex included:", [d[2] for d in dexes])
with zipfile.ZipFile(OUT) as z:
    print("total entries:", len(z.namelist()))
    print("dex files:", [n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")])
    print("baidu left:", [n for n in z.namelist() if "baidu" in n.lower()])
    print("has manifest:", "AndroidManifest.xml" in z.namelist())
    print("has arsc:", "resources.arsc" in z.namelist())
