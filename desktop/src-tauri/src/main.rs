#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod bridge;
mod crypto;
mod extra;
mod jsdec;
mod jsengine;
mod source;

use extra::{
    add_custom, get_custom, get_epg, get_favs, import_custom, remove_custom, toggle_fav,
};
use jsengine::{ensure_bundle, JsEngine, DEFAULT_JS_ZIP_URL};
use source::{
    fetch_group, fetch_provs, fetch_source, probe_playable, resolve_htmlplay, resolve_kdsvod_sign,
    resolve_playable, Channel, PlayLine,
};
use std::path::PathBuf;
use std::time::Duration;

/// 引擎 JS 的落地目录（与安装目录解耦，避免 Program Files 只读问题）。
fn engine_dir() -> PathBuf {
    if let Ok(appdata) = std::env::var("APPDATA") {
        return PathBuf::from(appdata).join("ql-live").join("js");
    }
    std::env::temp_dir().join("ql-live-js")
}

fn fetch_bytes(url: &str) -> Result<Vec<u8>, String> {
    let client = reqwest::blocking::Client::builder()
        .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
        .timeout(Duration::from_secs(25))
        .build()
        .map_err(|e| e.to_string())?;
    let resp = client.get(url).send().map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("下载 js.zip 失败: HTTP {}", resp.status()));
    }
    resp.bytes().map(|b| b.to_vec()).map_err(|e| e.to_string())
}

/// 构建内嵌引擎；任何一步失败都返回 None，由调用方退回硬编码解析。
fn build_engine() -> Option<JsEngine> {
    let dir = engine_dir();
    match ensure_bundle(&dir, false, DEFAULT_JS_ZIP_URL, fetch_bytes) {
        Ok(b) => eprintln!(
            "[engine] {} 引擎包（{} 个文件）-> {}",
            if b.downloaded { "已下载" } else { "复用" },
            b.files.len(),
            b.dir.display()
        ),
        Err(e) => {
            eprintln!("[engine] 引擎包不可用，回退硬编码解析: {e}");
            return None;
        }
    }
    match JsEngine::load(&dir, bridge::bridge()) {
        Ok(e) => {
            eprintln!(
                "[engine] 已加载下发算法 JS，RSA 参数={:?}",
                e.params
            );
            Some(e)
        }
        Err(e) => {
            eprintln!("[engine] 加载失败，回退硬编码解析: {e}");
            None
        }
    }
}

#[tauri::command]
fn get_groups() -> Result<Vec<String>, String> {
    fetch_provs()
}

#[tauri::command]
fn get_channels(group: String) -> Result<Vec<Channel>, String> {
    fetch_group(&group)
}

#[tauri::command]
fn get_lines(ename: String) -> Result<Vec<PlayLine>, String> {
    let lines = fetch_source(&ename)?;

    // 优先用服务商下发的算法 JS 执行解析（抗更新）；不可用时退回硬编码。
    let engine = build_engine();

    // 第一阶段：纯本地解析（sign/htmlplay 二次解析），得到候选线路。
    // htmlplay 需网络抓网页，仍串行（数量少，32 线里仅个位数）。
    let mut candidates: Vec<PlayLine> = Vec::new();
    let mut probe_indices: Vec<usize> = Vec::new(); // 需要做有效性探测的候选下标
    for l in lines {
        let raw = l.url.clone();
        let r = resolve_playable(&raw);
        if r.kind == "htmlplay" {
            match resolve_htmlplay(&raw) {
                Ok((final_url, referer)) => candidates.push(PlayLine {
                    url: final_url,
                    label: l.label,
                    playable: true,
                    kind: "htmlplay".to_string(),
                    referer,
                }),
                Err(_) => candidates.push(PlayLine {
                    url: r.url,
                    label: l.label,
                    playable: false,
                    kind: r.kind,
                    referer: String::new(),
                }),
            }
        } else if r.kind == "kdsvod" && !r.playable && raw.contains("pwd=jsdecode") {
            // 引擎优先：真正执行官方下发的 addSign/解析逻辑
            let resolved = engine
                .as_ref()
                .and_then(|e| match e.parse_line(&raw) {
                    Ok(u) => Some(u),
                    Err(err) => {
                        eprintln!("[engine] 解析失败（回退硬编码）: {err}");
                        None
                    }
                })
                .or_else(|| resolve_kdsvod_sign(&raw).ok());
            match resolved {
                Some(final_url) => {
                    let idx = candidates.len();
                    candidates.push(PlayLine {
                        url: final_url,
                        label: l.label,
                        playable: true, // 暂标 true，探测后修正
                        kind: "kdsvod".to_string(),
                        referer: String::new(),
                    });
                    probe_indices.push(idx);
                }
                None => candidates.push(PlayLine {
                    url: r.url,
                    label: l.label,
                    playable: false,
                    kind: r.kind,
                    referer: String::new(),
                }),
            }
        } else {
            candidates.push(PlayLine {
                url: r.url,
                label: l.label,
                playable: r.playable,
                kind: r.kind,
                referer: String::new(),
            });
        }
    }

    // 第二阶段：对 kdsvod 线路并行做有效性探测，剔除废模板（playUrl error! / 404 / 403）。
    // 串行探测会拖慢取源（单频道可到 6s+），并行 8 线程可压到 ~1s。
    if !probe_indices.is_empty() {
        let urls: Vec<String> = probe_indices
            .iter()
            .map(|&i| candidates[i].url.clone())
            .collect();
        let results: Vec<bool> = std::thread::scope(|scope| {
            let handles: Vec<_> = urls
                .into_iter()
                .map(|u| scope.spawn(move || probe_playable(&u)))
                .collect();
            handles.into_iter().map(|h| h.join().unwrap_or(false)).collect()
        });
        for (k, &idx) in probe_indices.iter().enumerate() {
            candidates[idx].playable = results.get(k).copied().unwrap_or(false);
        }
    }

    Ok(candidates)
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
        .invoke_handler(tauri::generate_handler![
            get_groups,
            get_channels,
            get_lines,
            get_epg,
            get_favs,
            toggle_fav,
            get_custom,
            add_custom,
            remove_custom,
            import_custom
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}