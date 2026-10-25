//! 补齐安卓端已有、Windows 版缺失的功能：
//!   - EPG 节目单（对应安卓 EpgFetcher）
//!   - 收藏本台（对应安卓 toggleFav，prefs 存 ename 列表）
//!   - 自定义源（对应安卓 CustomSource，含远程 txt/m3u 导入）
//!
//! 持久化落在 data 目录（Tauri 后端无 localStorage），文件名为 ql-preferences.json。
//! 数据结构由前端通过 tauri command 读写，后端只负责存取 + 网络抓取。

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use tauri::Manager;

use crate::crypto::default_salt;

/// 一条 EPG 节目
#[derive(Serialize, Clone, Debug)]
pub struct EpgProgram {
    pub title: String,
    pub start: i64,
    pub end: i64,
    pub now: bool,
}

/// 一个自定义源频道（#EXTINF 标题 + url，或手动 name + url）
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CustomChannel {
    pub name: String,
    pub url: String,
}

fn data_file(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("定位数据目录失败: {e}"))?;
    fs::create_dir_all(&dir).map_err(|e| format!("创建数据目录失败: {e}"))?;
    Ok(dir.join("ql-preferences.json"))
}

/// 偏好结构（应用级 KV，简化版；仅存收藏 ename 列表与自定义源列表）
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
struct Prefs {
    #[serde(default)]
    favs: Vec<String>,
    #[serde(default)]
    custom: Vec<CustomChannel>,
}

fn load_prefs(app: &tauri::AppHandle) -> Prefs {
    let path = match data_file(app) {
        Ok(p) => p,
        Err(_) => return Prefs::default(),
    };
    match fs::read_to_string(&path) {
        Ok(s) => serde_json::from_str(&s).unwrap_or_default(),
        Err(_) => Prefs::default(),
    }
}

fn save_prefs(app: &tauri::AppHandle, prefs: &Prefs) -> Result<(), String> {
    let path = data_file(app)?;
    let s = serde_json::to_string_pretty(prefs).map_err(|e| e.to_string())?;
    fs::write(&path, s).map_err(|e| format!("写入偏好失败: {e}"))
}

// ---------------------------------------------------------------------------
// EPG
// ---------------------------------------------------------------------------

fn build_client() -> reqwest::blocking::Client {
    reqwest::blocking::Client::builder()
        .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .expect("build client")
}

/// 拉取某频道当日 EPG。等价安卓 EpgFetcher：
///   GET http://sjapi.91kds.cn/api/get_epgall_by_ename.php?npw=1&ename=<ename>&day=yyyy-MM-dd
#[tauri::command]
pub fn get_epg(ename: String) -> Result<Vec<EpgProgram>, String> {
    let day = {
        use chrono::prelude::*;
        Local::now().format("%Y-%m-%d").to_string()
    };
    let url = format!(
        "http://sjapi.91kds.cn/api/get_epgall_by_ename.php?npw=1&ename={}&day={}",
        urlencoding::encode(&ename),
        day
    );
    let client = build_client();
    let body = client.get(&url).send().map_err(|e| e.to_string())?;
    let text = body.text().map_err(|e| e.to_string())?;
    let arr: serde_json::Value = serde_json::from_str(&text).map_err(|e| e.to_string())?;

    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let day_start = now - (now % 86400);

    let mut out = Vec::new();
    if let serde_json::Value::Array(items) = arr {
        for it in items {
            let title = it.get("t").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let start = it.get("st").and_then(|v| v.as_i64()).unwrap_or(0);
            let end = it.get("et").and_then(|v| v.as_i64()).unwrap_or(0);
            if title.is_empty() || start <= 0 {
                continue;
            }
            if end < day_start || start > day_start + 172800 {
                continue;
            }
            let prog_now = now >= start && now < end;
            out.push(EpgProgram {
                title,
                start,
                end,
                now: prog_now,
            });
        }
    }
    out.sort_by(|a, b| a.start.cmp(&b.start));
    if out.is_empty() {
        Err("无节目单数据".into())
    } else {
        Ok(out)
    }
}

// ---------------------------------------------------------------------------
// 收藏
// ---------------------------------------------------------------------------

#[tauri::command]
pub fn get_favs(app: tauri::AppHandle) -> Result<Vec<String>, String> {
    Ok(load_prefs(&app).favs)
}

#[tauri::command]
pub fn toggle_fav(app: tauri::AppHandle, ename: String) -> Result<bool, String> {
    let mut prefs = load_prefs(&app);
    let is_fav;
    if let Some(pos) = prefs.favs.iter().position(|e| e == &ename) {
        prefs.favs.remove(pos);
        is_fav = false;
    } else {
        prefs.favs.push(ename);
        is_fav = true;
    }
    save_prefs(&app, &prefs)?;
    Ok(is_fav)
}

// ---------------------------------------------------------------------------
// 自定义源
// ---------------------------------------------------------------------------

#[tauri::command]
pub fn get_custom(app: tauri::AppHandle) -> Result<Vec<CustomChannel>, String> {
    Ok(load_prefs(&app).custom)
}

#[tauri::command]
pub fn add_custom(app: tauri::AppHandle, name: String, url: String) -> Result<Vec<CustomChannel>, String> {
    let mut prefs = load_prefs(&app);
    prefs.custom.push(CustomChannel {
        name: if name.trim().is_empty() { "自定义".to_string() } else { name },
        url,
    });
    save_prefs(&app, &prefs)?;
    Ok(prefs.custom)
}

#[tauri::command]
pub fn remove_custom(app: tauri::AppHandle, index: i64) -> Result<Vec<CustomChannel>, String> {
    let mut prefs = load_prefs(&app);
    if index == -2 {
        prefs.custom.clear();
    } else if index >= 0 && (index as usize) < prefs.custom.len() {
        prefs.custom.remove(index as usize);
    }
    save_prefs(&app, &prefs)?;
    Ok(prefs.custom)
}

/// 从远程 txt/m3u 拉取自定义源并覆盖存储。解析规则与安卓 CustomSource.fetch 一致：
///   - `#genre#xxx` 作为分组名
///   - `#EXTINF:-1,标题` 取标题（下一行非 # 行为 url）
///   - `名称,url` 单行格式
#[tauri::command]
pub fn import_custom(app: tauri::AppHandle, url: String) -> Result<Vec<CustomChannel>, String> {
    let client = build_client();
    let body = client.get(&url).send().map_err(|e| e.to_string())?;
    let text = body.text().map_err(|e| e.to_string())?;

    let mut list: Vec<CustomChannel> = Vec::new();
    let mut cur_group = String::new();
    let mut pending_title: Option<String> = None;

    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() {
            continue;
        }
        if line.contains("#genre#") {
            let a = line.find(',');
            let b = line.find('#');
            if let Some(a) = a {
                if a > 0 {
                    cur_group = line[..a].trim().to_string();
                }
            } else if let Some(b) = b {
                if b > 0 {
                    cur_group = line[..b].replace('#', "").trim().to_string();
                }
            }
            continue;
        }
        if line.starts_with("#EXTINF") {
            let c1 = line.find(',');
            let title = match c1 {
                Some(i) => line[i + 1..].trim().to_string(),
                None => String::new(),
            };
            let name = if cur_group.is_empty() {
                title
            } else {
                format!("{} {}", cur_group, title)
            };
            // 空标题兜底（#EXTINF:-1, 无名称），避免渲染成空白
            let name = if name.trim().is_empty() {
                format!("频道{}", list.len() + 1)
            } else {
                name
            };
            pending_title = Some(name);
            continue;
        }
        if line.starts_with('#') {
            continue;
        }
        let (name, url) = match pending_title.take() {
            Some(t) => (t, line.to_string()),
            None => {
                if let Some(comma) = line.find(',') {
                    let n = line[..comma].trim().to_string();
                    let u = line[comma + 1..].trim().to_string();
                    (n, u)
                } else {
                    (format!("频道{}", list.len() + 1), line.to_string())
                }
            }
        };
        if url.starts_with("http") || url.starts_with("rtmp") || url.starts_with("rtsp") {
            list.push(CustomChannel { name, url });
        }
    }

    if list.is_empty() {
        return Err("未解析到有效频道".into());
    }

    let mut prefs = load_prefs(&app);
    prefs.custom = list;
    save_prefs(&app, &prefs)?;
    Ok(prefs.custom)
}

// ---------------------------------------------------------------------------
// 辅助：default_salt 供前端展示 / 调试（可选）
// ---------------------------------------------------------------------------

#[tauri::command]
pub fn get_salt() -> String {
    default_salt()
}