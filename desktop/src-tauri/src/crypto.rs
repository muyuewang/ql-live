use aes::cipher::{BlockDecryptMut, KeyIvInit};
use base64::Engine as _;

type Aes256CbcDec = cbc::Decryptor<aes::Aes256>;

pub const HOST: &str = "http://sjapi.91kds.cn";

const KEY_PREFIX: &str = "4954af3c86d8bc0b766afee71503d860";
const KEY_SUFFIX: &str = "f8dd806a73202456eb6e782c1c4aecfc";

/// key = md5(prefix + tm + suffix)，tm 为秒级时间戳字符串
pub fn sign_key(tm_sec: u64) -> String {
    let raw = format!("{}{}{}", KEY_PREFIX, tm_sec, KEY_SUFFIX);
    let digest = md5::compute(raw.as_bytes());
    format!("{:x}", digest)
}

pub fn md5_hex(data: &[u8]) -> String {
    format!("{:x}", md5::compute(data))
}

/// AES-256-CBC/PKCS7 解密：data = base64(iv(16字节) + ciphertext)
/// key 为 32 字符的 UTF-8 字节（salt）
pub fn aes_cbc_decrypt(base64_data: &str, key_utf8: &str) -> Result<String, String> {
    let raw = base64::engine::general_purpose::STANDARD
        .decode(base64_data)
        .map_err(|e| format!("base64 解码失败: {e}"))?;
    if raw.len() < 16 {
        return Err("数据过短".into());
    }
    let iv: &[u8] = &raw[..16];
    let ciphertext = &raw[16..];
    let key_bytes = key_utf8.as_bytes();
    if key_bytes.len() != 32 {
        return Err(format!("salt 长度必须为 32 字符，实际 {}", key_bytes.len()));
    }
    let mut buf = ciphertext.to_vec();
    let dec = Aes256CbcDec::new(key_bytes.into(), iv.into());
    let plain = dec
        .decrypt_padded_mut::<aes::cipher::block_padding::Pkcs7>(&mut buf)
        .map_err(|e| format!("AES 解密失败: {e}"))?;
    String::from_utf8(plain.to_vec()).map_err(|e| format!("UTF-8 解码失败: {e}"))
}

/// 基准 salt（AES key）。原版由 libencrypt.so 的 getSalt() 提供，
/// 已从 arm64 so 的 getSalt 反汇编提取并实测反解校验通过：
/// salt[i] = rodata[0xa7000+i] - delta[i]，delta 为交错 ±1/±2 序列。
pub fn default_salt() -> String {
    "f8e9d9e9f5b6f8fce7427f4e1a2e9aaf".to_string()
}

/// kdsvod://...pwd=jsdecode 线路的 sign 生成（等价于引擎 other_live.js 的 addSign）。
/// 已逆向自 encrypt_other_live.js（RSA 混淆解密后明文，d=343/n=12382 模幂还原）：
///
///   id      = URL 中 `?id=` 与 `&` 之间
///   app     = 包名（原版 com.jiaoxiang.fangnale；本重构包 com.muyue.ql）
///   version = 版本号
///   mac     = "l1:1l:l1:1l:l1:1l"（固定）
///   nwtime  = 服务器/当前秒级时间戳
///   ev      = "20250805"（固定）
///   srcKey  = id + app + "ahkajfkahlajjaflfakhfakfbuyaozaigaolefuquqikangbuzhu" + version + mac + nwtime + ev
///   sign    = md5(srcKey)
pub fn add_sign(
    id: &str,
    app: &str,
    version: &str,
    mac: &str,
    nwtime: u64,
    ev: &str,
) -> String {
    const KEY_SALT: &str = "ahkajfkahlajjaflfakhfakfbuyaozaigaolefuquqikangbuzhu";
    let src_key = format!("{}{}{}{}{}{}{}", id, app, KEY_SALT, version, mac, nwtime, ev);
    format!("{:x}", md5::compute(src_key.as_bytes()))
}

/// kdsvod 线路 host 替换规则（等价 native_layer.js 的 getHost）。
/// 包名未命中白名单 => 默认 121.199.48.54（hz05）。
pub fn kds_host(app: &str) -> &'static str {
    const HZ05: &[&str] = &["com.zbdq.tvhd", "com.zbdq2025.tvhd", "com.dsdq2025.tvhd",
        "cc.zztv.tvbox", "com.zstv.tvhd", "cc.yxtv.tvhd"];
    const HZ07: &[&str] = &["com.chenfengtv.tvbox", "com.cjitv.tvhd", "cc.yftv.tvhd",
        "com.fftv.tvbox", "com.lktv.tvhd", "cc.yunshangtv.tvhd"];
    if HZ05.contains(&app) {
        "47.96.12.149"
    } else if HZ07.contains(&app) {
        "47.111.31.246"
    } else {
        "121.199.48.54"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sign_key_known() {
        // 日志实测：tm=1792215467 -> key=995f40b9b9adeb83b0c3df8381abe495
        assert_eq!(sign_key(1792215467), "995f40b9b9adeb83b0c3df8381abe495");
    }

    #[test]
    fn test_add_sign_basic() {
        // 仅验证确定性：同一输入产出同一 sign，且为 32 位 hex。
        let s = add_sign("12345", "com.muyue.ql", "2.3.9", "l1:1l:l1:1l:l1:1l", 1730000000, "20250805");
        assert_eq!(s.len(), 32);
        assert!(s.chars().all(|c| c.is_ascii_hexdigit()));
    }

    #[test]
    fn test_kds_host_default() {
        assert_eq!(kds_host("com.muyue.ql"), "121.199.48.54");
        assert_eq!(kds_host("com.zbdq.tvhd"), "47.96.12.149");
    }
}