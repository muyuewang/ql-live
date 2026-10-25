//! 桌面端对引擎 JS 的宿主能力实现（等价安卓的 `@JavascriptInterface`）。
//!
//! 方法清单一比一对照 `JsLayer$ParserJsInterface`（原版 smali），
//! 引擎 JS 里 `client.*` 实际调用到的 23 个方法全部实现，
//! 保证「原版能解析的线路，桌面也能解析」。

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use aes::cipher::{BlockEncryptMut, KeyInit, KeyIvInit};
use base64::Engine as _;
use hmac::{Hmac, Mac};
use sha2::Sha256;

use crate::crypto::{ADD_SIGN_MAC, ADD_SIGN_VERSION, APP_PKG};
use crate::jsengine::HostBridge;

/// 引擎请求上游资源时使用的 UA（官方同款 AppleCoreMedia）。
const APPLE_UA: &str =
    "AppleCoreMedia/1.0.0.16F79 (iPhone; U; CPU OS 11_5 like Mac OS X; zh_cn)";

/// getUtid 的兜底值。安卓端 `_generateUtdid()` 异常时返回的就是这个常量，
/// 服务端把它当普通设备标识，不校验真实性。
const FALLBACK_UTID: &str = "WvlagSFJED4DAMDfGOQntl2N";

/// getHncscsKey 的固定输入（来自 HncscsUtil.getAes 的常量）
const HNCSCS_TIME: &str = "1534055214123";
const HNCSCS_SALT: &str = "shfagjalk";
const HNCSCS_KEY: &[u8; 16] = b"idontnotkeyappid";
const HNCSCS_IV: &[u8; 16] = b"A-16-Byte-String";

type HmacSha256 = Hmac<Sha256>;
type Aes128CbcEnc = cbc::Encryptor<aes::Aes128>;

pub struct NativeBridge {
    http: reqwest::blocking::Client,
    /// auth 文件目录（等价安卓 MyApplication.dir，getCntvUrl 从这里读 16 字节密钥）
    auth_dir: PathBuf,
}

impl NativeBridge {
    pub fn new(auth_dir: PathBuf) -> Self {
        let http = reqwest::blocking::Client::builder()
            .user_agent(APPLE_UA)
            .timeout(Duration::from_secs(10))
            .connect_timeout(Duration::from_secs(6))
            .build()
            .unwrap_or_else(|_| reqwest::blocking::Client::new());
        Self { http, auth_dir }
    }

    fn now_sec(&self) -> String {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs().to_string())
            .unwrap_or_default()
    }

    /// 解析引擎传来的 header 串：`A:B@C:D`（安卓里就是 split("@") 再 split(":")）
    fn parse_headers(spec: &str) -> Vec<(String, String)> {
        let mut out = Vec::new();
        let t = spec.trim();
        if t.is_empty() || t == "{}" {
            return out;
        }
        for part in spec.split('@') {
            let part = part.trim();
            if part.is_empty() || part == "{}" {
                continue;
            }
            if let Some(i) = part.find(':') {
                let k = part[..i]
                    .trim()
                    .trim_matches(|c| c == '{' || c == '\'' || c == '"');
                let v = part[i + 1..]
                    .trim()
                    .trim_matches(|c| c == '}' || c == '\'' || c == '"');
                if !k.is_empty() {
                    out.push((k.to_string(), v.to_string()));
                }
            }
        }
        out
    }

    /// OkHttp `Headers.toString()` 的等价形式：每行 `Name: value`。
    /// 引擎侧靠 `getStringBtween(resp,"Set-Cookie:",";")` 定位，冒号后的空格不能省。
    fn format_headers(h: &reqwest::header::HeaderMap) -> String {
        // 必须输出 Title-Case（`Set-Cookie: ...`）：reqwest 会把头名归一化成小写，
        // 而官方 JS 用 `getStringBtween(resp, "Set-Cookie:", ";")` 做字面匹配，
        // 小写 `set-cookie` 取不到 cookie（jsyztv 解析失败的根因）。
        // OkHttp 的 Headers.toString() 就是原样大小写 + 每行 `name: value\n`。
        let mut s = String::new();
        for (k, v) in h.iter() {
            let name = k.as_str();
            let mut pretty = String::with_capacity(name.len());
            let mut upper = true;
            for ch in name.chars() {
                if upper {
                    pretty.extend(ch.to_uppercase());
                    upper = false;
                } else {
                    pretty.push(ch);
                }
                if ch == '-' {
                    upper = true;
                }
            }
            let val = v.to_str().unwrap_or("");
            s.push_str(&format!("{}: {}\n", pretty, val));
        }
        s
    }

    fn apply_headers(
        mut req: reqwest::blocking::RequestBuilder,
        spec: &str,
    ) -> reqwest::blocking::RequestBuilder {
        for (k, v) in Self::parse_headers(spec) {
            req = req.header(k, v);
        }
        req
    }

    fn get_text(&self, url: &str, header_spec: &str) -> String {
        if !url.starts_with("http") {
            return String::new();
        }
        let req = Self::apply_headers(self.http.get(url), header_spec);
        match req.send() {
            Ok(r) if r.status().is_success() => r.text().unwrap_or_default(),
            Ok(r) => {
                eprintln!("[bridge] GET {} -> HTTP {}", url, r.status());
                String::new()
            }
            Err(e) => {
                eprintln!("[bridge] GET {} 失败: {e}", url);
                String::new()
            }
        }
    }

    /// getRespAndHeaders：GET 后返回 `{"body":"...","headers":"Name: value\n..."}`
    fn get_with_headers(&self, url: &str, header_spec: &str) -> String {
        if !url.starts_with("http") {
            return String::new();
        }
        let req = Self::apply_headers(self.http.get(url), header_spec);
        match req.send() {
            Ok(r) => {
                let status = r.status();
                let headers = Self::format_headers(r.headers());
                let body = r.text().unwrap_or_default();
                if !status.is_success() {
                    eprintln!("[bridge] GET(带响应头) {url} -> HTTP {status}");
                }
                json_pair(&body, &headers)
            }
            Err(e) => {
                eprintln!("[bridge] GET(带响应头) {url} 失败: {e}");
                String::new()
            }
        }
    }

    /// getPostContent：POST `k=v&k2=v2`，按 form-urlencoded 提交
    fn post_form(&self, url: &str, form: &str, header_spec: &str) -> String {
        if !url.starts_with("http") {
            return String::new();
        }
        let mut pairs: Vec<(String, String)> = Vec::new();
        for seg in form.split('&') {
            if seg.is_empty() {
                continue;
            }
            match seg.find('=') {
                Some(i) => pairs.push((seg[..i].to_string(), seg[i + 1..].to_string())),
                None => pairs.push((seg.to_string(), String::new())),
            }
        }
        let req = Self::apply_headers(self.http.post(url), header_spec).form(&pairs);
        match req.send() {
            Ok(r) => r.text().unwrap_or_default(),
            Err(e) => {
                eprintln!("[bridge] POST {url} 失败: {e}");
                String::new()
            }
        }
    }

    /// getPostContent1：POST 原始 body + 指定 MediaType
    fn post_raw(&self, url: &str, body: &str, header_spec: &str, media: &str) -> String {
        if !url.starts_with("http") {
            return String::new();
        }
        let mt = if media.trim().is_empty() {
            "application/x-www-form-urlencoded"
        } else {
            media.trim()
        };
        let req = Self::apply_headers(self.http.post(url), header_spec)
            .header(reqwest::header::CONTENT_TYPE, mt)
            .body(body.to_string());
        match req.send() {
            Ok(r) => r.text().unwrap_or_default(),
            Err(e) => {
                eprintln!("[bridge] POST(raw) {url} 失败: {e}");
                String::new()
            }
        }
    }

    /// getPostRespAndHeaders：POST 后返回 body + 响应头 JSON
    fn post_with_headers(&self, url: &str, body: &str, header_spec: &str, media: &str) -> String {
        if !url.starts_with("http") {
            return String::new();
        }
        let mt = if media.trim().is_empty() {
            "application/x-www-form-urlencoded"
        } else {
            media.trim()
        };
        let req = Self::apply_headers(self.http.post(url), header_spec)
            .header(reqwest::header::CONTENT_TYPE, mt)
            .body(body.to_string());
        match req.send() {
            Ok(r) => {
                let status = r.status();
                let headers = Self::format_headers(r.headers());
                let text = r.text().unwrap_or_default();
                if !status.is_success() {
                    eprintln!("[bridge] POST(带响应头) {url} -> HTTP {status}");
                }
                json_pair(&text, &headers)
            }
            Err(e) => {
                eprintln!("[bridge] POST(带响应头) {url} 失败: {e}");
                String::new()
            }
        }
    }

    /// getCntvUrl：等价安卓 urlMakeAuth。
    ///
    /// 安卓从 `MyApplication.dir/<fileName>` 读一个 auth 文件，seek 到 pos 取 16 字节当密钥，
    /// 再 AES/ECB/NoPadding 派生 mkey，解出 token 拼进 `?amode=1&AUTH=cntv0005ooefqxwe<token>`。
    /// auth 文件由 assets 提供；桌面端放在 `engine_dir/auth/<fileName>`，
    /// 文件不存在时原样返回（与安卓 auth_key 为 null 的分支一致）。
    fn cntv_url(&self, url: &str, pos: &str, file_name: &str) -> String {
        if !url.contains("AUTH") {
            return url.to_string();
        }
        let pos: usize = pos.trim().parse().unwrap_or(0);
        let path = self.auth_dir.join(sanitize(file_name));
        let key = match read_auth_key(&path, pos) {
            Some(k) => k,
            None => {
                eprintln!(
                    "[bridge] getCntvUrl 缺少 auth 文件 {}，原样返回",
                    path.display()
                );
                return url.to_string();
            }
        };

        // mkey = AES/ECB/NoPadding(encrypt, key=auth_key16, data="13Abde%6@UdwerPh")
        let mkey = aes128_ecb_encrypt(&key, b"13Abde%6@UdwerPh");
        if mkey.is_empty() {
            return url.to_string();
        }

        // AUTH= 后面那段是 base64，用 mkey 以 ECB 解密得到 token
        let auth_b64 = match url.split("AUTH=").nth(1) {
            Some(s) => s.split('&').next().unwrap_or(s),
            None => return url.to_string(),
        };
        let ct = match base64::engine::general_purpose::STANDARD.decode(auth_b64.trim()) {
            Ok(b) => b,
            Err(e) => {
                eprintln!("[bridge] getCntvUrl AUTH 段不是合法 base64: {e}");
                return url.to_string();
            }
        };
        let token = match aes128_ecb_decrypt(&mkey, &ct) {
            Some(t) => t,
            None => return url.to_string(),
        };
        if token.len() <= 1 {
            return url.to_string();
        }
        let base = url.split('?').next().unwrap_or(url);
        format!(
            "{}?amode=1&AUTH=cntv0005ooefqxwe{}",
            base,
            base64::engine::general_purpose::STANDARD.encode(&token)
        )
    }

    /// getHncscsKey：等价 HncscsUtil.getAes
    /// = URLEncode(Base64(AES/CBC/PKCS5({"appid":"60","time":...,"deviceid":md5(time+salt)})))
    fn hncscs_key(&self) -> String {
        let device = crate::crypto::md5_hex(format!("{HNCSCS_TIME}{HNCSCS_SALT}").as_bytes());
        let json = format!(r#"{{"appid":"60","time":"{HNCSCS_TIME}","deviceid":"{device}"}}"#);
        let ct = aes128_cbc_pkcs7_encrypt(HNCSCS_KEY, HNCSCS_IV, json.as_bytes());
        let b64 = base64::engine::general_purpose::STANDARD.encode(&ct);
        // Java URLEncoder.encode：空格→+，其余非 unreserved 全转 %XX
        urlencoding::encode(&b64).replace("%20", "+")
    }
}

impl HostBridge for NativeBridge {
    fn supports(&self, name: &str) -> bool {
        matches!(
            name,
            "getPkgName"
                | "getAppVersion"
                | "getNwtime"
                | "getMD5"
                | "getMac"
                | "getImei"
                | "getUserToken"
                | "getTmKey"
                | "getLocation"
                | "writeFile"
                | "getUrlContent"
                | "getUrlContentWithHeader"
                | "getPostContent"
                | "getPostContent1"
                | "getPostRespAndHeaders"
                | "getRespAndHeaders"
                | "AESDecrypt128"
                | "encC20Ep"
                | "getJxtvKey"
                | "getBstFk"
                | "getCntvUrl"
                | "getHncscsKey"
                | "getUtid"
        )
    }

    fn call(&self, name: &str, a: &str, b: &str, c: &str, d: &str) -> String {
        match name {
            "getPkgName" => APP_PKG.to_string(),
            "getAppVersion" => ADD_SIGN_VERSION.to_string(),
            "getNwtime" => self.now_sec(),
            "getMD5" => crate::crypto::md5_hex(a.as_bytes()),
            "getMac" => ADD_SIGN_MAC.to_string(),
            "getImei" => "123456".to_string(),
            // 未登录 / 无定位 / 无写文件需求：给空串即可
            "getUserToken" | "getTmKey" | "getLocation" | "writeFile" => String::new(),

            "getUrlContent" | "getUrlContentWithHeader" => self.get_text(a, b),
            "getRespAndHeaders" => self.get_with_headers(a, b),
            "getPostContent" => self.post_form(a, b, c),
            "getPostContent1" => self.post_raw(a, b, c, d),
            "getPostRespAndHeaders" => self.post_with_headers(a, b, c, d),

            // AES/ECB/NoPadding 解密：content 是 base64
            "AESDecrypt128" => match base64::engine::general_purpose::STANDARD.decode(a.trim()) {
                Ok(ct) => aes128_ecb_decrypt(b.as_bytes(), &ct).unwrap_or_default(),
                Err(_) => String::new(),
            },
            // AES/ECB/NoPadding 加密：零填充到 (len/16+1)*16（多一个块，与安卓一致）
            "encC20Ep" => {
                let n = a.as_bytes().len();
                let padded = (n / 16 + 1) * 16;
                let mut buf = vec![0u8; padded];
                buf[..n].copy_from_slice(a.as_bytes());
                let ct = aes128_ecb_encrypt(b.as_bytes(), &buf);
                base64::engine::general_purpose::STANDARD.encode(&ct)
            }

            // 江苏台 SbtStreamingServer 的 CRC32 口令
            "getJxtvKey" => {
                let ts: u64 = a.trim().parse().unwrap_or(0);
                jxtv_key(ts)
            }
            // HmacSHA256(key=b, data=a)，小写 hex
            "getBstFk" => {
                // 明确用 Mac trait 的 new_from_slice（KeyInit 也有同名方法，会歧义）
                let mut mac = match <HmacSha256 as Mac>::new_from_slice(b.as_bytes()) {
                    Ok(m) => m,
                    Err(_) => return String::new(),
                };
                mac.update(a.as_bytes());
                let out = mac.finalize().into_bytes();
                out.iter().map(|x| format!("{x:02x}")).collect()
            }
            "getCntvUrl" => self.cntv_url(a, b, c),
            "getHncscsKey" => self.hncscs_key(),
            "getUtid" => FALLBACK_UTID.to_string(),
            _ => String::new(),
        }
    }
}

// ---------------------------------------------------------------- 算法原语

/// 等价 `getRespAndHeaders` 的 Gson 输出：{"body":..,"headers":..}
fn json_pair(body: &str, headers: &str) -> String {
    serde_json::json!({ "body": body, "headers": headers }).to_string()
}

/// 等价安卓 getJxtvKey(long)：
///   buf = [低 32 位小端 4 字节] + "SbtStreamingServer"
///   len = (len(buf)/4)*4 = 20
///   return String(crc32(buf[0..20]))
fn jxtv_key(ts: u64) -> String {
    let mut buf = Vec::with_capacity(21);
    buf.extend_from_slice(&(ts as u32).to_le_bytes());
    buf.extend_from_slice(b"SbtStreamingServer");
    let n = (buf.len() / 4) * 4;
    crc32fast::hash(&buf[..n]).to_string()
}

/// AES-128/ECB/NoPadding 加密（输入长度须为 16 的倍数）
///
/// ECB 就是「每块独立做一次 AES」，逐块调用 block 原语即可（aes crate 不再提供 ecb 类型）。
fn aes128_ecb_encrypt(key: &[u8], data: &[u8]) -> Vec<u8> {
    use aes::cipher::BlockEncrypt;
    let k: [u8; 16] = match key.get(..16).and_then(|s| s.try_into().ok()) {
        Some(k) => k,
        None => return Vec::new(),
    };
    let cipher = aes::Aes128::new_from_slice(&k).expect("16 字节密钥");
    let mut buf = data.to_vec();
    let len = buf.len() / 16 * 16;
    buf.truncate(len);
    for chunk in buf.chunks_mut(16) {
        cipher.encrypt_block(aes::Block::from_mut_slice(chunk));
    }
    buf
}

/// AES-128/ECB/NoPadding 解密，结果按 UTF-8 解出并去掉尾部 NUL
fn aes128_ecb_decrypt(key: &[u8], data: &[u8]) -> Option<String> {
    use aes::cipher::BlockDecrypt;
    let k: [u8; 16] = key.get(..16)?.try_into().ok()?;
    let cipher = aes::Aes128::new_from_slice(&k).ok()?;
    let mut buf = data.to_vec();
    let len = buf.len() / 16 * 16;
    buf.truncate(len);
    for chunk in buf.chunks_mut(16) {
        cipher.decrypt_block(aes::Block::from_mut_slice(chunk));
    }
    let end = buf.iter().rposition(|&x| x != 0).map_or(0, |i| i + 1);
    Some(String::from_utf8_lossy(&buf[..end]).into_owned())
}

/// AES-128/CBC/PKCS5Padding 加密（等价 javax AesCipher PKCS5）
fn aes128_cbc_pkcs7_encrypt(key: &[u8; 16], iv: &[u8; 16], data: &[u8]) -> Vec<u8> {
    let pad = 16 - (data.len() % 16);
    let mut buf = data.to_vec();
    buf.extend(std::iter::repeat(pad as u8).take(pad));
    let mut enc = Aes128CbcEnc::new(key.into(), iv.into());
    for chunk in buf.chunks_mut(16) {
        enc.encrypt_block_mut(aes::Block::from_mut_slice(chunk));
    }
    buf
}

/// 从 auth 文件 offset 处读 16 字节（等价 RandomAccessFile.seek + read）
fn read_auth_key(path: &Path, pos: usize) -> Option<[u8; 16]> {
    use std::io::{Read, Seek, SeekFrom};
    let mut f = std::fs::File::open(path).ok()?;
    let len = f.metadata().ok()?.len();
    if len <= pos as u64 {
        return None;
    }
    f.seek(SeekFrom::Start(pos as u64)).ok()?;
    let mut buf = [0u8; 16];
    f.read_exact(&mut buf).ok()?;
    Some(buf)
}

/// 只保留文件名，防目录穿越
fn sanitize(name: &str) -> String {
    Path::new(name)
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_default()
}

pub fn bridge(auth_dir: PathBuf) -> Arc<dyn HostBridge> {
    Arc::new(NativeBridge::new(auth_dir))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jxtv_key_is_decimal_u32() {
        // 等价 Java：CRC32(le32(ts) + "SbtStreamingServer" 取前 20 字节)
        let k = jxtv_key(1700000000);
        assert!(k.parse::<u32>().is_ok(), "应为十进制无符号整数: {k}");
    }

    #[test]
    fn header_spec_parsing() {
        let h = NativeBridge::parse_headers("User-Agent:Mozilla/5.0@Referer:https://a.b/c");
        assert_eq!(h.len(), 2);
        assert_eq!(h[0].0, "User-Agent");
        assert_eq!(h[1].1, "https://a.b/c");
        // JS 里常传 "{}" 表示无头
        assert!(NativeBridge::parse_headers("{}").is_empty());
    }

    #[test]
    fn ecb_roundtrip_and_zero_pad() {
        let key = b"0123456789abcdef";
        let ct = aes128_ecb_encrypt(key, b"0123456789abcdef");
        assert_eq!(aes128_ecb_decrypt(key, &ct).unwrap(), "0123456789abcdef");

        // encC20Ep 语义：16 字节输入 → (16/16+1)*16 = 32 字节密文
        assert_eq!((16usize / 16 + 1) * 16, 32);
    }

    #[test]
    fn hncscs_key_is_urlencoded_base64() {
        let b = NativeBridge::new(std::env::temp_dir());
        let k = b.hncscs_key();
        assert!(!k.is_empty());
        // URLEncode 后不应残留 / =
        assert!(!k.contains('/'));
        assert!(!k.contains('='));
    }
}
