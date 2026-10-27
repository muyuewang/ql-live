import re, sys, io

P = r"C:/Users/fengxin/WorkBuddy/悠兔/out/decompile/proj2/AndroidManifest.xml"
s = open(P, encoding="utf-8").read()

# 1) 去掉加固壳 Application -> 真实 Application
s2 = s.replace('android:name="com.sagittarius.v6.StubApplication"',
               'android:name="com.jiaoxiang.fangnale.MyApplication"')

# 2) 需要移除的权限 / feature
remove_perms = {
    "android.permission.READ_PHONE_STATE",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.GET_ACCOUNTS",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_LOCATION_EXTRA_COMMANDS",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.GET_TASKS",
    "android.permission.CAMERA",
    "android.permission.FLASHLIGHT",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "com.asus.msa.SupplementaryDID.ACCESS",
}

lines = s2.split("\n")
out = []
for ln in lines:
    m = re.search(r'<uses-permission android:name="([^"]+)"\s*/>', ln)
    if m and m.group(1) in remove_perms:
        continue
    if re.search(r'<uses-feature android:name="android\.hardware\.camera', ln):
        continue
    out.append(ln)
s2 = "\n".join(out)

open(P, "w", encoding="utf-8").write(s2)

# 校验
left = re.findall(r'android:name="([^"]*permission[^"]*|[^"]*SupplementaryDID[^"]*)"', s2)
print("剩余 permission 条目:")
for x in left:
    print("  ", x)
print("application name ok:", 'com.jiaoxiang.fangnale.MyApplication' in s2)
print("stub gone:", 'com.sagittarius.v6.StubApplication' not in s2)
