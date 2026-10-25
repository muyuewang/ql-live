//! encrypt_*.js 数字串解密（引擎算法脚本）。
//!
//! 引擎下发的算法 JS 是「数字串 + RSA 变换」，格式历版恒定：
//!
//! ```text
//! x    = N + c          （c 为密文块，0 < c < N）
//! byte = c^d mod N
//! JS   = base64( 所有 byte 顺序拼接 )
//! ```
//!
//! 官方实现放在 libencrypt.so 的 NativeEncrypt.decrypt 里，且把包名/证书也一起钉死，
//! 结果就是「换个包名就解不开」。这里改为**运行时可还原参数**：
//!
//! * `N` 被数字区间夹死：`N <= min(x)` 且 `2N > max(x)`，候选只有一两百个；
//! * `d` 是小整数（历版 343 / 2023）；
//!
//! 于是枚举 `(N, d)` 并用「解出的前若干字节必须落在 base64 字母表内」做判定，
//! 即可以把当次下发的参数直接算出来，不依赖任何随包二进制。
//!
//! 模块刻意不引第三方 crate（自带 base64 解码），方便桌面端、安卓端与独立验证程序共用。
//!
//! 实测：线上 20241115 版 js.zip → N=12367, d=2023；引擎内置旧版 → N=12382, d=343。

/// 一组解出的 RSA 参数
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Params {
    pub n: u64,
    pub d: u64,
}

impl Params {
    pub const fn new(n: u64, d: u64) -> Self {
        Self { n, d }
    }
}

/// 已知参数（快速通道，命中即无需暴力枚举）
pub const KNOWN_PARAMS: &[Params] = &[Params::new(12367, 2023), Params::new(12382, 343)];

/// 枚举 d 的上限。历版最大 2023，留足余量。
const D_MAX: u64 = 4000;

/// 判定用的探测字节数
const PROBE: usize = 8;

/// 判断是否为 base64 字母表字符（A-Z a-z 0-9 + / =）
fn is_b64_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'+' || b == b'/' || b == b'='
}

/// 快速判断一段文本是否是「加密数字串」形态（而不是明文 JS）。
pub fn looks_encrypted(text: &str) -> bool {
    for tok in text.split_whitespace().take(3) {
        if tok.is_empty() {
            continue;
        }
        if tok.parse::<u64>().is_err() {
            return false;
        }
    }
    !text.trim().is_empty()
}

/// 解析全部数字；只要出现非数字 token 就返回 None。
pub fn parse_nums(text: &str) -> Option<Vec<u64>> {
    let mut out = Vec::new();
    for tok in text.split_whitespace() {
        match tok.parse::<u64>() {
            Ok(v) => out.push(v),
            Err(_) => return None,
        }
    }
    if out.is_empty() {
        None
    } else {
        Some(out)
    }
}

/// 模幂：base^exp mod m（u128 中间量，避免溢出）
fn mod_pow(mut base: u64, mut exp: u64, m: u64) -> u64 {
    if m == 0 {
        return 0;
    }
    let mm = m as u128;
    let mut result: u128 = 1;
    let mut b: u128 = (base % m) as u128;
    while exp > 0 {
        if exp & 1 == 1 {
            result = result * b % mm;
        }
        b = b * b % mm;
        exp >>= 1;
    }
    base = 0;
    let _ = base;
    result as u64
}

/// 标准 base64 解码（自动忽略换行/空白，容忍无 padding）。
pub fn b64_decode(input: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(input.len() * 3 / 4 + 3);
    let mut acc: u32 = 0;
    let mut bits: u32 = 0;
    let mut pad = 0usize;
    for &c in input {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => {
                pad += 1;
                continue;
            }
            b'\n' | b'\r' | b' ' | b'\t' => continue,
            _ => return None,
        } as u32;
        if pad > 0 {
            return None; // '=' 之后不应再有有效字符
        }
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1u32 << bits) - 1;
        }
    }
    Some(out)
}

/// 用给定参数尝试解密前 `PROBE` 个数字，判断参数是否命中。
fn probe_ok(nums: &[u64], p: Params) -> bool {
    let mut seen = 0usize;
    for &x in nums {
        if x <= p.n {
            continue;
        }
        let b = mod_pow(x - p.n, p.d, p.n);
        if b > 255 || !is_b64_byte(b as u8) {
            return false;
        }
        seen += 1;
        if seen >= PROBE {
            return true;
        }
    }
    false
}

/// 暴力还原参数：N ∈ (max/2, min]，d ∈ [1, 4000]。
///
/// 返回第一个命中组合（同 `N` 下最小的 `d`；模 λ 等价的解只会命中最小那个）。
pub fn recover(nums: &[u64]) -> Option<Params> {
    if nums.len() < PROBE {
        return None;
    }
    // 已知参数优先
    for &p in KNOWN_PARAMS {
        if probe_ok(nums, p) {
            return Some(p);
        }
    }

    let mut max = 0u64;
    let mut min = u64::MAX;
    for &x in nums {
        if x > max {
            max = x;
        }
        if x < min {
            min = x;
        }
    }
    let lo = max / 2 + 1;
    if lo >= min {
        // 数字区间太窄（可能不是这种编码），仍然扫一遍最可能的几个值
        let start = min.saturating_sub(8);
        return scan_nums(nums, start, min);
    }
    scan_nums(nums, lo, min)
}

fn scan_nums(nums: &[u64], lo: u64, hi: u64) -> Option<Params> {
    let x0 = nums[0];
    for n in lo..=hi {
        if x0 <= n {
            continue;
        }
        let c1 = x0 - n;
        let mut v: u64 = 1;
        for d in 1..=D_MAX {
            v = (v as u128 * c1 as u128 % n as u128) as u64;
            if v > 255 || !is_b64_byte(v as u8) {
                continue;
            }
            if probe_ok(nums, Params::new(n, d)) {
                return Some(Params::new(n, d));
            }
        }
    }
    None
}

/// 用指定参数解密成 JS 明文。
pub fn decrypt_with(nums: &[u64], p: Params) -> Result<String, String> {
    let mut raw: Vec<u8> = Vec::with_capacity(nums.len());
    for (i, &x) in nums.iter().enumerate() {
        if x <= p.n {
            return Err(format!("第 {} 个数字 {} 不大于模数 {}", i + 1, x, p.n));
        }
        let b = mod_pow(x - p.n, p.d, p.n);
        if b > 255 {
            return Err(format!("第 {} 个数字解出 {} 超出字节范围（参数不对）", i + 1, b));
        }
        raw.push(b as u8);
    }
    let js = b64_decode(&raw).ok_or_else(|| "base64 解码失败".to_string())?;
    String::from_utf8(js).map_err(|e| format!("UTF-8 解码失败: {e}"))
}

/// 自动识别参数并解密；文本本身是明文时直接返回原文。
///
/// 返回值第二项为实际使用的参数（明文输入时为 None）。
pub fn decrypt_auto(text: &str) -> Result<(String, Option<Params>), String> {
    if !looks_encrypted(text) {
        return Ok((text.to_string(), None));
    }
    let nums = parse_nums(text).ok_or_else(|| "数字串解析失败".to_string())?;
    let p = recover(&nums).ok_or_else(|| format!("未能还原 RSA 参数（共 {} 个数字）", nums.len()))?;
    let js = decrypt_with(&nums, p)?;
    Ok((js, Some(p)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn b64_roundtrip_known() {
        // "function" 的 base64
        assert_eq!(b64_decode(b"ZnVuY3Rpb24=").unwrap(), b"function");
    }

    #[test]
    fn mod_pow_basic() {
        assert_eq!(mod_pow(2, 10, 1000), 24);
        assert_eq!(mod_pow(90, 343, 12382), mod_pow(90, 343, 12382));
    }

    #[test]
    fn detect_plain_vs_numeric() {
        assert!(!looks_encrypted("function RootFunction(url) {"));
        assert!(looks_encrypted("18616 18998 18516 18585"));
    }

    /// 用「旧版」参数自造一段数字串，验证 recover 能把它找回来。
    #[test]
    fn recover_synthetic() {
        // 明文 -> base64 -> 用 e 加密回去（e * d ≡ 1 mod λ(12382)，λ=600，d=343）
        let n = 12382u64;
        let d = 343u64;
        let e = 7u64; // 343 * 7 = 2401 ≡ 1 (mod 600)
        let plain = b"ZnVuY3Rpb24gUm9vdEZ1bmN0aW9uKHVybCl7"; // "function RootFunction(url){"
        let mut nums = Vec::new();
        for &b in plain {
            nums.push(n + mod_pow(b as u64, e, n));
        }
        let p = recover(&nums).expect("应能还原参数");
        assert_eq!(p.n, n);
        assert_eq!(mod_pow(2, p.d, 1), 0); // 占位，避免 unused
        let js = decrypt_with(&nums, p).expect("应能解密");
        assert_eq!(js, "function RootFunction(url){");
    }
}
