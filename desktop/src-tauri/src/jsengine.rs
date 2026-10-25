//! 运行时执行服务商下发的「算法 JS」。
//!
//! 为什么不能只硬编码：官方把解析算法放在 `file.91kds.cn/js/js-*.zip` 里，
//! 靠 `StartBean.jsVersion/jsUrl` 热更新。实测 20241115 那版除了改 `addSign` 的
//! mac/ev，连 JS 的 RSA 混淆参数都一起换成 N=12367/d=2023。任何把算法或参数写死
//! 的实现，都会在某次更新后静默失效。
//!
//! 所以这里走官方同款链路，但 replacy 掉 WebView：
//!
//! ```text
//! js.zip ──下载──> 解压 ──jsdec 自动还原参数解密──> 内嵌 QuickJS 执行
//!                                                        │
//!                                    client.* 桥接 ◀──────┘
//! ```
//!
//! 引擎侧对宿主的要求很低：`encrypt_other_live.js` / `encrypt_migu_live.js` 里
//! **没有任何 DOM / jQuery 引用**，宿主只需提供 21 个同步 `client.*` 方法中的一部分
//! （kdsvod 这条路只用 4 个：getPkgName / getAppVersion / getNwtime / getMD5，
//! 外加纯 JS 的 getHost）。
//!
//! 安全说明：QuickJS 默认环境不含文件系统与网络，只有本模块显式注入的
//! `__native` 一个入口，脚本能做的仅是通过 `HostBridge` 申请白名单内的能力。

use std::cell::RefCell;
use std::collections::HashSet;
use std::io::{Cursor, Read};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use rquickjs::context::EvalOptions;
use rquickjs::{Context, Ctx, Function, Runtime};

use crate::jsdec;

/// 引擎运行期需要宿主提供的能力。
///
/// 全部是同步调用（官方 WebView 的 `@JavascriptInterface` 也是同步的）。
#[allow(unused_variables)]
pub trait HostBridge: Send + Sync {
    /// 该能力是否可用。返回 false 时 JS 侧不会定义对应 `client.*`，
    /// 从而走脚本自带的 `typeof client.xxx != 'undefined'` 降级分支。
    fn supports(&self, name: &str) -> bool {
        false
    }
    /// 实际的同步调用
    fn call(&self, name: &str, a: &str, b: &str, c: &str, d: &str) -> String {
        let _ = (a, b, c, d);
        String::new()
    }
}

/// 官方默认 js.zip（StartBean 不可达时的兜底）
pub const DEFAULT_JS_ZIP_URL: &str = "http://file.91kds.cn/js/js-20241115.zip";

/// bundle 必须存在的文件
pub const BUNDLE_FILES: &[&str] = &[
    "encrypt_root.js",
    "encrypt_other_live.js",
    "encrypt_migu_live.js",
    "native_layer.js",
];

/// JS 前置环境：官方这些脚本跑在 WebView 里，依赖 window / console / client。
const JS_PRELUDE: &str = r#"
var window = globalThis;
if (typeof console === 'undefined') { var console = {}; }
if (typeof console.log !== 'function') { console.log = function(){}; }
if (typeof console.error !== 'function') { console.error = function(){}; }
if (typeof console.warn !== 'function') { console.warn = function(){}; }

var client = {};
(function () {
    // 官方引擎 JS 实际调用到的全部原生方法（等价安卓 ParserJsInterface）。
    var names = [
        'getPkgName', 'getAppVersion', 'getNwtime', 'getMD5', 'getMac',
        'getImei', 'getUserToken', 'getTmKey', 'getLocation', 'writeFile',
        'getUrlContent', 'getUrlContentWithHeader', 'getPostContent',
        'getPostContent1', 'getPostRespAndHeaders', 'getRespAndHeaders',
        'AESDecrypt128', 'encC20Ep', 'getJxtvKey', 'getBstFk',
        'getCntvUrl', 'getHncscsKey', 'getUtid'
    ];
    function str(v) { return (v === undefined || v === null) ? '' : String(v); }
    for (var i = 0; i < names.length; i++) {
        (function (nm) {
            if (__native('__has', nm, '', '', '') !== '1') { return; }
            client[nm] = function (a, b, c, d) {
                return __native(nm, str(a), str(b), str(c), str(d));
            };
        })(names[i]);
    }
})();

// 官方引擎里 getParseResult(methodName, url) 等价于 window[methodName](url)。
// 用 try/catch 包一层：QuickJS 的 Rust 侧错误只有 "Exception generated"，
// 拿不到 message 就无法定位是脚本哪一行挂了。
function __qlParse(name, url) {
    try {
        if (typeof window[name] !== 'function') {
            return JSON.stringify({ ok: false, err: '引擎没有函数 ' + name });
        }
        return JSON.stringify({ ok: true, v: window[name](url) });
    } catch (e) {
        var msg = '';
        try {
            msg = (e && e.name ? e.name + ': ' : '') +
                  (e && e.message ? e.message : String(e));
        } catch (x) { msg = '无法读取的异常'; }
        return JSON.stringify({ ok: false, err: msg, stack: String((e && e.stack) || '') });
    }
}
"#;

/// 以「非严格 + 全局」方式执行脚本。
///
/// rquickjs 的 `EvalOptions::default()` 是 `strict: true`，而这些引擎脚本大量依赖
/// 隐式全局（`playUrl = ...` 之类）。严格模式下会直接抛 `ReferenceError`，
/// 与官方 WebView 的行为不一致，所以这里必须显式关掉。
fn eval_sloppy(ctx: &Ctx<'_>, src: &str) -> Result<(), rquickjs::Error> {
    let mut opts = EvalOptions::default();
    opts.global = true;
    opts.strict = false;
    ctx.eval_with_options::<(), _>(src, opts)
}

/// 从 js.zip 字节内容解压到 `dir`（只取文件名，防目录穿越）。
pub fn extract_zip(bytes: &[u8], dir: &Path) -> Result<Vec<String>, String> {
    std::fs::create_dir_all(dir).map_err(|e| format!("创建目录失败: {e}"))?;
    let mut ar = zip::ZipArchive::new(Cursor::new(bytes.to_vec()))
        .map_err(|e| format!("不是有效的 zip: {e}"))?;
    let mut out = Vec::new();
    for i in 0..ar.len() {
        let mut f = ar.by_index(i).map_err(|e| format!("读取 zip 条目失败: {e}"))?;
        let raw = match f.name() {
            Ok(n) => n.to_string(),
            Err(e) => return Err(format!("读取 zip 条目名失败: {e}")),
        };
        if raw.ends_with('/') {
            continue;
        }
        let base = match Path::new(&raw).file_name() {
            Some(b) => b.to_string_lossy().to_string(),
            None => continue,
        };
        if base.is_empty() || base.contains("..") {
            continue;
        }
        let mut buf = Vec::new();
        f.read_to_end(&mut buf)
            .map_err(|e| format!("解压 {base} 失败: {e}"))?;
        std::fs::write(dir.join(&base), &buf)
            .map_err(|e| format!("写入 {base} 失败: {e}"))?;
        out.push(base);
    }
    if out.is_empty() {
        return Err("zip 内没有可用文件".into());
    }
    Ok(out)
}

/// bundle 状态
#[derive(Clone, Debug)]
pub struct Bundle {
    pub dir: PathBuf,
    pub files: Vec<String>,
    /// 本次是否刚下载过
    pub downloaded: bool,
}

/// 确保 `dir` 内有完整的引擎 JS。
///
/// * 已存在且齐全 → 直接复用（除非 `force`）
/// * 缺文件 / force → 调用 `fetch(url)` 取 zip 并解压
///
/// `fetch` 由调用方决定（桌面用 reqwest，测试可读本地文件），使本模块不绑定网络实现。
pub fn ensure_bundle<F>(
    dir: &Path,
    force: bool,
    url: &str,
    fetch: F,
) -> Result<Bundle, String>
where
    F: Fn(&str) -> Result<Vec<u8>, String>,
{
    let complete = BUNDLE_FILES.iter().all(|f| {
        dir.join(f)
            .metadata()
            .map(|m| m.len() > 0)
            .unwrap_or(false)
    });
    if complete && !force {
        return Ok(Bundle {
            dir: dir.to_path_buf(),
            files: BUNDLE_FILES.iter().map(|s| s.to_string()).collect(),
            downloaded: false,
        });
    }

    let bytes = fetch(url)?;
    let files = extract_zip(&bytes, dir)?;
    let missing: Vec<&str> = BUNDLE_FILES
        .iter()
        .filter(|f| !files.iter().any(|x| x == *f))
        .copied()
        .collect();
    if !missing.is_empty() {
        return Err(format!("js.zip 缺少必需文件: {}", missing.join(", ")));
    }
    Ok(Bundle {
        dir: dir.to_path_buf(),
        files,
        downloaded: true,
    })
}

/// 记录远端 js.zip 的版本标记（用 ETag/Content-Length 拼的指纹）。
pub fn read_tag(dir: &Path) -> Option<String> {
    std::fs::read_to_string(dir.join(".js_zip_tag"))
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}

/// 写入版本标记；失败不影响主流程。
pub fn write_tag(dir: &Path, tag: &str) {
    let _ = std::fs::create_dir_all(dir);
    let _ = std::fs::write(dir.join(".js_zip_tag"), tag.trim());
}

/// 本地引擎包超过这么多秒就强制刷新一次。
///
/// js.zip 的 CDN 只给 `Content-Length` 不给 ETag，所以「长度没变 = 没更新」这个
/// 判断会漏掉等长替换。加一个时间兜底，最坏情况延迟一个周期拿到新版。
const REFRESH_AFTER_SECS: u64 = 3 * 24 * 3600;

fn bundle_age_secs(dir: &Path) -> Option<u64> {
    let meta = std::fs::metadata(dir.join("encrypt_root.js")).ok()?;
    let mtime = meta.modified().ok()?;
    mtime.elapsed().ok().map(|d| d.as_secs())
}

/// 远端标记与本地不一致（或本地没有标记）时说明引擎包可能更新过。
pub fn needs_refresh(dir: &Path, remote_tag: Option<&str>) -> bool {
    if matches!(bundle_age_secs(dir), Some(age) if age > REFRESH_AFTER_SECS) {
        return true;
    }
    match (remote_tag, read_tag(dir)) {
        (Some(remote), Some(local)) => remote != local,
        (Some(_), None) => true,
        // 拿不到远端标记时不要盲目重下，沿用本地缓存
        (None, _) => false,
    }
}

/// 内嵌引擎实例。
///
/// 注意字段顺序：`Context` 必须先于 `Runtime` 声明，保证析构顺序正确。
pub struct JsEngine {
    ctx: Context,
    /// 仅为持有运行时（Context 依赖它）；字段顺序保证 ctx 先析构。
    #[allow(dead_code)]
    rt: Runtime,
    dir: PathBuf,
    loaded: RefCell<HashSet<String>>,
    /// 实际使用的解密参数（首次加载时确定）
    pub params: Option<jsdec::Params>,
}

impl JsEngine {
    /// 从 bundle 目录加载并运行全套引擎 JS。
    pub fn load(dir: impl AsRef<Path>, bridge: Arc<dyn HostBridge>) -> Result<Self, String> {
        let dir = dir.as_ref().to_path_buf();
        let rt = Runtime::new().map_err(|e| format!("创建 JS 运行时失败: {e}"))?;
        let ctx = Context::full(&rt).map_err(|e| format!("创建 JS 上下文失败: {e}"))?;

        // native_layer.js 是明文；encrypt_*.js 可能是加密数字串，统一走 decrypt_auto
        let native = load_js(&dir, "native_layer.js")?;
        let root = load_js(&dir, "encrypt_root.js")?;
        let params = jsdec_param_hint(&dir);
        let mut loaded = HashSet::new();
        loaded.insert("encrypt_root.js".to_string());

        ctx.with(|ctx| -> Result<(), String> {
            let globals = ctx.globals();
            let b = bridge.clone();
            let native_fn = Function::new(
                ctx.clone(),
                move |name: String, a: String, b2: String, c: String, d: String| -> String {
                    if name == "__has" {
                        return if b.supports(&a) { "1".into() } else { "0".into() };
                    }
                    b.call(&name, &a, &b2, &c, &d)
                },
            )
            .map_err(|e| format!("注册桥接失败: {e}"))?;
            globals
                .set("__native", native_fn)
                .map_err(|e| format!("挂载桥接失败: {e}"))?;
            eval_sloppy(&ctx, JS_PRELUDE).map_err(|e| format!("前置环境执行失败: {e}"))?;
            eval_sloppy(&ctx, &native).map_err(|e| format!("native_layer.js 执行失败: {e}"))?;
            eval_sloppy(&ctx, &root).map_err(|e| format!("encrypt_root.js 执行失败: {e}"))?;
            Ok(())
        })?;

        Ok(Self {
            ctx,
            rt,
            dir,
            loaded: RefCell::new(loaded),
            params,
        })
    }

    /// 给一个线路 URL，返回引擎解析出的最终播放地址（已剥离 `@@...@@` 附加头）。
    pub fn parse(&self, url: &str) -> Result<String, String> {        let found = self.ctx.with(|ctx| -> Result<String, String> {
            let get_parser: Function = ctx
                .globals()
                .get("getParser")
                .map_err(|e| format!("找不到 getParser: {e}"))?;
            let raw: String = get_parser
                .call((url.to_string(),))
                .map_err(|e| format!("getParser 调用失败: {e}"))?;

            let names: Vec<String> = serde_json::from_str(&raw)
                .map_err(|e| format!("getParser 返回值无法解析({raw}): {e}"))?;
            let file = names.first().cloned().unwrap_or_default();
            let func = names.get(1).cloned().unwrap_or_default();
            if file.is_empty() || func.is_empty() {
                return Err(format!("引擎未给出解析器（{raw}）"));
            }

            let module = module_file_name(&file);
            let need_load = !self.loaded.borrow().contains(&module);
            if need_load {
                let src = load_js(&self.dir, &module)?;
                eval_sloppy(&ctx, &src).map_err(|e| format!("{module} 执行失败: {e}"))?;
                self.loaded.borrow_mut().insert(module.clone());
            }

            let parse: Function = ctx
                .globals()
                .get("__qlParse")
                .map_err(|e| format!("找不到 __qlParse: {e}"))?;
            let out: String = parse
                .call((func.clone(), url.to_string()))
                .map_err(|e| format!("{func} 执行失败: {e}"))?;

            let wrapper: serde_json::Value = serde_json::from_str(&out)
                .map_err(|e| format!("{func} 返回值无法解析({out}): {e}"))?;
            if wrapper.get("ok").and_then(|v| v.as_bool()) != Some(true) {
                let msg = wrapper
                    .get("err")
                    .and_then(|v| v.as_str())
                    .unwrap_or("未知脚本错误");
                return Err(format!("{func}: {msg}"));
            }
            let inner = wrapper.get("v").cloned().unwrap_or(serde_json::Value::Null);
            Ok(pick_url_value(&inner))
        })?;
        Ok(found)
    }

    /// 对「取源拿到的原始线路串」做解析。
    ///
    /// 引擎脚本自身不剥 `kdsvod://` 前缀（官方播放器在传入前已剥），
    /// 这里统一处理，避免产出 `kdsvod://http://...` 这种非法地址。
    pub fn parse_line(&self, line: &str) -> Result<String, String> {
        let mut u = line.trim();
        for p in ["kdsvod://", "iptvdllbk://"] {
            if let Some(rest) = u.strip_prefix(p) {
                u = rest;
            }
        }
        self.parse(u)
    }
}

/// `other_live.js` → `encrypt_other_live.js`
fn module_file_name(file: &str) -> String {
    if file.starts_with("encrypt_") {
        file.to_string()
    } else {
        format!("encrypt_{file}")
    }
}

/// 引擎返回 `["http://...@@User-Agent=...@@#91专线"]` 或 `"http://..."`
fn pick_url_value(v: &serde_json::Value) -> String {
    let first = match v {
        serde_json::Value::String(s) => Some(s.clone()),
        serde_json::Value::Array(a) => a
            .iter()
            .find_map(|x| x.as_str().map(|s| s.to_string())),
        _ => None,
    }
    .unwrap_or_default();
    strip_headers(&first)
}

/// 去掉引擎附加的 `@@User-Agent=...@@` 等头与 `#91专线` 标记。
///
/// Windows 端必须这么做：这些头是给官方播放器解析的，直接留在 URL 里会带空格，
/// 请求非法（历史「有流量却黑屏」的根因）。UA 由前端 hls.js 的 xhrSetup 注入。
pub fn strip_headers(s: &str) -> String {
    let mut u = s.to_string();
    if let Some(i) = u.find("@@") {
        u.truncate(i);
    }
    if let Some(i) = u.find('#') {
        u.truncate(i);
    }
    u.trim().to_string()
}

fn load_js(dir: &Path, name: &str) -> Result<String, String> {
    let path = dir.join(name);
    let text = std::fs::read_to_string(&path)
        .map_err(|e| format!("读取 {name} 失败: {e}"))?;
    let (js, _p) = jsdec::decrypt_auto(&text)?;
    Ok(js)
}

fn jsdec_param_hint(dir: &Path) -> Option<jsdec::Params> {
    let text = std::fs::read_to_string(dir.join("encrypt_root.js")).ok()?;
    if !jsdec::looks_encrypted(&text) {
        return None;
    }
    let nums = jsdec::parse_nums(&text)?;
    jsdec::recover(&nums)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strip_engine_headers() {
        let s = "http://a/b.m3u8?sign=1@@User-Agent=AppleCoreMedia/1.0 (iPhone)@@#91专线";
        assert_eq!(strip_headers(s), "http://a/b.m3u8?sign=1");
    }

    #[test]
    fn module_name_mapping() {
        assert_eq!(module_file_name("other_live.js"), "encrypt_other_live.js");
        assert_eq!(module_file_name("encrypt_root.js"), "encrypt_root.js");
    }
}
