//! tvbus:// 线路解析：驱动官方 PC 版 P2P 引擎（tvbus.exe）。
//!
//! ## 为什么不能靠 JS 引擎
//!
//! `encrypt_root.js` 的 `RootFunction` 里没有任何 tvbus 分支——它不属于「算法 JS」
//! 覆盖的协议族（kdsvod / ysptp / nxtv / 各类地方台才是），而是独立的 P2P 传输层，
//! 安卓端由 `libtvcore.so` + `com.tvbus.engine.TVCore` 提供。
//!
//! ## 桌面端怎么接
//!
//! binstream 官方提供了 win32 版 P2P 引擎 `tvbus.exe`，用法是纯文本协议：
//!
//! ```text
//! tvbus.exe init <p2p端口> <http端口>
//!   → stdout: [Initing] ... → [Inited]: ...
//!   ← stdin : start tvbus://xxxx        （必须保留 scheme，剥掉会解析失败）
//!   → stdout: [Prepared] http://127.0.0.1:<http端口>/live.ts
//!   ← stdin : stop / quit
//! ```
//!
//! 拿到的 `http://127.0.0.1:8902/live.ts` 是一条 MPEG-TS 直播流，交给前端的
//! mpegts.js 播放即可。
//!
//! ⚠️ 初始化（连 tracker）实测约 90 秒，所以应用启动时就要 `warmup()` 预热，
//! 否则用户第一次点 tvbus 频道会干等一分半。

use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// P2P 监听端口（UDP 直连/穿透用）
const P2P_PORT: u16 = 4000;
/// 本地 HTTP 端口：Prepared 后从这里吐 TS 流
pub const HTTP_PORT: u16 = 8902;
/// init 完成最长等待（实测 90s 上下，给足余量）
const INIT_TIMEOUT: Duration = Duration::from_secs(150);
/// 已 Inited 后 start 到 Prepared 的等待（实测 2~3s，慢网留余量）
const START_TIMEOUT: Duration = Duration::from_secs(25);

struct Inner {
    _child: Child,
    stdin: ChildStdin,
    /// 最近一次 `[Prepared]` 给出的本地播放地址
    prepared: Arc<Mutex<Option<String>>>,
    /// init 是否已完成
    inited: Arc<AtomicBool>,
}

/// 全局单例：同一进程只跑一个 P2P 引擎，切台时复用。
static ENGINE: Mutex<Option<Inner>> = Mutex::new(None);

/// 寻找可执行文件目录。
///
/// 优先级：APPDATA 缓存 → 安装目录 resources/tvbus → 开发目录。
fn engine_bin_dir() -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();

    if let Ok(appdata) = std::env::var("APPDATA") {
        candidates.push(PathBuf::from(appdata).join("ql-live").join("tvbus"));
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join("resources").join("tvbus"));
            // 开发模式：target/release/ql-live.exe → src-tauri/resources/tvbus
            candidates.push(dir.join("resources").join("tvbus"));
            if let Some(p) = dir.parent().and_then(|p| p.parent()) {
                candidates.push(p.join("resources").join("tvbus"));
                if let Some(p2) = p.parent() {
                    candidates.push(p2.join("resources").join("tvbus"));
                }
            }
        }
    }
    for c in candidates {
        if c.join("tvbus.exe").is_file() {
            return Some(c);
        }
    }
    None
}

/// 预热：后台拉起 P2P 引擎并等它连上 tracker。
///
/// 幂等，重复调用只会在未启动时启动一次。
pub fn warmup() {
    let mut guard = match ENGINE.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    if guard.is_some() {
        return;
    }
    match spawn_engine() {
        Ok(inner) => {
            eprintln!("[tvbus] 引擎已启动，等待初始化（约 90s）");
            *guard = Some(inner);
        }
        Err(e) => eprintln!("[tvbus] 引擎启动失败: {e}"),
    }
}

fn spawn_engine() -> Result<Inner, String> {
    let dir = engine_bin_dir().ok_or("找不到 tvbus.exe（缺少 resources/tvbus）")?;
    let exe = dir.join("tvbus.exe");
    let mut child = Command::new(&exe)
        .arg("init")
        .arg(P2P_PORT.to_string())
        .arg(HTTP_PORT.to_string())
        .current_dir(&dir)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        // 必须 give up stderr：piped 但没人读会把管道写满，进程直接卡死
        .stderr(Stdio::null())
        .creation_flags_no_window()
        .spawn()
        .map_err(|e| format!("启动 {} 失败: {e}", exe.display()))?;

    let stdin = child.stdin.take().ok_or("拿不到 stdin")?;
    let stdout = child.stdout.take().ok_or("拿不到 stdout")?;
    let inited = Arc::new(AtomicBool::new(false));
    let prepared = Arc::new(Mutex::new(None::<String>));

    {
        let inited = inited.clone();
        let prepared = prepared.clone();
        std::thread::spawn(move || {
            let reader = BufReader::new(stdout);
            for line in reader.lines() {
                let line = match line {
                    Ok(l) => l,
                    Err(_) => break,
                };
                let t = line.trim();
                if t.is_empty() {
                    continue;
                }
                if t.starts_with("[Inited]") {
                    inited.store(true, Ordering::SeqCst);
                    eprintln!("[tvbus] {t}");
                } else if let Some(rest) = t.strip_prefix("[Prepared]") {
                    let addr = rest.trim().to_string();
                    if addr.starts_with("http") {
                        eprintln!("[tvbus] 就绪 {addr}");
                        if let Ok(mut p) = prepared.lock() {
                            *p = Some(addr);
                        }
                    }
                } else if t.starts_with("[Initing]") || t.starts_with("[Start]") {
                    eprintln!("[tvbus] {t}");
                }
            }
        });
    }

    Ok(Inner {
        _child: child,
        stdin,
        prepared,
        inited,
    })
}

/// 把 `tvbus://xxx` 交给引擎，返回本地 TS 地址。
///
/// 第一次调用会等 init 完成（最多 150s）；已预热过则是秒级。
pub fn resolve(line: &str) -> Result<String, String> {
    let addr = line.trim();
    if !addr.starts_with("tvbus://") {
        return Err("不是 tvbus 线路".into());
    }

    warmup();

    let mut guard = ENGINE.lock().map_err(|_| "tvbus 引擎状态锁异常")?;
    let inner = guard.as_mut().ok_or("tvbus 引擎未启动")?;

    // 1) 等 init（首次可能很慢）
    if !inner.inited.load(Ordering::SeqCst) {
        let t0 = Instant::now();
        while t0.elapsed() < INIT_TIMEOUT {
            if inner.inited.load(Ordering::SeqCst) {
                break;
            }
            std::thread::sleep(Duration::from_millis(300));
        }
        if !inner.inited.load(Ordering::SeqCst) {
            return Err("tvbus 引擎初始化超时（连不上 P2P 网络）".into());
        }
    }

    // 2) 清空上一路结果，发 start
    if let Ok(mut p) = inner.prepared.lock() {
        *p = None;
    }
    // 必须带完整 scheme：实测剥掉 tvbus:// 后引擎会一直不 Prepared
    writeln!(inner.stdin, "start {addr}").map_err(|e| format!("写入引擎失败: {e}"))?;
    inner
        .stdin
        .flush()
        .map_err(|e| format!("刷新引擎输入失败: {e}"))?;

    // 3) 等 Prepared
    let t0 = Instant::now();
    while t0.elapsed() < START_TIMEOUT {
        if let Ok(p) = inner.prepared.lock() {
            if let Some(u) = p.as_ref() {
                return Ok(u.clone());
            }
        }
        std::thread::sleep(Duration::from_millis(150));
    }
    // 没起来就主动 stop，避免引擎还挂着上一路的半开连接影响下一条线
    let _ = writeln!(inner.stdin, "stop");
    let _ = inner.stdin.flush();
    Err(format!(
        "tvbus 解析超时（{}s 内无 Prepared）",
        START_TIMEOUT.as_secs()
    ))
}

/// 停掉当前流（切台/退出时用），保留引擎进程。
pub fn stop_current() {
    if let Ok(mut g) = ENGINE.lock() {
        if let Some(inner) = g.as_mut() {
            let _ = writeln!(inner.stdin, "stop");
            let _ = inner.stdin.flush();
            if let Ok(mut p) = inner.prepared.lock() {
                *p = None;
            }
        }
    }
}

/// 退出引擎（应用关闭时调用）。
pub fn shutdown() {
    if let Ok(mut g) = ENGINE.lock() {
        if let Some(inner) = g.as_mut() {
            let _ = writeln!(inner.stdin, "quit");
            let _ = inner.stdin.flush();
        }
        *g = None;
    }
}

// ---------------------------------------------------------------- 工具

#[cfg(windows)]
trait NoWindow {
    fn creation_flags_no_window(&mut self) -> &mut Self;
}

#[cfg(windows)]
impl NoWindow for Command {
    fn creation_flags_no_window(&mut self) -> &mut Self {
        use std::os::windows::process::CommandExt;
        // CREATE_NO_WINDOW：P2P 引擎是控制台程序，别让它弹出黑窗
        self.creation_flags(0x0800_0000)
    }
}

#[cfg(not(windows))]
trait NoWindow {
    fn creation_flags_no_window(&mut self) -> &mut Self;
}

#[cfg(not(windows))]
impl NoWindow for Command {
    fn creation_flags_no_window(&mut self) -> &mut Self {
        self
    }
}
