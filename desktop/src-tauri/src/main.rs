#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod bridge;
mod crypto;
mod engine_host;
mod extra;
mod jsdec;
mod jsengine;
mod perf;
mod source;

use extra::{
    add_custom, get_custom, get_epg, get_favs, import_custom, remove_custom, toggle_fav,
};
use source::{
    fetch_group, fetch_provs, fetch_source, probe, resolve_htmlplay, resolve_kdsvod_sign,
    resolve_playable, Channel, PlayLine,
};
use std::collections::{HashMap, HashSet};
use std::sync::{mpsc, Mutex, OnceLock};
use std::time::{Duration, Instant};

// ===== 取源结果缓存 =====
//
// 切台慢的体感里，有相当一部分是「刚看过、又切回来」的往返。
// 早期只缓存 2 分钟，用户切台来回扫一遍就过期了，等于每次都在冷启动。
// 线路在 10 分钟内基本不会变；真失效了探测/首帧看门狗会自动换线，
// 所以这里放宽到 10 分钟，换来「来回切台近乎瞬时」。

const LINES_TTL: Duration = Duration::from_secs(600);

struct Cached {
    at: Instant,
    lines: Vec<PlayLine>,
}

fn lines_cache() -> &'static Mutex<HashMap<String, Cached>> {
    static C: OnceLock<Mutex<HashMap<String, Cached>>> = OnceLock::new();
    C.get_or_init(|| Mutex::new(HashMap::new()))
}

fn cache_fresh(ename: &str) -> Option<Vec<PlayLine>> {
    let mut m = lines_cache().lock().ok()?;
    match m.get(ename) {
        Some(c) if c.at.elapsed() < LINES_TTL => Some(c.lines.clone()),
        Some(_) => {
            m.remove(ename);
            None
        }
        None => None,
    }
}

fn cache_has(ename: &str) -> bool {
    match lines_cache().lock() {
        Ok(m) => m.get(ename).map(|c| c.at.elapsed() < LINES_TTL).unwrap_or(false),
        Err(_) => false,
    }
}

fn cache_put(ename: &str, lines: Vec<PlayLine>) {
    if let Ok(mut m) = lines_cache().lock() {
        // 超过上限只淘汰最旧的一条：以前是 clear() 全清，
        // 一跨过阈值就把所有频道的热缓存一起扔掉，切台重新变冷。
        if m.len() >= 400 {
            if let Some(oldest) = m
                .iter()
                .min_by_key(|(_, c)| c.at)
                .map(|(k, _)| k.clone())
            {
                m.remove(&oldest);
            }
        }
        m.insert(ename.to_string(), Cached { at: Instant::now(), lines });
    }
}

/// 正在预取的频道（去重），防止鼠标来回划就发一堆重复请求。
fn inflight() -> &'static Mutex<HashSet<String>> {
    static S: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();
    S.get_or_init(|| Mutex::new(HashSet::new()))
}

// ===== 线路解析 =====

/// 每批只解析这么多条「需二次解析」的线路。
///
/// 一个频道常有 20+ 条 kdsvod 线路，全解析要串行跑 20 多次引擎 + 20 多路探测，
/// 实测能拖到 5s 以上。但第一条基本就能播 —— 所以按批推进，第一批有可播结果
/// 就立刻返回，剩下的等用户真去切线路再说。
const PARSE_BATCH: usize = 3;

/// 一批候选线路的探测最多等这么久。
///
/// 探测是并行发出的，个别源站 302 链会拖到 2s+，把整批卡住最没价值。
/// 超时未回的线路按「乐观可播」交给前端——播不出来自然走自动换线，
/// 总比让用户多干等 1.5 秒强。
const PROBE_WAIT: Duration = Duration::from_millis(1200);

/// 并行探测一批地址，`None` 表示超时未返回（未知）。
///
/// 关键取舍：前端只会播「排在最前面的可播线路」，所以只要第一个候选确认可播，
/// 就没必要再等其它几条（个别死源会把 302 拖到 1s+）。实测这一条判断能把
/// 常见切台从 ~1.2s 压到 ~0.5s。
fn probe_urls(urls: &[String]) -> Vec<Option<(bool, bool, String)>> {
    let n = urls.len();
    let mut out = vec![None; n];
    if n == 0 {
        return out;
    }
    // (下标, 可播, 是否HLS, 302 后的最终地址)
    let (tx, rx) = mpsc::channel::<(usize, bool, bool, String)>();
    for (i, u) in urls.iter().enumerate() {
        let tx = tx.clone();
        let u = u.clone();
        // 不 join：超时那条让它自己在后台跑完即可，结果丢弃
        std::thread::spawn(move || {
            let r = probe(&u);
            let _ = tx.send((i, r.playable, r.hls, r.final_url));
        });
    }
    drop(tx);
    let deadline = Instant::now() + PROBE_WAIT;
    let mut got = 0usize;
    while got < n {
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            break;
        }
        match rx.recv_timeout(left) {
            Ok((i, ok, hls, fu)) => {
                out[i] = Some((ok, hls, fu));
                got += 1;
                // 第一个候选就能播 → 收工，剩下的按乐观可播交出去
                if i == 0 && ok {
                    break;
                }
            }
            Err(_) => break,
        }
    }
    out
}

/// 解析一批候选线路（就地写回 `out`），并对 kdsvod 结果并行做有效性探测。
fn resolve_batch(items: &[(usize, String, String)], out: &mut [PlayLine]) {
    let mut probe: Vec<(usize, String)> = Vec::new();
    for (idx, raw, kind) in items {
        if kind == "htmlplay" {
            // htmlplay 需要抓网页（网络），但这类线路数量少
            if let Ok((u, referer)) = resolve_htmlplay(raw) {
                out[*idx].hls = u.contains(".m3u8");
                out[*idx].url = u;
                out[*idx].playable = true;
                out[*idx].referer = referer;
            }
        } else {
            let resolved = engine_host::parse(raw)
                .and_then(|r| r.ok())
                .or_else(|| resolve_kdsvod_sign(raw).ok());
            if let Some(u) = resolved {
                out[*idx].url = u.clone();
                out[*idx].playable = true; // 先标可播，探测失败再改回
                probe.push((*idx, u));
            }
        }
    }
    if probe.is_empty() {
        return;
    }
    let urls: Vec<String> = probe.iter().map(|(_, u)| u.clone()).collect();
    let results = probe_urls(&urls);
    for (k, (idx, _)) in probe.iter().enumerate() {
        match results.get(k).cloned().flatten() {
            Some((ok, hls, final_url)) => {
                out[*idx].playable = ok;
                // 探测就是按 #EXTM3U 判定的，可播即说明是 HLS 清单
                out[*idx].hls = hls;
                // 直接用跟随 302 后的地址：省掉播放器那一次跳转（这些源一次 500ms）
                if ok && final_url.starts_with("http") {
                    out[*idx].url = final_url;
                }
            }
            // 未在时限内返回：先放行，让前端首播去验（按 HLS 处理，走 hls.js 更稳）
            None => {
                out[*idx].playable = true;
                out[*idx].hls = true;
            }
        }
    }
}

/// 把原始线路串整理成前端可用形态：分类 + 分批解析 + 并行探测。
fn collect_lines(raw: Vec<PlayLine>) -> Vec<PlayLine> {
    let mut out: Vec<PlayLine> = Vec::with_capacity(raw.len());
    // (在 out 中的下标, 原始串, 协议类型) —— 需要二次解析的线路
    let mut queue: Vec<(usize, String, String)> = Vec::new();

    for l in raw {
        let r = resolve_playable(&l.url);
        let idx = out.len();
        let needs = r.kind == "htmlplay"
            || (r.kind == "kdsvod" && !r.playable && l.url.contains("pwd=jsdecode"));
        if needs {
            out.push(PlayLine {
                url: r.url.clone(),
                label: l.label,
                playable: false,
                kind: r.kind.clone(),
                referer: String::new(),
                hls: false,
            });
            queue.push((idx, l.url, r.kind));
        } else {
            out.push(PlayLine {
                url: r.url,
                label: l.label,
                playable: r.playable,
                kind: r.kind,
                referer: String::new(),
                hls: false,
            });
        }
    }

    // 已有直连线路 → 首播不等解析，只把第一批当备选解析掉
    if out.iter().any(|l| l.playable) {
        let end = queue.len().min(PARSE_BATCH);
        if end > 0 {
            resolve_batch(&queue[..end], &mut out);
        }
        return out;
    }

    // 没有直连 → 逐批解析，直到出现可播线路
    let mut pos = 0usize;
    while pos < queue.len() {
        let end = (pos + PARSE_BATCH).min(queue.len());
        let t = perf::Timer::new("lines.batch");
        resolve_batch(&queue[pos..end], &mut out);
        let ok = queue[pos..end].iter().filter(|(i, _, _)| out[*i].playable).count();
        t.done_q(&format!("第 {}-{} 条，可播 {}", pos + 1, end, ok));
        if ok > 0 {
            break;
        }
        pos = end;
    }
    out
}

fn lines_for(ename: &str, allow_cache: bool) -> Result<Vec<PlayLine>, String> {
    if allow_cache {
        if let Some(v) = cache_fresh(ename) {
            perf::log(&format!("lines[{ename}] 缓存命中（{} 条）", v.len()));
            return Ok(v);
        }
    }
    let t = perf::Timer::new("lines.fetch_source");
    let raw = fetch_source(ename)?;
    t.done_q(&format!("{} 条", raw.len()));

    let t2 = perf::Timer::new("lines.collect");
    let out = collect_lines(raw);
    t2.done_q(&format!(
        "可播 {}/{}",
        out.iter().filter(|l| l.playable).count(),
        out.len()
    ));

    cache_put(ename, out.clone());
    Ok(out)
}

// ===== 线程模型 =====
//
// 重要：Tauri 的**同步命令**（`fn`，不是 `async fn`）是在 UI 主线程上执行的。
// 这些命令里全是 reqwest 阻塞请求，一旦放主线程，整个窗口（含 WebView 重绘、
// CSS 过渡、loading 动画）全被冻住 —— 表现就是「点了频道窗口卡住不动」。
//
// 所以所有会阻塞的命令一律走 `off_ui`：真正的阻塞动作放在独立 std::thread，
// 主线程只 await 一个 recv。注意不能用 async_runtime::spawn_blocking 直接跑
// reqwest 阻塞客户端（它内部会自建 runtime，在 Tokio 上下文中会卡死），
// 必须落到自己的 std::thread 上。
pub(crate) async fn off_ui<T, F>(f: F) -> Result<T, String>
where
    T: Send + 'static,
    F: FnOnce() -> T + Send + 'static,
{
    let (tx, rx) = mpsc::channel::<T>();
    std::thread::spawn(move || {
        // 自证：真正的阻塞动作确实不在 UI 主线程上跑（只记一次，避免刷屏）
        static LOGGED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
        if !LOGGED.swap(true, std::sync::atomic::Ordering::Relaxed) {
            perf::log(&format!(
                "off_ui 阻塞任务运行在主线程={}",
                perf::on_main_thread()
            ));
        }
        let _ = tx.send(f());
    });
    tauri::async_runtime::spawn_blocking(move || rx.recv())
        .await
        .map_err(|e| format!("后台任务失败: {e}"))?
        .map_err(|e| format!("后台任务中断: {e}"))
}

// ===== Tauri 命令 =====

#[derive(serde::Serialize)]
pub struct BootTarget {
    /// 全部分组（前端渲染分组下拉用）
    pub groups: Vec<String>,
    /// 第一个真正有频道的分组；空串表示所有分组都没频道
    pub group: String,
    pub channels: Vec<Channel>,
}

#[tauri::command]
async fn get_groups() -> Result<Vec<String>, String> {
    off_ui(fetch_provs).await?
}

#[tauri::command]
async fn get_channels(group: String) -> Result<Vec<Channel>, String> {
    off_ui(move || fetch_group(&group)).await?
}

#[tauri::command]
async fn get_lines(ename: String) -> Result<Vec<PlayLine>, String> {
    off_ui(move || {
        let t = perf::Timer::new("get_lines");
        let r = lines_for(&ename, true);
        t.done();
        r
    })
    .await?
}

/// 启动落点：按分组顺序找第一个真正有频道的分组，并取回它的频道列表。
///
/// 放在 Rust 侧一次算完，前端启动只发一次 IPC：
/// 「收藏」分组常年是空的，逐个来回问会把启动拖成好几秒。
///
/// 注意：这里必须用**独立 std::thread** 去跑 reqwest 的阻塞客户端，
/// 不能直接 put 进异步线程池（阻塞客户端在 Tokio 上下文里会出问题）。
#[tauri::command]
async fn boot_target() -> Result<BootTarget, String> {
    let (tx, rx) = std::sync::mpsc::channel::<Result<BootTarget, String>>();
    std::thread::spawn(move || {
        let r = (|| -> Result<BootTarget, String> {
            let provs = fetch_provs()?;
            let mut found: Option<(String, Vec<Channel>)> = None;
            for i in 0..provs.len().min(10) {
                if let Ok(chs) = fetch_group(&provs[i]) {
                    if !chs.is_empty() {
                        found = Some((provs[i].clone(), chs));
                        break;
                    }
                }
            }
            Ok(match found {
                Some((g, chs)) => BootTarget {
                    groups: provs,
                    group: g,
                    channels: chs,
                },
                None => BootTarget {
                    groups: provs,
                    group: String::new(),
                    channels: Vec::new(),
                },
            })
        })();
        let _ = tx.send(r);
    });
    tauri::async_runtime::spawn_blocking(move || rx.recv())
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())?
}

/// 后台预取某个频道的线路（鼠标悬停/光标停留时由前端触发）。
///
/// 顺带把线路列表返回给前端：前端拿首条可播地址去预热播放器的 HTTP 缓存
/// （这些源首片只有几 KB 却要 1s+，纯 RTT 决定，只能靠提前取）。
#[tauri::command]
async fn prefetch_lines(ename: String) -> Vec<PlayLine> {
    tauri::async_runtime::spawn_blocking(move || {
        if cache_has(&ename) {
            return lines_for(&ename, true).unwrap_or_default();
        }
        {
            let mut s = match inflight().lock() {
                Ok(g) => g,
                Err(_) => return Vec::new(),
            };
            if !s.insert(ename.clone()) {
                return Vec::new(); // 已在预取中
            }
        }
        let t = perf::Timer::new("prefetch");
        let r = lines_for(&ename, false).unwrap_or_default();
        t.done_q(&format!("{} 条", r.len()));
        if let Ok(mut s) = inflight().lock() {
            s.remove(&ename);
        }
        r
    })
    .await
    .unwrap_or_default()
}

/// 前端打点：把 UI 侧的耗时（取源往返、首帧时间）写进同一条 perf 时间轴，
/// 这样才能分辨「慢在后端取源」还是「慢在播放器拉流」。
#[tauri::command]
fn perf_mark(msg: String) {
    perf::log(&format!("[ui] {msg}"));
}

/// 命令行测速：`ql-live.exe --bench=8`（配合 QL_PERF=1）。
///
/// 不开窗口，直接跑「取源 + 解析 + 探测」全链路并记录分阶段耗时，
/// 结果写 `%APPDATA%/ql-live/perf.log`。用来量化优化前后差异。
fn bench() {
    let n: usize = std::env::args()
        .find_map(|a| a.strip_prefix("--bench=").and_then(|s| s.parse().ok()))
        .unwrap_or(6)
        .max(1);
    perf::log(&format!("=== bench 开始，每组取 {n} 个频道 ==="));

    engine_host::prewarm();
    let t0 = Instant::now();
    while !engine_host::is_ready() && t0.elapsed() < Duration::from_secs(45) {
        std::thread::sleep(Duration::from_millis(100));
    }
    perf::log(&format!(
        "引擎就绪={}（等待 {}ms）",
        engine_host::is_ready(),
        t0.elapsed().as_millis()
    ));

    let provs = match fetch_provs() {
        Ok(p) => p,
        Err(e) => {
            perf::log(&format!("分组获取失败: {e}"));
            return;
        }
    };

    let mut tested = 0usize;
    let mut first_cold: Option<Vec<PlayLine>> = None;
    let mut first_ename = String::new();
    'outer: for p in &provs {
        let chs = match fetch_group(p) {
            Ok(c) => c,
            Err(_) => continue,
        };
        for c in chs.iter().take(n) {
            let t = perf::Timer::new("BENCH 切台(冷)");
            match lines_for(&c.ename, false) {
                Ok(v) => {
                    let ok = v.iter().filter(|l| l.playable).count();
                    t.done_q(&format!("{} | 可播 {}/{}", c.name, ok, v.len()));
                    if first_cold.is_none() {
                        first_cold = Some(v);
                        first_ename = c.ename.clone();
                    }
                }
                Err(e) => {
                    t.done_q(&format!("{} | 失败 {e}", c.name));
                }
            }
            tested += 1;
            if tested >= n * 2 {
                break 'outer;
            }
        }
    }

    // 缓存命中路径
    if first_cold.is_some() {
        let t = perf::Timer::new("BENCH 切台(缓存命中)");
        match lines_for(&first_ename, true) {
            Ok(v) => {
                t.done_q(&format!("{} 条", v.len()));
            }
            Err(e) => {
                t.done_q(&format!("失败 {e}"));
            }
        }
    }
    // 首屏链路：m3u8 → 首个分片。用来量化「拿到地址之后」到能出第一帧之前
    // 到底花在 302 跳转、清单还是分片下载上。
    if let Some(v) = &first_cold {
        for l in v.iter().filter(|l| l.playable).take(3) {
            if !l.url.starts_with("http") {
                continue;
            }
            perf::log(&format!(
                "首屏链路 [{}] {}",
                l.label,
                measure_first_segment(&l.url)
            ));
        }
    }

    perf::log(&format!("=== bench 结束，共 {} 个频道 ===", tested));
}

/// 模拟播放器起播：取 m3u8（跟随 302）→ 拼出首个分片 → 读前 64KB。
fn measure_first_segment(url: &str) -> String {
    use std::io::Read;
    let cli = match reqwest::blocking::Client::builder()
        .user_agent("AppleCoreMedia/1.0.0.l6F79 (iPhone; U; CPU OS 11_6 like Mac OS X; zh_cn)")
        .timeout(Duration::from_secs(12))
        .build()
    {
        Ok(c) => c,
        Err(e) => return format!("client err {e}"),
    };
    let t0 = Instant::now();
    let mut resp = match cli.get(url).send() {
        Ok(r) => r,
        Err(e) => return format!("m3u8 失败 {e}"),
    };
    let final_url = resp.url().to_string();
    let head_ms = t0.elapsed().as_millis();
    let mut body = String::new();
    if resp.read_to_string(&mut body).is_err() {
        return "m3u8 读取失败".into();
    }
    let list_ms = t0.elapsed().as_millis();
    let seg = body
        .lines()
        .map(|s| s.trim())
        .find(|s| !s.is_empty() && !s.starts_with('#'));
    let seg = match seg {
        Some(s) => s.to_string(),
        None => return format!("清单里没有分片 | {final_url}"),
    };
    let seg_url = if seg.starts_with("http") {
        seg
    } else {
        match reqwest::Url::parse(&final_url).and_then(|b| b.join(&seg)) {
            Ok(u) => u.to_string(),
            Err(_) => return format!("分片地址拼接失败 {seg}"),
        }
    };
    let t1 = Instant::now();
    let mut got = 0usize;
    if let Ok(mut r2) = cli.get(&seg_url).send() {
        let mut buf = vec![0u8; 65536];
        if let Ok(k) = r2.read(&mut buf) {
            got = k;
        }
    }
    format!(
        "清单首字节 {}ms / 读完 {}ms；首片前64KB {}ms（{got} 字节）| 最终 {final_url}",
        head_ms,
        list_ms,
        t1.elapsed().as_millis()
    )
}

fn main() {
    perf::init();
    perf::mark_main_thread();
    if std::env::args().any(|a| a.starts_with("--bench")) {
        bench();
        return;
    }
    // 一眼可辨的运行模式：prod 走内嵌资源，dev 会去连 devUrl（打包后必白屏）
    eprintln!(
        "[app] 运行模式={}",
        if tauri::is_dev() {
            "dev(连接 devUrl，打包版不应出现)"
        } else {
            "prod(内嵌前端资源)"
        }
    );
    tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
        .setup(|app| {
            // 诊断：QL_UI_PROBE=1 时开一个仅本机的接收口，让页面把运行状态用 fetch
            // 送出来。这条通道不依赖 Tauri 桥接，能区分「桥接没注入」和「页面没执行」。
            if std::env::var("QL_UI_PROBE").as_deref() == Ok("1") {
                eprintln!(
                    "[cfg] withGlobalTauri={:?} version={:?}",
                    app.config().app.with_global_tauri,
                    app.config().version
                );
                std::thread::spawn(|| {
                    use std::io::{Read, Write};
                    let l = match std::net::TcpListener::bind("127.0.0.1:18999") {
                        Ok(l) => l,
                        Err(e) => {
                            eprintln!("[diag] 端口占用 {e}");
                            return;
                        }
                    };
                    for s in l.incoming() {
                        let mut s = match s {
                            Ok(s) => s,
                            Err(_) => continue,
                        };
                        // 每个连接单独一个线程：浏览器可能先发一个预连接占着不放，
                        // 串行处理会把后面的打点全堵死（踩过一次）。
                        std::thread::spawn(move || {
                            let _ = s.set_read_timeout(Some(Duration::from_secs(3)));
                            let mut buf = [0u8; 8192];
                            let n = s.read(&mut buf).unwrap_or(0);
                            if n == 0 {
                                return; // 预连接/空请求，直接放掉
                            }
                            let req = String::from_utf8_lossy(&buf[..n]).to_string();
                            if let Some(target) = req.split_whitespace().nth(1) {
                                if let Some(q) = target.split("m=").nth(1) {
                                    let m = urlencoding::decode(q)
                                        .map(|c| c.into_owned())
                                        .unwrap_or_default();
                                    perf::log(&format!("[ui] {m}"));
                                }
                            }
                            let _ = s.write_all(
                                b"HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                            );
                        });
                    }
                });
            }
            // 诊断开关：QL_UI_PROBE=1 时把 DOM 概况写进窗口标题，便于从外部判断前端到底渲染了没有
            // （白屏排查用：能区分「页面没加载」和「GPU 合成画面抓不到」）
            if std::env::var("QL_UI_PROBE").as_deref() == Ok("1") {
                let wins = tauri::Manager::webview_windows(app);
                eprintln!("[probe] 窗口标签: {:?}", wins.keys().collect::<Vec<_>>());
                let w = wins
                    .get("main")
                    .cloned()
                    .or_else(|| wins.values().next().cloned());
                match w {
                    None => eprintln!("[probe] 没取到 webview 窗口"),
                    Some(w) => {
                        std::thread::spawn(move || {
                            for i in 1..=6u32 {
                                std::thread::sleep(Duration::from_secs(2));
                                let url = w
                                    .url()
                                    .map(|u| u.to_string())
                                    .unwrap_or_else(|e| format!("<err {e}>"));
                                // 把 DOM 概况经本机 fetch 送进 perf.log（比窗口标题可靠）
                                let mut js: String = "var q=function(s){try{fetch('http://127.0.0.1:18999/?m='+encodeURIComponent(s))}catch(e){}};"
                                    .to_string()
                                    + "var t='DOM"
                                    + &i.to_string()
                                    + "|kids='+document.body.children.length"
                                    + "+'|alls='+document.getElementsByTagName('*').length"
                                    + "+'|tk='+(typeof window.__TAURI__)"
                                    + "+'|cl='+(((document.getElementById('channelList')||{}).innerText)||'').slice(0,80).replace(/\\s+/g,' ')"
                                    + ";q(t);";
                                // 第一轮额外直接打一次命令，绕开页面脚本判断 IPC 本身通不通
                                if i == 1 {
                                    js.push_str("try{var T=window.__TAURI__;");
                                    js.push_str("if(!T||!T.core){q('probe 无 core')}else{");
                                    js.push_str("T.core.invoke('get_favs').then(function(r){q('probe get_favs ok n='+(r&&r.length))}).catch(function(e){q('probe get_favs ERR '+e)});");
                                    js.push_str("T.core.invoke('get_groups').then(function(r){q('probe get_groups ok n='+(r&&r.length))}).catch(function(e){q('probe get_groups ERR '+e)});");
                                    js.push_str("}}catch(e){q('probe 抛错 '+e)}");
                                }
                                let r = w.eval(&js);
                                eprintln!("[probe] {i} url={url} eval={:?}", r.err());
                            }
                        });
                    }
                }
            }
            // 下发算法 JS 的下载/解密/QuickJS 加载放到常驻线程里做，
            // 只做一次，之后所有频道解析复用同一个 Runtime。
            engine_host::prewarm();
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_groups,
            get_channels,
            get_lines,
            boot_target,
            get_epg,
            get_favs,
            toggle_fav,
            get_custom,
            add_custom,
            remove_custom,
            import_custom,
            prefetch_lines,
            perf_mark
        ])
        .build(tauri::generate_context!())
        .expect("error while running tauri application")
        .run(|_app, _event| {});
}
