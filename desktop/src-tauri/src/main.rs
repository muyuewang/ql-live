#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod crypto;
mod extra;
mod source;

use extra::{
    add_custom, get_custom, get_epg, get_favs, import_custom, remove_custom, toggle_fav,
};
use source::{
    fetch_group, fetch_provs, fetch_source, probe_playable, resolve_htmlplay, resolve_kdsvod_sign,
    resolve_playable, Channel, PlayLine,
};

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
            match resolve_kdsvod_sign(&raw) {
                Ok(final_url) => {
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
                Err(_) => candidates.push(PlayLine {
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