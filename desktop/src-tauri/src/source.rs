use serde::Serialize;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::crypto::{add_sign, aes_cbc_decrypt, default_salt, kds_host, sign_key, HOST};

/// 一个频道
#[derive(Serialize, Clone, Debug)]
pub struct Channel {
    pub ename: String,
    pub name: String,
    pub nowplay: String,
}

/// 一条可播放线路
#[derive(Serialize, Clone, Debug)]
pub struct PlayLine {
    pub url: String,
    pub label: String,
    /// 是否可直接播放（http/https/rtmp 直连为 true；kdsvod/tvbus 等需二次解析为 false）
    pub playable: bool,
    /// 协议类型（原始前缀）
    pub kind: String,
    /// 请求该 url 时需附加的 Referer（htmlplay/部分线路需要），空串表示无需
    pub referer: String,
}

fn now_sec() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// 真实源站接受的 User-Agent（引擎附加头）。kdsvod 线路 302 到真实源站后，
/// 必须以此 UA 请求才能拿到正常分片；安卓端由引擎把 @@User-Agent=...@@ 解析成请求头，
/// Windows 端由前端 hls.js 的 xhrSetup 注入。
pub const APPLE_UA: &str =
    "AppleCoreMedia/1.0.0.l6F79 (iPhone; U; CPU OS 11_6 like Mac OS X; zh_cn)";

/// 构造带签名的公共参数串（不含开头 ? 或 &）
fn signed_params() -> String {
    let tm = now_sec();
    let key = sign_key(tm);
    format!(
        "app=kgq&netType=wifi&channel=umeng&ipv6Support=no&version=2.3.5&deviceId=qldesk&mac=00:91:91:91:55&userToken=&tmKey=&isHaveFile=no&imei=123456&tm={}&key={}&desc=qldesk",
        tm, key
    )
}

fn build_reqwest() -> reqwest::blocking::Client {
    reqwest::blocking::Client::builder()
        .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .expect("build client")
}

/// 分组列表（明文 JSON 数组）
pub fn fetch_provs() -> Result<Vec<String>, String> {
    let client = build_reqwest();
    let url = format!("{}/api/get_prov.php", HOST);
    let body = client.get(&url).send().map_err(|e| e.to_string())?;
    let text = body.text().map_err(|e| e.to_string())?;
    let arr: serde_json::Value = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    if let serde_json::Value::Array(items) = arr {
        for it in items {
            if let Some(s) = it.as_str() {
                let s = s.trim();
                if !s.is_empty() {
                    out.push(s.to_string());
                }
            }
        }
    }
    if out.is_empty() {
        Err("分组为空".into())
    } else {
        Ok(out)
    }
}

/// 频道列表（带签名，响应可能是明文 JSON 数组，或 AES 加密）
pub fn fetch_group(prov: &str) -> Result<Vec<Channel>, String> {
    let client = build_reqwest();
    let encoded = urlencoding::encode(prov);
    let url = format!("{}/api/get_channel.php?id={}&{}", HOST, encoded, signed_params());
    let body = client.get(&url).send().map_err(|e| e.to_string())?;
    let text = body.text().map_err(|e| e.to_string())?;
    let trimmed = text.trim();
    let plain = if trimmed.starts_with('[') {
        trimmed.to_string()
    } else {
        aes_cbc_decrypt(trimmed, &default_salt())?
    };
    let arr: serde_json::Value = serde_json::from_str(&plain).map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    if let serde_json::Value::Array(items) = arr {
        for it in items {
            let ename = it.get("ename").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let name = it.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let nowplay = it
                .get("nowplaycontent")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            if !ename.is_empty() && !name.is_empty() {
                out.push(Channel { ename, name, nowplay });
            }
        }
    }
    if out.is_empty() {
        Err("频道为空".into())
    } else {
        Ok(out)
    }
}

/// 频道取源：返回 kdsvod:// 等线路
pub fn fetch_source(ename: &str) -> Result<Vec<PlayLine>, String> {
    let client = build_reqwest();
    let url = format!(
        "{}/api/get_source2024.php?ename={}&{}",
        HOST,
        urlencoding::encode(ename),
        signed_params()
    );
    let body = client.get(&url).send().map_err(|e| e.to_string())?;
    let text = body.text().map_err(|e| e.to_string())?;
    let trimmed = text.trim();

    let plain = if looks_like_plain_json(trimmed) {
        trimmed.to_string()
    } else {
        aes_cbc_decrypt(trimmed, &default_salt())?
    };

    let v: serde_json::Value = serde_json::from_str(&plain).map_err(|e| e.to_string())?;

    let mut lines = Vec::new();
    let mut labels = Vec::new();

    // 实测接口字段名（get_source2024.php AES 解密后）：
    //   { "liveSource": [...], "liveSourceName": [...], "backSource": [...], "backSourceName": [...] }
    // 兼容旧字段名（liveSourceList 等）以防服务端变体。
    collect_pair(&v, "liveSource", "liveSourceName", &mut lines, &mut labels);
    collect_pair(&v, "backSource", "backSourceName", &mut lines, &mut labels);
    if lines.is_empty() {
        collect_pair(&v, "liveSourceList", "liveSourceNameList", &mut lines, &mut labels);
        collect_pair(&v, "backSourceList", "backSourceNameList", &mut lines, &mut labels);
    }

    if lines.is_empty() {
        return Err("无可用线路".into());
    }

    let mut out = Vec::new();
    for i in 0..lines.len() {
        let label = labels.get(i).cloned().unwrap_or_default();
        let label = if label.trim().is_empty() {
            format!("线路{}", i + 1)
        } else {
            label
        };
        // 预处理：剥离 @@ 附加头，记录原始协议类型
        let raw = lines[i].clone();
        let raw_kind = raw.split("://").next().unwrap_or("").to_string();
        out.push(PlayLine {
            url: raw,
            label,
            playable: false,
            kind: raw_kind,
            referer: String::new(),
        });
    }
    Ok(out)
}

fn looks_like_plain_json(s: &str) -> bool {
    let t = s.trim_start();
    t.starts_with('[') || t.starts_with('{')
}

fn collect_pair(
    v: &serde_json::Value,
    url_key: &str,
    name_key: &str,
    lines: &mut Vec<String>,
    labels: &mut Vec<String>,
) {
    if let Some(urls) = v.get(url_key).and_then(|x| x.as_array()) {
        let names = v.get(name_key).and_then(|x| x.as_array());
        for (i, u) in urls.iter().enumerate() {
            if let Some(s) = u.as_str() {
                let s = s.trim();
                if s.is_empty() {
                    continue;
                }
                lines.push(s.to_string());
                let lb = names
                    .and_then(|n| n.get(i))
                    .and_then(|x| x.as_str())
                    .unwrap_or("")
                    .trim()
                    .to_string();
                labels.push(lb);
            }
        }
    }
}

/// 线路解析结果
#[derive(Clone, Debug)]
pub struct ResolvedLine {
    pub url: String,
    pub playable: bool,
    pub kind: String,
}

/// 把取源得到的线路 URL 规整成可交给前端的地址，并标注是否可直接播放。
/// 实测线路协议类型：
///   - http://... / https://... / rtmp://...   → 直接可播（playable=true）
///   - kdsvod://http://...m3u8?id=xxx&pwd=jsdecode → 去前缀后是 m3u8，但 pwd=jsdecode 需 JS 二次解析出 sign=
///   - kdsvod://http://...&kdsipsign=xxx       → 也可能含二次签名，暂标不可播
///   - tvbus://... / p3p://... / migu://...     → 专用播放器（so 私有协议），不可播
///   - htmlplay://https://...                  → 网页播放，取内嵌 https 地址（可解析）
///   - 含 @@Referer=...@@ 附加头需剥离
pub fn resolve_playable(line: &str) -> ResolvedLine {
    let mut t = line.trim().to_string();

    // 记录原始协议类型
    let raw_kind = if let Some(i) = t.find("://") {
        t[..i].to_string()
    } else {
        t.clone()
    };

    // 剥离 kdsvod:// 前缀
    if let Some(rest) = t.strip_prefix("kdsvod://") {
        t = rest.to_string();
    }
    // 剥离 iptvdllbk:// 前缀（回看线路）
    if let Some(rest) = t.strip_prefix("iptvdllbk://") {
        t = rest.to_string();
    }
    // 处理 @@ 分隔的附加 header（Referer/User-Agent 等），只保留 URL 本体
    if let Some(idx) = t.find("@@") {
        t.truncate(idx);
    }
    let url = t.trim().to_string();

    // 判断可播放性：仅 http/https/rtmp 直连可播，其余需二次解析
    let playable = url.starts_with("http://") || url.starts_with("https://") || url.starts_with("rtmp://");
    // 但若原始是 kdsvod:// 且带 pwd=jsdecode / kdsipsign，说明需要 JS 二次解析，即使内嵌 http 也不能直接播
    let needs_js = raw_kind == "kdsvod" && (line.contains("pwd=jsdecode") || line.contains("kdsipsign="));
    let final_playable = playable && !needs_js;

    ResolvedLine {
        url,
        playable: final_playable,
        kind: raw_kind,
    }
}

/// 解析 htmlplay:// 线路：请求网页，抓取内嵌 `<source src="...">` 得到真实流地址。
/// 等价引擎 ahhftvLiveParsePlay（app.hfbtc.cn），需要 UA + 附带 Referer 头。
/// 返回 (流地址, referer)。
pub fn resolve_htmlplay(line: &str) -> Result<(String, String), String> {
    // htmlplay://https://app.hfbtc.cn/shows/2/6.html@@action=play@@
    let mut u = line.trim().to_string();
    if let Some(rest) = u.strip_prefix("htmlplay://") {
        u = rest.to_string();
    }
    if let Some(idx) = u.find("@@") {
        u.truncate(idx);
    }
    let u = u.trim().to_string();
    if !u.starts_with("http") {
        return Err("htmlplay 地址无效".into());
    }

    let client = build_reqwest();
    let resp = client
        .get(&u)
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .send()
        .map_err(|e| e.to_string())?;
    let html = resp.text().map_err(|e| e.to_string())?;

    // 抓 <source src="...">
    let tag = "<source";
    if let Some(pos) = html.find(tag) {
        let after = &html[pos + tag.len()..];
        if let Some(s) = after.find("src=\"") {
            let start = pos + tag.len() + s + 5;
            let rest = &html[start..];
            if let Some(end) = rest.find('"') {
                let mut play = rest[..end].trim().to_string();
                // 相对地址补全
                if play.starts_with('/') {
                    if let (Some(scheme), Some(host)) = (scheme_of(&u), host_of(&u)) {
                        play = format!("{}://{}{}", scheme, host, play);
                    }
                }
                if play.starts_with("http") {
                    return Ok((play, u));
                }
            }
        }
    }
    Err("htmlplay 未解析到流地址".into())
}

fn scheme_of(u: &str) -> Option<String> {
    u.split("://").next().map(|s| s.to_string())
}
fn host_of(u: &str) -> Option<String> {
    let a = u.find("://")? + 3;
    let rest = &u[a..];
    let e = rest.find(['/', ':', '?', '#']).unwrap_or(rest.len());
    Some(rest[..e].to_string())
}

/// 对 kdsvod://...pwd=jsdecode 线路做 sign 二次解析，产出可直接播放的最终 m3u8 地址。
/// 等价于引擎 other_live.js 的 kdsLiveParsePlay + addSign（已完整逆向、实测复现）：
///
///   1. 提取 ?id= 与 & 之间的 id
///   2. host = kds_host(app)（包名白名单 -> hz05/hz07/hz08），替换 v.91kds.com/b7|c7、utuxx.91kds.cn/live1|2
///   3. sign = add_sign(id, app, version, mac, nwtime, ev)（md5 拼接，见 crypto.rs）
///   4. 拼接 &app=&version=&mac=&nwtime=&sign=&ev=
///   5. 尾部追加 @@User-Agent=AppleCoreMedia/...@@ 附加头
///
/// app 默认取重构包名 com.muyue.ql；version 默认 2.3.9；ev 固定 "20250805"。
pub fn resolve_kdsvod_sign(line: &str) -> Result<String, String> {
    // 剥离 kdsvod:// 前缀
    let inner = line
        .trim()
        .strip_prefix("kdsvod://")
        .unwrap_or(line.trim());

    // 提取 id
    let id = if let Some(p) = inner.find("&id=") {
        let after = &inner[p + 4..];
        after.split('&').next().unwrap_or("").to_string()
    } else if let Some(p) = inner.find("?id=") {
        let after = &inner[p + 4..];
        after.split('&').next().unwrap_or("").to_string()
    } else {
        return Err("未找到 id 参数".into());
    };
    if id.is_empty() {
        return Err("id 为空".into());
    }

    let app = "com.muyue.ql";
    let version = "2.3.9";
    let mac = "l1:1l:l1:1l:l1:1l";
    let nwtime = now_sec();
    let ev = "20250805";

    // 去掉 pwd=jsdecode，做 host 替换
    let mut lurl = inner.replace("&pwd=jsdecode", "");
    let host = kds_host(app);
    lurl = lurl.replace("v.91kds.com/b7", &format!("{}/live1", host));
    lurl = lurl.replace("v.91kds.com/c7", &format!("{}/live2", host));
    lurl = lurl.replace("utuxx.91kds.cn/live1", &format!("{}/live1", host));
    lurl = lurl.replace("utuxx.91kds.cn/live2", &format!("{}/live2", host));

    let sign = add_sign(&id, app, version, mac, nwtime, ev);
    let play_url = format!(
        "{}&app={}&version={}&mac={}&nwtime={}&sign={}&ev={}",
        lurl, app, version, mac, nwtime, sign, ev
    );

    // 注意：不要像引擎那样把 @@User-Agent=...@@ 拼进 URL —— 那会导致 URL 含空格字面量而非法，
    // 是 Windows 端「有下载流量却黑屏」的根因。UE 由前端 hls.js 的 xhrSetup 注入 APPLE_UA。
    Ok(play_url)
}

/// 对 kdsvod 二次解析出的最终 m3u8 地址做轻量有效性探测。
///
/// 动机：kdsvod 线路里混有大量「废模板」（dsdq/kds/cctv2/gdtv/hbtv/hoge…），这些模板
/// 302 后返回 `playUrl error!` 或 404/403，但 sign 解析本身能成功，导致后端把它们标记成
/// playable=true，前端首播即黑屏——这正是「很多线路播放不了」的直接原因。
///
/// 判断标准：响应的有效载荷（非 BOM/空白）以 `#EXTM3U` 开头才视为可播；其余一律不可播。
/// 只下载响应体开头 1KB 以内即中断判断，超时 5s，避免拖慢 get_lines。
pub fn probe_playable(url: &str) -> bool {
    use std::io::Read;
    let client = match reqwest::blocking::Client::builder()
        .user_agent(APPLE_UA)
        .timeout(std::time::Duration::from_secs(5))
        .build()
    {
        Ok(c) => c,
        Err(_) => return true, // 无法建客户端时不做过滤，交由前端兜底
    };
    let mut resp = match client.get(url).send() {
        Ok(r) => r,
        Err(_) => return false, // 网络/404/403 等请求失败 → 不可播
    };
    // 404/403 等非 2xx 直接判不可播（reqwest send 成功但 status 非 2xx）
    if !resp.status().is_success() {
        return false;
    }
    // 读响应体开头，只取前 1KB，见到非 #EXTM3U 即判废
    let mut buf = [0u8; 64];
    let mut total: usize = 0;
    let mut saw_content = false;
    loop {
        match resp.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                for &b in &buf[..n] {
                    if !saw_content {
                        // 跳过 BOM/空白/CR/LF，定位首个有效字节
                        if b == 0xEF || b == 0xBB || b == 0xBF || b.is_ascii_whitespace() {
                            continue;
                        }
                        saw_content = true;
                    }
                    if saw_content {
                        total += 1;
                        if total > 1024 {
                            break;
                        }
                    }
                }
                // 便捷判断：把已读部分拼起来看前缀
                let head = String::from_utf8_lossy(&buf[..n]);
                let prefix = head.trim_start_matches(|c: char| c == '\u{feff}' || c.is_whitespace());
                if saw_content && !prefix.is_empty() {
                    // 一旦读到首个有效片段即可判断（只需知道是否 #EXTM3U 开头）
                    return prefix.starts_with("#EXTM3U");
                }
                if total > 1024 {
                    break;
                }
            }
            Err(_) => return false,
        }
    }
    // 空响应体 → 不可播
    false
}