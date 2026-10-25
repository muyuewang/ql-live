//! 引擎宿主：让 `JsEngine` 整个进程只加载一次。
//!
//! 之前 `get_lines` 每次都调 `build_engine()`，等于每切一个台就：
//!   HEAD 一次 js.zip → 校验/必要时下载 → new QuickJS Runtime
//!   → 解密 encrypt_root.js（含 RSA 参数暴力还原）→ eval 全套 JS
//! 单次 0.5~2s 起步，是「换台要等好久」的最大头。
//!
//! 现在改成常驻工作线程：启动即在后台把引擎准备好，之后所有解析请求
//! 走 channel 复用同一个 Runtime。`JsEngine` 内部是 QuickJS（非 Send），
//! 所以必须固定在这一个线程里持有。

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use crate::bridge;
use crate::crypto;
use crate::jsengine::{ensure_bundle, JsEngine, DEFAULT_JS_ZIP_URL};
use crate::perf;

/// 单个解析请求最多等这么久（脚本内部可能带网络调用）。
const PARSE_TIMEOUT: Duration = Duration::from_secs(10);

/// 引擎已就绪（加载成功）。未就绪时调用方直接回退硬编码，不做无谓等待。
static READY: AtomicBool = AtomicBool::new(false);

static WORKER: OnceLock<Option<Sender<Req>>> = OnceLock::new();

enum Req {
    Parse { line: String, reply: Sender<Result<String, String>> },
}

/// 引擎 JS 的落地目录（与安装目录解耦，避免 Program Files 只读问题）。
pub fn engine_dir() -> PathBuf {
    if let Ok(appdata) = std::env::var("APPDATA") {
        return PathBuf::from(appdata).join("ql-live").join("js");
    }
    std::env::temp_dir().join("ql-live-js")
}

/// getCntvUrl 需要读的 auth 文件目录（等价安卓 MyApplication.dir）。
fn engine_auth_dir() -> PathBuf {
    engine_dir().join("auth")
}

/// 会话内缓存「服务端下发的引擎包版本与地址」。
///
/// 等价安卓 `JsUpdater`：官方把 js.zip 地址放在 `get_status_kgq.php` 的
/// `jsVersion/jsUrl` 里，写死任何地址都会在某次更新后失效。
static JS_BUNDLE: OnceLock<(u32, String)> = OnceLock::new();

/// 查配置接口拿 jsUrl；失败返回 None（调用方回退内置地址）。
fn discover_js_bundle() -> Option<(u32, String)> {
    let url = format!("{}/api/get_status_kgq.php?{}", crypto::HOST, crate::source::signed_params());
    let client = reqwest::blocking::Client::builder()
        .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
        .timeout(Duration::from_secs(8))
        .connect_timeout(Duration::from_secs(5))
        .build()
        .ok()?;
    let text = client.get(&url).send().ok()?.text().ok()?;
    let t = text.trim();
    let plain = if t.starts_with('{') {
        t.to_string()
    } else {
        crypto::aes_cbc_decrypt(t, &crypto::default_salt()).ok()?
    };
    let v: serde_json::Value = serde_json::from_str(plain.trim()).ok()?;
    let js_url = v.get("jsUrl")?.as_str()?.trim().to_string();
    if !js_url.starts_with("http") {
        return None;
    }
    let ver = v.get("jsVersion").and_then(|x| x.as_u64()).unwrap_or(0) as u32;
    Some((ver, js_url))
}

fn js_bundle() -> &'static (u32, String) {
    JS_BUNDLE.get_or_init(|| match discover_js_bundle() {
        Some((ver, url)) => {
            eprintln!("[engine] 配置接口: jsVersion={ver} jsUrl={url}");
            (ver, url)
        }
        None => {
            eprintln!("[engine] 配置接口不可用，使用内置 js.zip 地址兜底");
            (0, DEFAULT_JS_ZIP_URL.to_string())
        }
    })
}

fn fetch_bytes(url: &str) -> Result<Vec<u8>, String> {
    let client = reqwest::blocking::Client::builder()
        .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())?;
    let resp = client.get(url).send().map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("下载 js.zip 失败: HTTP {}", resp.status()));
    }
    resp.bytes().map(|b| b.to_vec()).map_err(|e| e.to_string())
}

/// 取远端 js.zip 的版本指纹（ETag + 长度）。拿不到返回 None，不影响主流程。
fn remote_js_tag(url: &str) -> Option<String> {
    let client = reqwest::blocking::Client::builder()
        .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
        .timeout(Duration::from_secs(8))
        .build()
        .ok()?;
    let resp = client.head(url).send().ok()?;
    if !resp.status().is_success() {
        return None;
    }
    let etag = resp
        .headers()
        .get(reqwest::header::ETAG)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    let len = resp
        .headers()
        .get(reqwest::header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    if etag.is_empty() && len.is_empty() {
        return None;
    }
    Some(format!("{etag}|{len}"))
}

/// 完整准备一次引擎：查地址 → 校验/下载 → 加载 QuickJS。只在 worker 线程跑一次。
fn load_engine() -> Option<JsEngine> {
    let dir = engine_dir();
    let (js_ver, js_url) = js_bundle();
    let t_tag = perf::Timer::new("engine.remote_tag");
    let remote_tag = remote_js_tag(js_url).map(|t| format!("v{js_ver}|{t}"));
    t_tag.done_q(if remote_tag.is_some() { "有指纹" } else { "无指纹" });

    let force = crate::jsengine::needs_refresh(&dir, remote_tag.as_deref());
    let t_dl = perf::Timer::new("engine.ensure_bundle");
    match ensure_bundle(&dir, force, js_url, fetch_bytes) {
        Ok(b) => {
            if b.downloaded {
                if let Some(t) = remote_tag.as_deref() {
                    crate::jsengine::write_tag(&dir, t);
                }
            }
            t_dl.done_q(&format!(
                "{}（{} 个文件）",
                if b.downloaded { "已下载/已更新" } else { "复用本地" },
                b.files.len()
            ));
        }
        Err(e) => {
            t_dl.done_q("失败");
            eprintln!("[engine] 引擎包不可用，回退硬编码解析: {e}");
            return None;
        }
    }
    let t_load = perf::Timer::new("engine.load");
    let bridge: Arc<dyn crate::jsengine::HostBridge> = bridge::bridge(engine_auth_dir());
    match JsEngine::load(&dir, bridge) {
        Ok(e) => {
            t_load.done_q(&format!("RSA 参数={:?}", e.params));
            Some(e)
        }
        Err(e) => {
            t_load.done_q("失败");
            eprintln!("[engine] 加载失败，回退硬编码解析: {e}");
            None
        }
    }
}

fn worker(rx: Receiver<Req>) {
    let t = perf::Timer::new("engine.boot");
    let eng = load_engine();
    let ok = eng.is_some();
    t.done_q(if ok { "就绪" } else { "不可用" });
    READY.store(ok, Ordering::Release);

    for req in rx {
        match req {
            Req::Parse { line, reply } => {
                let t = std::time::Instant::now();
                let r = match &eng {
                    Some(e) => e.parse_line(&line),
                    None => Err("引擎未就绪".to_string()),
                };
                let ms = t.elapsed().as_millis();
                // 成功才值得记；失败日志由调用方汇总，避免刷屏
                if r.is_ok() {
                    perf::log(&format!("engine.parse {}ms", ms));
                }
                let _ = reply.send(r);
            }
        }
    }
}

/// 启动常驻线程（幂等）。setup 里调一次即可开始预热。
pub fn prewarm() {
    let _ = worker_tx();
}

fn worker_tx() -> Option<&'static Sender<Req>> {
    WORKER
        .get_or_init(|| {
            let (tx, rx) = mpsc::channel::<Req>();
            match std::thread::Builder::new()
                .name("js-engine".into())
                .spawn(move || worker(rx))
            {
                Ok(_) => Some(tx),
                Err(e) => {
                    eprintln!("[engine] 无法启动引擎线程: {e}");
                    None
                }
            }
        })
        .as_ref()
}

/// 用常驻引擎解析一条线路。
///
/// 返回 `None` 表示「引擎还没就绪 / 线程不可用」——调用方应直接走硬编码，
/// 不要等，否则首次开机第一个台会被拖慢。
pub fn parse(line: &str) -> Option<Result<String, String>> {
    if !READY.load(Ordering::Acquire) {
        return None;
    }
    let tx = worker_tx()?;
    let (rtx, rrx) = mpsc::channel();
    tx.send(Req::Parse { line: line.to_string(), reply: rtx }).ok()?;
    match rrx.recv_timeout(PARSE_TIMEOUT) {
        Ok(r) => Some(r),
        Err(RecvTimeoutError::Timeout) => {
            perf::log("engine.parse 超时，回退硬编码");
            None
        }
        Err(RecvTimeoutError::Disconnected) => None,
    }
}

/// 引擎是否已可用（供诊断输出）。
pub fn is_ready() -> bool {
    READY.load(Ordering::Acquire)
}
