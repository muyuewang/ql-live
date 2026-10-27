import re, os, json

PROJ = r"C:/Users/fengxin/WorkBuddy/悠兔/out/decompile/proj2"
LAY = os.path.join(PROJ, "res/layout/activity_shequ.xml")
s = open(LAY, encoding="utf-8").read()
orig = s

# 需要隐藏的 id（社区Tab + 不属于"5项设置"的列表项）
HIDE = ["shequ_button", "my_openurl", "my_update", "my_about", "my_mzsm"]

for rid in HIDE:
    pat = re.compile(r'(android:id="@id/%s"\s*)' % re.escape(rid))
    def rep(m):
        return m.group(1) + 'android:visibility="gone" '
    s, n = pat.subn(rep, s, count=1)
    print(f"hide {rid}: {'ok' if n else 'NOT FOUND'}")

# 个人中心宫格框高度 280dip -> 缩小(只剩1项)
s = s.replace('android:layout_height="280.0dip" android:layout_marginTop="15.0dip" android:layout_marginStart="15.0dip" android:layout_marginEnd="15.0dip"',
              'android:layout_height="130.0dip" android:layout_marginTop="15.0dip" android:layout_marginStart="15.0dip" android:layout_marginEnd="15.0dip"')
print("grid box resized:", 'android:layout_height="130.0dip"' in s)

open(LAY, "w", encoding="utf-8").write(s)
print("layout changed:", s != orig)

# ---- my_center.json 只保留 更新节目源 ----
JP = os.path.join(PROJ, "assets/my_center.json")
data = json.load(open(JP, encoding="utf-8"))
keep = [it for it in data if it.get("name") == "更新节目源"]
print("my_center.json:", [it["name"] for it in data], "->", [it["name"] for it in keep])
json.dump(keep, open(JP, "w", encoding="utf-8"), ensure_ascii=False, indent=4)
