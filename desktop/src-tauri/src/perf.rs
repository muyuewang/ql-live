//! 轻量耗时打点。
//!
//! 桌面 exe 是 windows_subsystem="windows"，没有控制台可看 eprintln；
//! 排查「切台慢」这类问题必须能拿到真实分阶段耗时，所以设 `QL_PERF=1`
//! 时把打点同时追加到 `%APPDATA%/ql-live/perf.log`。

use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;
use std::time::Instant;

static ON: AtomicBool = AtomicBool::new(false);
static START: OnceLock<Instant> = OnceLock::new();

fn start() -> &'static Instant {
    START.get_or_init(Instant::now)
}

/// 默认开启：每次运行覆盖写 `%APPDATA%/ql-live/perf.log`（几十行，纯文本）。
/// 出问题时能直接对着时间轴定位是「取源慢」还是「播放器拉流慢」。
/// 不想留痕设 `QL_PERF=0`。
pub fn init() {
    let on = std::env::var("QL_PERF").map(|v| v != "0").unwrap_or(true);
    ON.store(on, Ordering::Relaxed);
    start();
    if on {
        // 每次运行覆盖，避免日志无限增长
        if let Some(p) = log_path() {
            let _ = std::fs::create_dir_all(p.parent().unwrap_or(&p));
            let _ = std::fs::write(&p, b"");
        }
        log("perf 打点开启");
    }
}

fn log_path() -> Option<PathBuf> {
    let appdata = std::env::var("APPDATA").ok()?;
    Some(PathBuf::from(appdata).join("ql-live").join("perf.log"))
}

fn now_ms() -> u128 {
    start().elapsed().as_millis()
}

/// 打一条耗时，自动带「距进程启动多少 ms」。
pub fn log(msg: &str) {
    let line = format!("[{:>6}ms] {}", now_ms(), msg);
    eprintln!("[perf] {line}");
    if !ON.load(Ordering::Relaxed) {
        return;
    }
    if let Some(p) = log_path() {
        let _ = std::fs::create_dir_all(p.parent().unwrap_or(&p));
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&p) {
            let _ = writeln!(f, "{line}");
        }
    }
}

/// 阶段计时器：`let t = perf::Timer::new("fetch_source"); ... t.done();`
pub struct Timer {
    what: &'static str,
    at: Instant,
}

impl Timer {
    pub fn new(what: &'static str) -> Self {
        Self { what, at: Instant::now() }
    }
    pub fn done(self) -> u128 {
        let ms = self.at.elapsed().as_millis();
        log(&format!("{} {}ms", self.what, ms));
        ms
    }
    pub fn done_q(self, extra: &str) -> u128 {
        let ms = self.at.elapsed().as_millis();
        log(&format!("{} {}ms {}", self.what, ms, extra));
        ms
    }
}
