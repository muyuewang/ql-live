# 替换 libencrypt.so 内嵌证书 hex 为新证书 (等长 1234 字符)
import hashlib

new_der = open("new_cert.der", "rb").read()
assert len(new_der) == 617
new_hex = new_der.hex().encode()  # 1234 chars lowercase

for so in ["orig_ext/lib/arm64-v8a/libencrypt.so", "orig_ext/lib/armeabi-v7a/libencrypt.so"]:
    d = open(so, "rb").read()
    # 找旧证书 hex 串 (以旧 DER hex 开头定位)
    start = d.find(b"30820265308201ce")
    assert start > 0, so
    end = d.find(b"\x00", start)
    old_hex = d[start:end]
    assert len(old_hex) == 1234, (so, len(old_hex))
    out = d[:start] + new_hex + d[end:]
    assert len(out) == len(d)
    dst = so.replace("orig_ext/", "patched_lib/")
    import os
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    open(dst, "wb").write(out)
    print("patched ->", dst, "| old sha1:", hashlib.sha1(bytes.fromhex(old_hex.decode())).hexdigest())
print("done")
