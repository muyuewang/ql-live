import zipfile, os, re, subprocess, shutil, sys

W = r"C:/Users/fengxin/WorkBuddy/悠兔/out/artifacts"
APKTOOL = r"C:/Users/fengxin/Tools/apktool/apktool.jar"

# 1) 构造只含"应用 dex"的 mini apk (复用原包的资源/清单)
src = zipfile.ZipFile(os.path.join(W, "youtu_dumped.apk"))
mini = os.path.join(W, "appdex.apk")
appdex = src.read("classes3.dex")     # 我们的应用 dex
date = (2026, 10, 15, 0, 0, 0)
with zipfile.ZipFile(mini, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr(zipfile.ZipInfo("classes.dex", date), appdex)
    for n in ("AndroidManifest.xml", "resources.arsc"):
        z.writestr(zipfile.ZipInfo(n, date), src.read(n))
    for n in src.namelist():
        if n.startswith("res/") and not n.endswith("/"):
            z.writestr(zipfile.ZipInfo(n, date), src.read(n))
print("mini apk built:", os.path.getsize(mini))

# 2) 解包 mini
proj = os.path.join(W, "appex_proj")
shutil.rmtree(proj, ignore_errors=True)
subprocess.run(["java", "-Xmx3g", "-jar", APKTOOL, "d", "-f", mini, "-o", proj],
               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

# 3) 定位并修补 showAdDialog
tgt = None
for root, _, fs in os.walk(os.path.join(proj, "smali")):
    for f in fs:
        if f == "SheQuActivity.smali":
            tgt = os.path.join(root, f)
print("target:", tgt)
s = open(tgt, encoding="utf-8").read()
lines = s.split("\n")
start = next(i for i, l in enumerate(lines) if l.strip().startswith(".method private showAdDialog"))
end = next(i for i in range(start, len(lines)) if lines[i].strip() == ".end method")
print("method lines:", start + 1, "..", end + 1)
new_body = [
    lines[start],
    "    .locals 0",
    "",
    "    return-void",
    ".end method",
]
lines = lines[:start] + new_body + lines[end + 1:]
open(tgt, "w", encoding="utf-8").write("\n".join(lines))
print("patched showAdDialog -> return-void")

# 4) 重建 mini
out = os.path.join(W, "appdex_new.apk")
if os.path.exists(out):
    os.remove(out)
subprocess.run(["java", "-Xmx3g", "-jar", APKTOOL, "b", proj, "-o", out],
               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
print("rebuilt:", os.path.getsize(out))

# 5) 取出新 dex, 覆盖 proj2 里的 classes3.dex
newz = zipfile.ZipFile(out)
newdex = newz.read("classes.dex")
dst = os.path.join(W, "proj2", "classes3.dex")
open(dst, "wb").write(newdex)
print("wrote proj2/classes3.dex", len(newdex))
