// Tauri v2：优先取全局 __TAURI__.core.invoke，兼容 withGlobalTauri=false 时手动注入
// Tauri v2：每次调用时现取，避免脚本先于桥接注入执行导致 invoke 永久不可用。
// __TAURI_INTERNALS__ 是 v2 的内部通道，不受 withGlobalTauri 开关影响，作为兜底。
function invoke(name, args) {
  const t = window.__TAURI__;
  if (t && t.core && typeof t.core.invoke === "function") return t.core.invoke(name, args);
  if (t && typeof t.invoke === "function") return t.invoke(name, args);
  const it = window.__TAURI_INTERNALS__;
  if (it && typeof it.invoke === "function") return it.invoke(name, args);
  return Promise.reject(new Error("Tauri 桥接未就绪"));
}

function bridgeReady() {
  const t = window.__TAURI__;
  return !!((t && t.core && t.core.invoke) || (t && t.invoke) ||
    (window.__TAURI_INTERNALS__ && window.__TAURI_INTERNALS__.invoke));
}

// 诊断回传：exe 没有控制台，两条通道同时发——
// 本地 HTTP（只在上层开探针时有人听）和 IPC 打点（写 perf.log）。
// 任一条通都能看到，方便判断是桥接的问题还是页面没执行。
function diag(msg) {
  const m = String(msg);
  try {
    fetch("http://127.0.0.1:18999/?m=" + encodeURIComponent(m)).catch(() => {});
  } catch (e) { /* 忽略 */ }
  try {
    const t = window.__TAURI__;
    if (t && t.core && typeof t.core.invoke === "function") {
      t.core.invoke("perf_mark", { msg: "[ui] " + m }).catch(() => {});
    }
  } catch (e) { /* 忽略 */ }
}

// 前端异常写进后端日志：这是唯一能看到前端报错的通道
{
  let errCount = 0;
  const report = (msg) => {
    if (errCount++ > 20) return;
    diag("JS错误 " + msg);
    invoke("perf_mark", { msg: "JS错误 " + msg }).catch(() => {});
  };
  window.addEventListener("error", (ev) => {
    report((ev.message || "?") + " @" + (ev.filename || "?") + ":" + (ev.lineno || 0));
  });
  window.addEventListener("unhandledrejection", (ev) => {
    const r = ev.reason;
    report("Promise拒绝 " + ((r && r.message) || String(r)));
  });
}

let groups = [];
let currentGroup = "";
let channels = [];
let currentChannel = null;   // { ename, name, nowplay }
let lines = [];              // [{ url, label, playable, kind }]
let currentLineIdx = 0;
let hls = null;

// 本地持久化（收藏源/设置保存在后端；解码模式/比例/自动换线存 localStorage）
let decodeMode = localStorage.getItem("decodeMode") || "hw";   // hw | sw | media
let aspectMode = Number(localStorage.getItem("aspectMode") || 0); // 0 fit 1 fill 2 16:9 3 4:3 4 stretch
let autoLine = localStorage.getItem("autoLine") !== "0";       // 默认开
let favs = [];                // 收藏的 ename 列表（后端为准）

const $ = (id) => document.getElementById(id);

// ===== 初始化 =====
// 带超时的 invoke：接口慢的时候给明确反馈，不要静默卡在加载中
function invokeT(name, args, ms) {
  return Promise.race([
    invoke(name, args),
    new Promise((_, rej) => setTimeout(() => rej(new Error(name + " 超时(" + ms + "ms)")), ms)),
  ]);
}

let bootedOnce = false;      // 只管「启动时自动播一次」，之后手动切分组不自动播

async function init() {
  diag("init 开始 tk=" + (typeof window.__TAURI__) + " ti=" +
    (typeof window.__TAURI_INTERNALS__) + " ready=" + bridgeReady());
  // 心跳：如果这条都不出现，说明 JS 线程被卡住了，不是调用慢
  setTimeout(() => diag("t+2s 心跳"), 2000);
  if (!bridgeReady()) {
    diag("init 中止：桥接不可用");
    showGroupError("运行时初始化失败：未检测到 Tauri 桥接（__TAURI__ 为空）");
    return;
  }
  try {
    $("channelList").innerHTML = `<div class="empty">正在加载节目源…</div>`;
    favs = (await invokeT("get_favs", {}, 5000).catch(() => [])) || [];
    diag("favs=" + favs.length);
    groups = (await invokeT("get_groups", {}, 15000).catch((e) => { diag("get_groups 失败 " + e); return []; })) || [];
    diag("groups=" + groups.length);
    renderGroups();
    if (groups.length === 0) {
      showGroupError("未获取到分组");
      return;
    }
    // 按分组顺序找第一个真正有频道的分组：服务器返回的第一个是「收藏」，
    // 它常年是空的，一进来停在这儿会让人以为根本没频道。
    let picked = -1;
    const limit = Math.min(groups.length, 6);
    for (let i = 0; i < limit; i++) {
      await selectGroup(groups[i]);
      if (channels.length) { picked = i; break; }
    }
    diag("picked=" + (picked >= 0 ? groups[picked] : "none") + " ch=" + channels.length);
    if (picked < 0) {
      showError("前 " + limit + " 个分组都没取到频道，请点右上角「更新节目源」重试");
      return;
    }
    if (!bootedOnce && channels.length) {
      bootedOnce = true;
      // 启动就播这个分组的第一个频道
      playChannel(channels[0]);
    }
    refreshToolbarState();
  } catch (e) {
    diag("init 异常 " + e);
    showGroupError("加载分组失败: " + e);
  }
}

function showGroupError(msg) {
  $("channelList").innerHTML = `<div class="empty">${escapeHtml(String(msg))}</div>`;
}
function showError(msg) {
  const list = $("channelList");
  list.innerHTML = `<div class="empty">${escapeHtml(String(msg))}</div>`;
}
function escapeHtml(s) {
  return s.replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}

// 更新播放区提示文字（保留占位图标）
function setVideoMsg(msg) {
  const box = $("videoMsg");
  const span = box.querySelector("span");
  if (span) span.textContent = msg;
  else box.textContent = msg;
}

// 轻提示 toast
let toastTimer = null;
function showToast(msg) {
  const t = $("toast");
  if (!t) return;
  t.textContent = msg;
  t.classList.add("show");
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove("show"), 2200);
}

// 播放加载态
let loadingSince = 0;
let loadingSlowTimer = null;
function showLoading(text) {
  const o = $("loadingOverlay");
  if (!o) return;
  $("loadingText").textContent = text || "加载线路中…";
  $("loadingText").classList.remove("slow");
  o.classList.remove("hidden");
  loadingSince = Date.now();
  if (loadingSlowTimer) clearTimeout(loadingSlowTimer);
  // 超过 12s 还没出画面就给个可行动提示，而不是一直无信息地转圈
  loadingSlowTimer = setTimeout(() => {
    const t = $("loadingText");
    if (t && loadingSince && Date.now() - loadingSince >= 12000) {
      t.textContent = (t.textContent || "") + "（较慢，可试着换条线路）";
      t.classList.add("slow");
    }
  }, 12000);
}
function hideLoading() {
  const o = $("loadingOverlay");
  if (o) o.classList.add("hidden");
  loadingSince = 0;
  if (loadingSlowTimer) { clearTimeout(loadingSlowTimer); loadingSlowTimer = null; }
  if (firstFrameTimer) { clearTimeout(firstFrameTimer); firstFrameTimer = null; }
}

// ===== 首帧监听 =====
// playing 只代表「有数据可播」，MSE 下画面可能还要几十~几百毫秒才真正绘制出来。
// 所以优先用 requestVideoFrameCallback 等真正渲染的一帧再撤蒙层，避免出现
// 「转圈动画都停了，屏幕上还是黑的」。
let switchT0 = 0;      // 本次切台的起点（performance.now）
let playerT0 = 0;      // 交给播放器的时刻
let frameToken = 0;    // 每次起播自增，作废旧回调
let frameDoneToken = -1;
let frameFallbackTimer = null;

// ---- 线路自学习：记住本频道上次出帧最快的线路 ----
// 这些源一个频道十几二十条线，实测快 0.9s、慢 5.5s。记住好的，下次直接用。
const FAST_LINE_MS = 3500;
const FAST_LINE_TTL = 7 * 86400000;   // 7 天，过期作废（源站会换线）

function fastLineKey(ename) { return "fastline_" + ename; }

function rememberFastLine(ms) {
  const ch = currentChannel;
  if (!ch || !ch.ename || !ms || ms > FAST_LINE_MS) return;
  const line = lines[currentLineIdx];
  if (!line || !line.url) return;
  try {
    localStorage.setItem(fastLineKey(ch.ename),
      JSON.stringify({ u: line.url, ms: Math.round(ms), t: Date.now() }));
  } catch (e) { /* 配额满等，忽略 */ }
}

/** 命中历史快线就把它提到第一位，返回原索引（-1 表示没命中） */
function promoteFastLine() {
  const ch = currentChannel;
  if (!ch || !ch.ename || lines.length < 2) return -1;
  let o = null;
  try { o = JSON.parse(localStorage.getItem(fastLineKey(ch.ename)) || "null"); } catch (e) { return -1; }
  if (!o || !o.u) return -1;
  if (Date.now() - (o.t || 0) > FAST_LINE_TTL) return -1;
  const i = lines.findIndex((l) => l.url === o.u && isPlayable(l));
  if (i <= 0) return -1;    // 已失效或本来就在首位
  const [it] = lines.splice(i, 1);
  lines.unshift(it);
  return i;
}

function markFirstFrame(tag) {
  if (frameDoneToken === frameToken) return;   // 一次起播只认第一帧
  frameDoneToken = frameToken;
  if (frameFallbackTimer) { clearTimeout(frameFallbackTimer); frameFallbackTimer = null; }
  if (firstFrameTimer) { clearTimeout(firstFrameTimer); firstFrameTimer = null; }
  hideLoading();
  const ms = switchT0 ? Math.round(performance.now() - switchT0) : 0;
  const pl = playerT0 ? Math.round(performance.now() - playerT0) : 0;
  rememberFastLine(ms);
  if (switchT0) {
    const ch = currentChannel ? currentChannel.name : "?";
    invoke("perf_mark", { msg: `首帧(${tag}) ${ch} 总${ms}ms（播放器 ${pl}ms）` }).catch(() => {});
    switchT0 = 0;
  }
}

function onPlayingEvent() {
  const v = $("video");
  const token = frameToken;
  if (typeof v.requestVideoFrameCallback === "function") {
    v.requestVideoFrameCallback(() => {
      if (token === frameToken) markFirstFrame("rVFC");
    });
    if (frameFallbackTimer) clearTimeout(frameFallbackTimer);
    // 兜底：个别源 rVFC 不回调，最多再等 1s 就放行
    frameFallbackTimer = setTimeout(() => {
      if (token === frameToken) markFirstFrame("playing");
    }, 1000);
  } else {
    markFirstFrame("playing");
  }
}

function bindFirstFrame() {
  frameToken++;
  if (frameFallbackTimer) { clearTimeout(frameFallbackTimer); frameFallbackTimer = null; }
  const v = $("video");
  v.removeEventListener("playing", onPlayingEvent);
  v.addEventListener("playing", onPlayingEvent);
}

// 首帧看门狗：实测正常首帧 ~2s，超过 5s 还没画面说明这条线路明显慢，
// 与其干等，不如换下一条（最多换两次，免得在几条慢线之间来回跳）。
let firstFrameTimer = null;
let firstFrameHops = 0;
const FIRST_FRAME_WAIT = 5000;

function armFirstFrameWatchdog() {
  if (firstFrameTimer) clearTimeout(firstFrameTimer);
  firstFrameTimer = setTimeout(() => {
    firstFrameTimer = null;
    if (!loadingSince) return;        // 已经出画面了
    if (firstFrameHops >= 2) return;  // 只折腾两次
    firstFrameHops++;
    onLineFail("首帧超过 " + FIRST_FRAME_WAIT / 1000 + " 秒，换条线路");
  }, FIRST_FRAME_WAIT);
}

// ===== 悬停预取 =====
// 鼠标停在频道卡片上就把它的线路先在后台取好，并且顺手把 m3u8 和「起播位置
// 那一片」提前下进 WebView 的 HTTP 缓存。这些源首片只有几 KB 却要 1s 以上，
// 完全是 RTT 决定的，代码层面压不下去 —— 只能靠提前取回来。
let prefetchTimer = null;
const prewarmed = new Set();

function prewarmUrl(url, depth) {
  if (!url || depth > 2) return;
  fetch(url, { cache: "default" })
    .then((r) => (r.ok ? r.text() : ""))
    .then((text) => {
      if (!text || text.trimStart().indexOf("#EXTM3U") !== 0) return;
      const rows = text.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
      const items = rows.filter((s) => !s.startsWith("#"));
      if (!items.length) return;
      // master 清单：再下一层才是真正的分片清单
      if (/\.m3u8(\?|$)/i.test(items[0])) {
        let sub;
        try { sub = new URL(items[0], url).toString(); } catch (e) { return; }
        prewarmUrl(sub, depth + 1);
        return;
      }
      // 取末尾两片（对应 hls.js liveSyncDurationCount:1 的起播位置）
      items.slice(-2).forEach((seg) => {
        let abs;
        try { abs = new URL(seg, url).toString(); } catch (e) { return; }
        // 必须把 body 读完才会进缓存
        fetch(abs, { cache: "default" }).then((r) => r.arrayBuffer()).catch(() => {});
      });
    })
    .catch(() => {});
}

function prewarmLine(line) {
  if (!line || !line.playable || !line.url) return;
  if (!(line.hls === true || /\.m3u8(\?|$)/i.test(line.url))) return;
  if (prewarmed.has(line.url)) return;
  prewarmed.add(line.url);
  if (prewarmed.size > 80) prewarmed.clear();
  prewarmUrl(line.url, 0);
}

function schedulePrefetch(ename) {
  if (prefetchTimer) clearTimeout(prefetchTimer);
  prefetchTimer = setTimeout(async () => {
    if (currentChannel && currentChannel.ename === ename) return;
    try {
      const ls = await invoke("prefetch_lines", { ename });
      const first = (ls || []).find((l) => isPlayable(l) && l.url);
      if (first) prewarmLine(first);
    } catch (e) { /* 预取失败无所谓，点下去时正常取 */ }
  }, 260);
}
function cancelPrefetch() {
  if (prefetchTimer) { clearTimeout(prefetchTimer); prefetchTimer = null; }
}

// ===== 分组（下拉选择） =====
function renderGroups() {
  const menu = $("gsMenu");
  menu.innerHTML = "";
  groups.forEach((g) => {
    const div = document.createElement("div");
    div.className = "gs-option" + (g === currentGroup ? " active" : "");
    const icon = g === "收藏" ? `<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" class="g-ico"><path d="M12 3l2.9 5.9 6.5.9-4.7 4.6 1.1 6.5L12 17.8 6.2 20.9l1.1-6.5L2.6 9.8l6.5-.9z"/></svg>` : "";
    div.innerHTML = icon + `<span>${escapeHtml(g)}</span>`;
    div.dataset.group = g;
    div.onclick = () => { selectGroup(g); closeGroupMenu(); };
    menu.appendChild(div);
  });
}

function toggleGroupMenu() {
  $("groupSelect").classList.toggle("open");
}
function closeGroupMenu() {
  $("groupSelect").classList.remove("open");
}
// 点击页面其它处关闭下拉
document.addEventListener("click", (e) => {
  if (!$("groupSelect").contains(e.target)) closeGroupMenu();
});
document.addEventListener("click", (e) => {
  const el = $("lineSelect");
  if (el && !el.contains(e.target)) closeLineMenu();
});

let groupSeq = 0;   // 分组切换序号：只认最后一次请求的结果，避免快速连点串台
async function selectGroup(g, preloaded) {
  currentGroup = g;
  renderGroups();
  $("groupTitle").textContent = g;
  // 切换分组时清空搜索框，避免上一组的搜索词把本组频道全部过滤掉（导致列表为空）
  $("searchInput").value = "";
  const seq = ++groupSeq;
  if (preloaded && preloaded.length) {
    channels = preloaded;
    renderChannels();
    return;
  }
  $("channelList").innerHTML = `<div class="empty">加载频道中…</div>`;
  const list = await invokeT("get_channels", { group: g }, 12000).catch(() => []);
  if (seq !== groupSeq) return;      // 用户已经切到别的分组了
  channels = list;
  if (channels.length === 0) {
    $("channelCount").textContent = "0 个频道";
    $("channelList").innerHTML =
      `<div class="empty">「${escapeHtml(g)}」分组暂无频道<br><span class="empty-hint">可在左上角切换到其它分组</span></div>`;
    return;
  }
  renderChannels();
}

// ===== 频道 =====
function renderChannels() {
  const list = $("channelList");
  const kw = $("searchInput").value.trim().toLowerCase();
  list.innerHTML = "";
  const filtered = kw ? channels.filter((c) => c.name.toLowerCase().includes(kw) || c.ename.toLowerCase().includes(kw)) : channels;
  $("channelCount").textContent = filtered.length + " 个频道";
  if (filtered.length === 0) {
    list.innerHTML = `<div class="empty">无匹配频道</div>`;
    return;
  }
  filtered.forEach((c, idx) => {
    const card = document.createElement("div");
    card.className = "channel-card" + (currentChannel && currentChannel.ename === c.ename ? " active" : "");
    card.innerHTML = `<div class="ch-head"><span class="ch-name">${escapeHtml(c.name)}</span><span class="ch-no">${String(idx + 1).padStart(2, "0")}</span></div>` +
      (c.nowplay ? `<div class="ch-now">${escapeHtml(c.nowplay)}</div>` : "");
    card.onclick = () => playChannel(c);
    card.addEventListener("mouseenter", () => schedulePrefetch(c.ename));
    card.addEventListener("mouseleave", cancelPrefetch);
    list.appendChild(card);
  });
}

// 换台时先停掉上一路的拉流，但保留 hls 实例和最后一帧（画面定格），
// 否则旧台还在后台抢带宽，新台首帧会被拖慢。
function holdCurrentFrame() {
  if (hls) { try { hls.stopLoad(); } catch (e) {} }
  try { $("video").pause(); } catch (e) {}
}

// ===== 播放 =====
let playSeq = 0;   // 连点不同频道时，只让最后一次的取源结果生效
async function playChannel(c) {
  const seq = ++playSeq;
  currentChannel = c;
  switchT0 = performance.now();
  playerT0 = 0;
  firstFrameHops = 0;                // 每个频道重新给两次换线机会
  renderChannels();
  cancelPrefetch();
  triedLines = new Set();            // 换频道重置换线记录
  stopWatchdog();
  destroyTs();                       // 换台先放掉上一路 P2P 流
  holdCurrentFrame();                // 停掉旧台拉流，画面定格，别占带宽
  showLoading("正在获取线路…");
  $("nowTitle").textContent = c.name + (c.nowplay ? " · " + c.nowplay : "");
  if ($("lineMenu")) $("lineMenu").innerHTML = "";
  lines = await invokeT("get_lines", { ename: c.ename }, 20000).catch((e) => { showError("取源失败: " + e); return []; });
  if (seq !== playSeq) return;       // 等待期间用户已经切到别的频道了
  if (switchT0) {
    invoke("perf_mark", { msg: `取源 ${c.name} ${Math.round(performance.now() - switchT0)}ms / ${lines.length} 条` }).catch(() => {});
  }
  if (lines.length === 0) {
    hideLoading();
    $("videoMsg").style.display = "block";
    setVideoMsg("该频道暂无可用线路");
    $("video").style.display = "none";
    return;
  }
  // 线路优先级排序：①可播优先 ②已解析(kdsvod/htmlplay)排最前 ③纯直连 http/https 次之 ④rtmp 再次 ⑤tvbus(P2P) ⑥其余垫底
  const rank = (l) => {
    if (l.kind === "tvbus") return 4;                             // P2P 需起流，放最后手段
    if (!l.playable) return 9;
    if (l.kind === "kdsvod" || l.kind === "htmlplay") return 0;   // 已解析、可用性最高
    if (l.kind === "http" || l.kind === "https") return 1;
    if (l.kind === "rtmp") return 2;
    return 3;
  };
  lines.sort((a, b) => rank(a) - rank(b));
  // 本频道上次出帧最快的线路提到第一位（切过一次之后，回来直接就快线）
  const movedFrom = promoteFastLine();
  renderLines();
  if (movedFrom > 0) {
    invoke("perf_mark", { msg: `快线记忆命中 ${c.name}：原第 ${movedFrom + 1} 条提到首位` }).catch(() => {});
  }
  const firstPlayable = lines.findIndex((l) => isPlayable(l));
  // 该频道所有线路均不可播（全废，如 tvbus 私有协议 / 废模板）→ 直接给出明确提示，不进入逐线尝试
  if (firstPlayable < 0) {
    hideLoading();
    $("video").style.display = "none";
    $("videoMsg").style.display = "block";
    setVideoMsg("该频道所有线路均暂不可播放，可能源站维护或需专用播放器，请稍后重试或换频道");
    return;
  }
  currentLineIdx = firstPlayable;
  playLine(currentLineIdx);
}

function renderLines() {
  const menu = $("lineMenu");
  if (!menu) return;
  menu.innerHTML = "";
  // 仅列出可播放线路：不可播线路（需二次解析）直接隐藏，不再显示删除线，避免干扰
  lines.forEach((l, i) => {
    if (!isPlayable(l)) return;
    const opt = document.createElement("button");
    opt.className = "line-option" + (i === currentLineIdx ? " active" : "");
    const tag = l.kind === "tvbus" ? " · P2P" : "";
    opt.innerHTML = `<span class="lo-idx">${i + 1}</span><span class="lo-label">${escapeHtml((l.label || ("线路" + (i + 1))) + tag)}</span>`;
    opt.title = l.url;
    opt.onclick = () => { currentLineIdx = i; renderLines(); closeLineMenu(); playLine(i); };
    menu.appendChild(opt);
  });
  const playableIdx = lines.map((l, i) => i).filter((i) => isPlayable(lines[i]));
  const pos = playableIdx.indexOf(currentLineIdx) + 1;
  const t = $("lineTriggerText");
  if (t) t.textContent = playableIdx.length ? ("线路 " + (pos || 1) + "/" + playableIdx.length) : "线路";
}
function toggleLineMenu() { const el = $("lineSelect"); if (el) el.classList.toggle("open"); }
function closeLineMenu() { const el = $("lineSelect"); if (el) el.classList.remove("open"); }

let bufferWatchdog = null;   // 缓冲超时看门狗（12s，与安卓端一致）
let triedLines = new Set();  // 本轮已尝试过的线路索引（避免死循环）
let tsPlayer = null;         // tvbus 的 MPEG-TS 播放器（mpegts.js）

/** tvbus:// 线路的 url 不是 http，playable=false，但桌面端能靠 P2P 引擎播 */
function isPlayable(l) {
  return !!(l && (l.playable || l.kind === "tvbus"));
}

function destroyTs() {
  if (tsPlayer) {
    try { tsPlayer.pause(); } catch (e) {}
    try { tsPlayer.unload(); } catch (e) {}
    try { tsPlayer.detachMediaElement(); } catch (e) {}
    try { tsPlayer.destroy(); } catch (e) {}
    tsPlayer = null;
  }
}

/** 播放 tvbus 引擎吐出的本地 TS 流 */
function playTs(url) {
  const video = $("video");
  destroyHls();
  destroyTs();
  if (!window.mpegts || !mpegts.getFeatureList().mseLivePlayback) {
    onLineFail("当前环境不支持 TS 直播播放（MSE 不可用）");
    return;
  }
  video.style.display = "block";
  $("videoMsg").style.display = "none";
  tsPlayer = mpegts.createPlayer(
    { type: "mpegts", isLive: true, url },
    { enableWorker: true, enableStashBuffer: false, liveBufferLatencyChasing: true, lazyLoad: false }
  );
  tsPlayer.attachMediaElement(video);
  tsPlayer.on(mpegts.Events.ERROR, (_t, d) => {
    stopWatchdog();
    onLineFail("P2P 流错误：" + (d || "unknown"));
  });
  tsPlayer.load();
  bindFirstFrame();
  tsPlayer.play().catch(() => {});
  video.addEventListener("waiting", onWaiting);
}

/** 起播封装：WebView 的自动播放策略会拦掉「没有用户手势」的 play
 *  （启动自动播、自动换线都属于这类），被拦时先静音播起来，画面先出来，
 *  用户点一下画面再恢复声音。 */
function tryPlayVideo(video) {
  if (!video) return;
  const p = video.play();
  if (!p || typeof p.catch !== "function") return;
  p.catch((e) => {
    const blocked = e && (e.name === "NotAllowedError" || /autoplay|gesture/i.test(e.message || ""));
    if (blocked && !video.muted) {
      video.muted = true;
      showToast("已静音播放（点击画面恢复声音）");
      const p2 = video.play();
      if (p2 && typeof p2.catch === "function") p2.catch(() => {});
    }
  });
}

async function playLine(i) {
  const line = lines[i];
  if (!line) return;
  if (!switchT0) switchT0 = performance.now();   // 手动切线路也算一次起播计时

  // tvbus：先让 P2P 引擎起流，再交给 mpegts.js
  if (line.kind === "tvbus") {
    $("videoMsg").style.display = "none";
    $("video").style.display = "block";
    showLoading("正在连接 P2P 线路…（首次启动需约 1 分钟）");
    showToast("正在解析：" + (line.label || ("线路" + (i + 1))));
    let local;
    try {
      local = await invoke("resolve_tvbus", { line: line.url });
    } catch (e) {
      onLineFail("P2P 线路解析失败：" + e);
      return;
    }
    if (currentLineIdx !== i) return; // 等待期间用户已切走
    playerT0 = performance.now();
    playTs(local);
    return;
  }

  if (!line.playable) {
    $("video").style.display = "none";
    $("videoMsg").style.display = "block";
    setVideoMsg("该线路需二次解析，暂不支持播放（" + (line.kind || "未知") + "）");
    return;
  }
  destroyTs();
  const url = line.url;
  $("videoMsg").style.display = "none";
  const video = $("video");
  video.style.display = "block";
  playerT0 = performance.now();
  showLoading("加载线路中…（" + (line.label || ("线路" + (i + 1))) + "）");
  showToast("正在播放：" + (line.label || ("线路" + (i + 1))));

  // 后端探测时已确认是否为 HLS 清单（有的源地址不带 .m3u8 后缀，靠后缀猜会走错分支，
  // 原生 video 拉 HLS 清单会黑等好几秒才报错，看起来就像「切台特别慢」）
  const isHls = line.hls === true || /\.m3u8(\?|$)/i.test(url);
  const canNativeHls = video.canPlayType("application/vnd.apple.mpegurl");

  // 线路可能携带所需 Referer（htmlplay 解析后），以及 kdsvod 需 AppleCoreMedia UA
  const lineReferer = line.referer || "http://sjapi.91kds.cn/";
  const isKdsvod = line.kind === "kdsvod";

  if (isHls && !canNativeHls && window.Hls && Hls.isSupported()) {
    destroyHls();
    // kdsvod 线路 302 到真实源站后需 AppleCoreMedia UA 才能拿正常分片（安卓端引擎附加头）
    const APPLE_UA = "AppleCoreMedia/1.0.0.l6F79 (iPhone; U; CPU OS 11_6 like Mac OS X; zh_cn)";
    hls = new Hls({
      enableWorker: false,
      // 直播起播点：hls.js 默认要落后 3 个分片才开播。这些源多为 10s/片，
      // 等于白白多等 20~30s。收到 1 片即出画。
      liveSyncDurationCount: 1,
      liveMaxLatencyDurationCount: 6,
      maxBufferLength: 24,
      backBufferLength: 10,
      // manifest 到手立刻拉首片，不等 level 决策
      startFragPrefetch: true,
      // 别为了估带宽先白拉一片；从最低档起播，出画后再由 ABR 升档
      testBandwidth: false,
      startLevel: 0,
      // 别死磕重试（默认 4 次指数退避会叠加十几秒），尽快让自动换线接管
      manifestLoadingMaxRetry: 2,
      manifestLoadingRetryDelay: 400,
      manifestLoadingTimeOut: 6000,
      levelLoadingMaxRetry: 2,
      levelLoadingTimeOut: 6000,
      fragLoadingMaxRetry: 2,
      fragLoadingTimeOut: 8000,
      // 初始带宽估计别太低，避免先播一档低码率再切
      abrEwmaDefaultEstimate: 3000000,
      xhrSetup: (xhr) => {
        xhr.setRequestHeader("User-Agent", isKdsvod ? APPLE_UA : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        xhr.setRequestHeader("Referer", lineReferer);
      },
    });
    hls.loadSource(url);
    hls.attachMedia(video);

    // MANIFEST 到手立刻起播（此时还在下分片），loading 等真正出帧再关
    hls.on(Hls.Events.MANIFEST_PARSED, () => { tryPlayVideo(video); });

    // 任何致命错误 / fragment 加载失败 → 触发换线
    hls.on(Hls.Events.ERROR, (_e, data) => {
      if (data.fatal || data.details === "fragLoadError" || data.details === "manifestLoadError") {
        stopWatchdog();
        onLineFail("线路错误：" + (data.type || data.details));
      }
    });

    // 持续缓冲 12s 判卡死 → 换线（对应安卓 bufferWatchdog）
    video.addEventListener("waiting", onWaiting);
    bindFirstFrame();
    armFirstFrameWatchdog();
  } else {
    destroyHls();
    video.removeEventListener("waiting", onWaiting);
    video.src = url;
    bindFirstFrame();
    tryPlayVideo(video);
    video.addEventListener("error", onVideoError);
    armFirstFrameWatchdog();
  }
}

function onWaiting() {
  if (!autoLine) return;
  if (bufferWatchdog) return;
  bufferWatchdog = setTimeout(() => onLineFail("持续缓冲，自动换线"), 12000);
}
function stopWatchdog() {
  if (bufferWatchdog) { clearTimeout(bufferWatchdog); bufferWatchdog = null; }
}
function onVideoError() {
  stopWatchdog();
  onLineFail("播放错误");
}

// ===== 自动换线 =====
function onLineFail(reason) {
  stopWatchdog();
  triedLines.add(currentLineIdx);
  if (!autoLine) { hideLoading(); showToast(reason + "（已关闭自动换线）"); showError(reason + "（已关闭自动换线）"); return; }
  const next = lines.findIndex((l, i) => i > currentLineIdx && isPlayable(l) && !triedLines.has(i));
  if (next >= 0) {
    currentLineIdx = next;
    renderLines();
    showToast("自动换线：" + (lines[next].label || ("线路" + (next + 1))));
    playLine(currentLineIdx);
  } else {
      // 所有线路均不可播：回退到第一条重试一轮，仍未果则提示
      hideLoading();
      $("video").style.display = "none";
      $("videoMsg").style.display = "block";
      setVideoMsg("所有线路均无法播放：" + reason + "。可能源站正在限流或线路维护，请稍后重试或切换频道。");
    }
}

function autoSwitchLine() { onLineFail("自动换线"); }

function destroyHls() {
  stopWatchdog();
  if (hls) { try { hls.destroy(); } catch (e) {} hls = null; }
}

// ===== EPG =====
$("epgBtn").addEventListener("click", async () => {
  if (!currentChannel) { showError("请先选择频道"); return; }
  openModal("epgModal");
  $("epgTitle").textContent = "节目单 · " + currentChannel.name;
  $("epgBody").innerHTML = `<div class="empty">加载节目单中…</div>`;
  try {
    const list = await invoke("get_epg", { ename: currentChannel.ename });
    renderEpg(list || []);
  } catch (e) {
    $("epgBody").innerHTML = `<div class="empty">节目单获取失败: ${escapeHtml(String(e))}</div>`;
  }
});

function renderEpg(list) {
  if (!list.length) {
    $("epgBody").innerHTML = `<div class="empty">暂无节目单数据</div>`;
    return;
  }
  const now = Math.floor(Date.now() / 1000);
  const html = list.map((p) => {
    const start = fmtTime(p.start);
    const end = fmtTime(p.end);
    const isNow = p.now || (now >= p.start && now < p.end);
    return `<div class="epg-item${isNow ? " now" : ""}">
      <span class="epg-time">${start} - ${end}</span>
      <span class="epg-title">${escapeHtml(p.title)}</span>
      ${isNow ? '<span class="epg-tag">正在播出</span>' : ""}
    </div>`;
  }).join("");
  $("epgBody").innerHTML = html;
  // 滚动到当前节目
  const nowEl = $("epgBody").querySelector(".epg-item.now");
  if (nowEl) nowEl.scrollIntoView({ block: "center" });
}
function fmtTime(sec) {
  const d = new Date(sec * 1000);
  const p = (n) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}`;
}

// ===== 收藏 =====
$("favBtn").addEventListener("click", async () => {
  if (!currentChannel) { showError("请先选择频道"); return; }
  try {
    const isFav = await invoke("toggle_fav", { ename: currentChannel.ename });
    favs = await invoke("get_favs").catch(() => favs);
    refreshToolbarState();
    showError(isFav ? "已收藏本台" : "已取消收藏");
  } catch (e) {
    showError("收藏操作失败: " + e);
  }
});
function isFav(ename) { return favs.includes(ename); }
function refreshToolbarState() {
  const favText = $("favBtnText");
  $("favBtn").classList.toggle("active", currentChannel ? isFav(currentChannel.ename) : false);
  if (favText) favText.textContent = (currentChannel && isFav(currentChannel.ename)) ? "已收藏" : "收藏";
  const decText = $("decodeBtnText");
  if (decText) decText.textContent = "解码·" + ({ hw: "硬", sw: "软", media: "系统" }[decodeMode] || "硬");
  const aspText = $("aspectBtnText");
  if (aspText) aspText.textContent = "比例·" + (["适配", "填充", "16:9", "4:3", "拉伸"][aspectMode] || "适配");
  $("autoLineBtn").classList.toggle("active", autoLine);
  const alText = $("autoLineBtnText");
  if (alText) alText.textContent = "换线·" + (autoLine ? "开" : "关");
}

// ===== 解码模式 / 比例 / 自动换线 =====
$("decodeBtn").addEventListener("click", () => {
  const order = ["hw", "sw", "media"];
  const i = order.indexOf(decodeMode);
  decodeMode = order[(i + 1) % order.length];
  localStorage.setItem("decodeMode", decodeMode);
  refreshToolbarState();
  // 重启当前线路以应用解码模式（hls.js 无软硬解之分，这里仅提示）
  if (currentChannel) { playLine(currentLineIdx); }
});
$("aspectBtn").addEventListener("click", () => {
  aspectMode = (aspectMode + 1) % 5;
  localStorage.setItem("aspectMode", String(aspectMode));
  applyAspect();
  refreshToolbarState();
});
function applyAspect() {
  const v = $("video");
  const wrap = $("videoWrap");
  switch (aspectMode) {
    case 1: v.style.objectFit = "cover"; break;      // 填充
    case 4: v.style.objectFit = "fill"; break;        // 拉伸
    default: v.style.objectFit = "contain"; break;    // 适配 / 16:9 / 4:3 用 contain 基础
  }
  // 16:9 / 4:3 通过调整容器留黑边（简化处理，contain 已在黑边内等比）
  void wrap;
}
$("autoLineBtn").addEventListener("click", () => {
  autoLine = !autoLine;
  localStorage.setItem("autoLine", autoLine ? "1" : "0");
  refreshToolbarState();
});

// ===== 更新源 =====
$("updateBtn").addEventListener("click", async () => {
  try {
    groups = (await invoke("get_groups")) || [];
    renderGroups();
    if (currentGroup) selectGroup(currentGroup);
    showError("节目源已更新");
  } catch (e) {
    showError("更新失败: " + e);
  }
});

// ===== 自定义源 =====
$("customBtn").addEventListener("click", () => { openModal("customModal"); renderCustomList(); });
$("customAddBtn").addEventListener("click", async () => {
  const name = $("customName").value.trim();
  const url = $("customUrl").value.trim();
  if (!url) { showError("请输入直播地址"); return; }
  try {
    await invoke("add_custom", { name, url });
    $("customName").value = ""; $("customUrl").value = "";
    renderCustomList();
  } catch (e) { showError("添加失败: " + e); }
});
$("customImportBtn").addEventListener("click", async () => {
  const url = $("customImportUrl").value.trim();
  if (!url) { showError("请输入远程列表地址"); return; }
  try {
    await invoke("import_custom", { url });
    $("customImportUrl").value = "";
    renderCustomList();
  } catch (e) { showError("导入失败: " + e); }
});
async function renderCustomList() {
  const listEl = $("customList");
  try {
    const list = (await invoke("get_custom")) || [];
    if (!list.length) {
      listEl.innerHTML = `<div class="empty">暂无自定义源</div>`;
      return;
    }
    listEl.innerHTML = list.map((c, i) => `
      <div class="custom-item">
        <span class="c-name">${escapeHtml(c.name)}</span>
        <span class="c-url">${escapeHtml(c.url)}</span>
        <button class="c-play" data-i="${i}" title="播放">
          <svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
        </button>
        <button class="c-del" data-i="${i}" title="删除">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6L6 18M6 6l12 12"/></svg>
        </button>
      </div>`).join("");
    listEl.querySelectorAll(".c-del").forEach((b) => b.onclick = async () => {
      await invoke("remove_custom", { index: Number(b.dataset.i) });
      renderCustomList();
    });
    listEl.querySelectorAll(".c-play").forEach((b) => b.onclick = async () => {
      const list = await invoke("get_custom");
      const item = list[Number(b.dataset.i)];
      playCustom(item);
    });
  } catch (e) {
    listEl.innerHTML = `<div class="empty">读取失败: ${escapeHtml(String(e))}</div>`;
  }
}
function playCustom(item) {
  closeModal("customModal");
  const c = { ename: "custom_" + item.url, name: item.name, nowplay: "" };
  currentChannel = c;
  $("nowTitle").textContent = item.name;
  lines = [{ url: item.url, label: "自定义", playable: true, kind: item.url.split("://")[0] }];
  renderLines();
  currentLineIdx = 0;
  playLine(0);
  refreshToolbarState();
}

// ===== 弹层通用 =====
function openModal(id) { $(id).classList.remove("hidden"); }
function closeModal(id) { $(id).classList.add("hidden"); }
document.querySelectorAll("[data-close]").forEach((el) => {
  el.addEventListener("click", () => closeModal(el.dataset.close));
});

// ===== 事件 =====
$("searchInput").addEventListener("input", renderChannels);
$("gsTrigger").addEventListener("click", (e) => {
  e.stopPropagation();
  toggleGroupMenu();
});
$("refreshBtn").addEventListener("click", () => {
  if (currentChannel) playChannel(currentChannel);
  else showError("请先选择频道");
});

// 线路下拉（底部向上展开）
$("lineTrigger").addEventListener("click", (e) => { e.stopPropagation(); toggleLineMenu(); });

// 自动播放被拦而静音时：点一下画面恢复声音
$("videoWrap").addEventListener("click", (e) => {
  if (e.target && e.target.closest && e.target.closest("button")) return;
  const v = $("video");
  if (v && v.muted) {
    v.muted = false;
    showToast("已恢复声音");
    tryPlayVideo(v);
  }
});

// 全屏
$("fullscreenBtn").addEventListener("click", async () => {
  const wrap = $("videoWrap");
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen();
    } else if (wrap.requestFullscreen) {
      await wrap.requestFullscreen();
    } else if (wrap.webkitRequestFullscreen) {
      wrap.webkitRequestFullscreen();
    } else {
      showToast("当前环境不支持全屏");
    }
  } catch (e) {
    showToast("全屏切换失败：" + e);
  }
});
document.addEventListener("fullscreenchange", () => {
  const b = $("fullscreenBtn");
  if (b) b.classList.toggle("active", !!document.fullscreenElement);
});

// 启动：等桥接真正就绪再 init（内嵌资源加载 + 注入偶尔会比脚本慢）
function bootWhenReady() {
  let tries = 0;
  const tick = () => {
    if (bridgeReady()) {
      init();
      return;
    }
    if (++tries > 300) {   // 30s 还没有桥接：给出明确提示，不要停在空白页
      diag("boot 超时：30s 未检测到桥接");
      showGroupError("运行时初始化失败：未检测到 Tauri 桥接（__TAURI__ 为空）");
      return;
    }
    setTimeout(tick, 100);
  };
  tick();
}
bootWhenReady();