# -*- coding: utf-8 -*-
"""Dependency-closure pruning of smali trees under dump_s/."""
import os, re

base = 'dump_s'
smali_dirs = [d for d in os.listdir(base) if d.startswith('smali')]
files = {}  # Lcom/x/Y; -> file path
for d in smali_dirs:
    for root, _, fnames in os.walk(os.path.join(base, d)):
        for fn in fnames:
            if fn.endswith('.smali'):
                rel = os.path.relpath(os.path.join(root, fn), os.path.join(base, d))
                rel = rel.replace('\\\\', '/').replace('\\', '/')
                cls = 'L' + os.path.splitext(rel)[0] + ';'
                files[cls] = os.path.join(root, fn)

print('total smali classes:', len(files))

ref_re = re.compile(r'L[\w/$\-]+;')

roots = set()
with open(os.path.join(base, 'AndroidManifest.xml'), encoding='utf-8') as f:
    mf = f.read()
for m in re.finditer(r'(?:android:name|android:targetActivity)="([\w.]+)"', mf):
    n = m.group(1)
    if not n.startswith(('com.android', 'android', 'androidx')):
        roots.add('L' + n.replace('.', '/') + ';')
roots.add('Lcom/jiaoxiang/fangnale/MyApplication;')
for cls in files:
    if cls.startswith('Lcom/jiaoxiang/fangnale/myui/'):
        roots.add(cls)
for c in ['Lcom/jiaoxiang/fangnale/utils/NativeEncrypt;', 'Lcom/jiaoxiang/fangnale/utils/AesUtil;',
          'Lcom/jiaoxiang/fangnale/utils/ApiUtils;', 'Lcom/jiaoxiang/fangnale/utils/OkHttpClientManager;',
          'Lcom/jiaoxiang/fangnale/net/NetConstant;', 'Lcom/jiaoxiang/fangnale/bean/SourceListBean;',
          'Lcom/jiaoxiang/fangnale/utils/SourceName;', 'Lcom/jiaoxiang/fangnale/activity/IjkGovPlayerActivity;']:
    if c in files:
        roots.add(c)

refs_cache = {}
def refs_of(path):
    if path in refs_cache:
        return refs_cache[path]
    with open(path, encoding='utf-8', errors='ignore') as f:
        data = f.read()
    out = set(ref_re.findall(data))
    refs_cache[path] = out
    return out

keep = set()
queue = list(roots)
while queue:
    c = queue.pop()
    if c in keep or c not in files:
        continue
    keep.add(c)
    for r in refs_of(files[c]):
        if r not in keep and r in files:
            queue.append(r)

print('closure keep:', len(keep), '  drop:', len(files) - len(keep))

removed = 0
for cls, path in files.items():
    if cls not in keep:
        os.remove(path)
        removed += 1
print('removed files:', removed)
