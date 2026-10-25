//! 桌面端对引擎 JS 的宿主能力实现（等价安卓的 `@JavascriptInterface`）。
//!
//! 只声明确实能提供的少数几个：kdsvod 那条路只需 4 个
//! （getPkgName / getAppVersion / getNwtime / getMD5）。其余保持「不声明」，
//! 让脚本走它自带的降级分支，而不是拿到空字符串去拼出坏地址。

use std::sync::Arc;

use crate::crypto::{ADD_SIGN_MAC, ADD_SIGN_VERSION, APP_PKG};
use crate::jsengine::HostBridge;

/// 引擎请求上游资源时使用的 UA（官方同款 AppleCoreMedia）。
const APPLE_UA: &str =
    "AppleCoreMedia/1.0.0.16F79 (iPhone; U; CPU OS 11_5 like Mac OS X; zh_cn)";

pub struct NativeBridge {
    http: reqwest::blocking::Client,
}

impl NativeBridge {
    pub fn new() -> Self {
        let http = reqwest::blocking::Client::builder()
            .user_agent(APPLE_UA)
            .timeout(std::time::Duration::from_secs(8))
            .build()
            .unwrap_or_else(|_| reqwest::blocking::Client::new());
        Self { http }
    }

    fn now_sec(&self) -> String {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs().to_string())
            .unwrap_or_default()
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
        )
    }

    fn call(&self, name: &str, a: &str, _b: &str, _c: &str) -> String {
        match name {
            "getPkgName" => APP_PKG.to_string(),
            "getAppVersion" => ADD_SIGN_VERSION.to_string(),
            "getNwtime" => self.now_sec(),
            "getMD5" => format!("{:x}", md5::compute(a.as_bytes())),
            "getMac" => ADD_SIGN_MAC.to_string(),
            "getImei" => "123456".to_string(),
            // 未登录 / 无定位 / 无写文件需求：给空串即可
            "getUserToken" | "getTmKey" | "getLocation" | "writeFile" => String::new(),
            "getUrlContent" | "getUrlContentWithHeader" => self.fetch_text(a),
            _ => String::new(),
        }
    }
}

impl NativeBridge {
    /// 同步 GET，失败一律返回空串（脚本按空内容自行降级）。
    fn fetch_text(&self, url: &str) -> String {
        if !url.starts_with("http") {
            return String::new();
        }
        match self.http.get(url).send() {
            Ok(r) if r.status().is_success() => r.text().unwrap_or_default(),
            _ => String::new(),
        }
    }
}

pub fn bridge() -> Arc<dyn HostBridge> {
    Arc::new(NativeBridge::new())
}
