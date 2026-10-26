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
/** 只改进度文案，不动 loadingSince（否则会把 12s 慢速提示的计时重置掉） */
function setLoadingProgress(txt) {
  const t = $("loadingText");
  if (t && loadingSince) t.textContent = txt;
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

// 首帧看门狗：**按「多久没收到数据」计时，不是按「总耗时」计时**。
//
// 这里踩过一个大坑：以前是「点了 5 秒还没画面就换线」。但实测这些源单个分片
// 3~4.7MB、TTFB 1.0~1.3s，慢一点的宽带上光下完一片就要 6~8 秒 —— 也就是说
// 线路完全正常，却被 5 秒看门狗判死，于是「才加载一点就换线」，换到下一台
// 还是同样的分片大小，还是被判死，连换两次才勉强播出来。原版 App 不做这种
// 提前放弃，所以第一条线路看着很顺。
//
// 现在改成：只有**连续 IDLE 秒没有任何一个字节到达**才认为这条线路死了。
// 只要还在下，就让它下完。
let firstFrameTimer = null;
let firstFrameHops = 0;
// 只要还有字节在进来，这个计时就会被 kickWatchdog 重置；
// 15s 完全没数据才判死（正常线路 TTFB 只有 1.1s，绝不会误伤）
const FIRST_FRAME_IDLE = 15000;

function armFirstFrameWatchdog() {
  if (firstFrameTimer) clearTimeout(firstFrameTimer);
  firstFrameTimer = setTimeout(() => {
    firstFrameTimer = null;
    if (!loadingSince) return;        // 已经出画面了
    if (firstFrameHops >= 1) return;  // 最多换一次，别在几条慢线之间来回跳
    firstFrameHops++;
    onLineFail("超过 " + FIRST_FRAME_IDLE / 1000 + " 秒没有任何数据，换条线路");
  }, FIRST_FRAME_IDLE);
}
/** 收到任何数据就重新计时：还在下就说明线路活着，不许判死 */
function kickWatchdog() {
  // 起播阶段：首帧看门狗按「无数据时长」续命
  if (loadingSince && firstFrameTimer) armFirstFrameWatchdog();
  // 播放阶段：缓冲看门狗也按「无数据时长」续命（有字节进来就不算卡死）
  if (bufferWatchdog) {
    clearTimeout(bufferWatchdog);
    bufferWatchdog = setTimeout(() => onLineFail("持续缓冲，自动换线"), BUFFER_STALL_MS);
  }
}
const BUFFER_STALL_MS = 12000;

// ===== 首片预取 =====
// 实测这些源每片 3~5MB、目标时长 10s（约 3~4Mbps），而 HLS 首帧必须**下完一整片**
// 才能 append 到 MSE —— 首帧时间几乎全部花在这一片上（本机实测 3.4~4.5s）。
// 分片响应是 no-store 且每次刷新清单签名都会变，HTTP 缓存完全救不了，
// 所以这里把字节存在 JS 内存里，用自定义 loader 直接喂给 hls.js：
//   - 命中（已下完）→ 0 延迟起播
//   - 在途（还在下）→ 等它，不重复下载
//   - 未命中/失败 → 原样回退默认 XHR 加载器，行为与优化前完全一致

const segCache = new Map();      // pathname -> { p: Promise<ArrayBuffer>, at, ms }
const SEG_CACHE_MAX = 2;
let segInflight = 0;             // 同时只允许一个分片预取在跑

function segKey(url) {
  try { return new URL(url, location.href).pathname; }
  catch (e) { return String(url || "").split("?")[0]; }
}

function prefetchSegment(segUrl) {
  if (!segUrl) return;
  const k = segKey(segUrl);
  if (!k || segCache.has(k)) return;
  // 一条分片 3~5MB：鼠标扫过多个卡片时若并行预取，会把当前播放的带宽吃掉，
  // 反而更卡。所以只留一路，其余的放弃（点下去时正常下载）。
  if (segInflight > 0) return;
  segInflight++;
  const t0 = performance.now();
  const rec = { at: Date.now(), ms: 0 };
  rec.p = fetch(segUrl, { cache: "no-store" })
    .then((r) => {
      if (!r.ok) throw new Error("http " + r.status);
      return r.arrayBuffer();
    })
    .then((buf) => {
      // 字节级校验：确认拿到的是媒体数据而不是错误页 / 空响应，
      // 否则绝不能喂给 MSE（会导致 append 失败、黑屏换线）。
      const u8 = new Uint8Array(buf);
      const isTs = u8.length > 188 && u8[0] === 0x47;
      const isMp4 = u8.length > 12 && u8[4] === 0x66 && u8[5] === 0x74 &&
        u8[6] === 0x79 && u8[7] === 0x70;   // "ftyp"
      if (!isTs && !isMp4) throw new Error("not media");
      rec.ms = Math.max(1, Math.round(performance.now() - t0));
      return buf;
    })
    .catch((e) => {
      if (segCache.get(k) === rec) segCache.delete(k);
      throw e;
    })
    .finally(() => { segInflight = Math.max(0, segInflight - 1); });
  rec.p.catch(() => {});          // 让未处理的拒绝安静掉
  segCache.set(k, rec);
  if (segCache.size > SEG_CACHE_MAX) {
    const oldest = [...segCache.entries()].sort((a, b) => a[1].at - b[1].at)[0];
    if (oldest) segCache.delete(oldest[0]);
  }
}

function segStats(ms, bytes, t0) {
  return {
    aborted: false, loaded: bytes, retry: 0, total: bytes,
    chunkCount: 1, bwEstimate: bytes * 8 / Math.max(0.05, ms / 1000),
    parsing: { start: t0, end: t0 }, buffering: { start: 0, end: 0 },
    trequest: t0, tfirst: t0, tload: t0 + ms,
  };
}

/// 带首片缓存的 hls.js 加载器（未命中一律走原生实现）
let SegLoaderClass = null;
function segLoaderClass() {
  if (SegLoaderClass) return SegLoaderClass;
  const Base = (window.Hls && Hls.DefaultConfig && Hls.DefaultConfig.loader) || null;
  if (!Base) return null;
  SegLoaderClass = class extends Base {
    load(context, config, callbacks) {
      if (context && context.type === "frag" && context.url) {
        const k = segKey(context.url);
        const rec = k ? segCache.get(k) : null;
        if (rec) {
          segCache.delete(k);       // 一次性：用过就丢，避免内存堆积
          const t0 = performance.now();
          let done = false;
          const fallback = () => {
            if (done) return;
            done = true;
            super.load(context, config, callbacks);
          };
          // 在途的话最多再等 12s，超时回退（别把起播押在一个可能卡住的预取上）
          const guard = setTimeout(fallback, 12000);
          rec.p.then((buf) => {
            if (done) return;
            done = true;
            clearTimeout(guard);
            try {
              callbacks.onSuccess(
                { url: context.url, data: buf },
                segStats(rec.ms || (performance.now() - t0), buf.byteLength, t0),
                context, null
              );
            } catch (e) {
              // onSuccess 内部抛错（极少见）→ 交给默认加载器重来
              done = false;
              fallback();
            }
          }).catch(() => { clearTimeout(guard); fallback(); });
          return;
        }
      }
      super.load(context, config, callbacks);
    }
    abort() {
      try { super.abort(); } catch (e) { /* 命中缓存时没有 xhr，忽略 */ }
    }
  };
  return SegLoaderClass;
}

// ===== 悬停预取 =====
// 鼠标停在频道卡片上就把它的线路先在后台取好，顺手把首片字节拉进内存。
// 这些源首片 3~5MB 且是 RTT+带宽双限，代码层面压不下去 —— 只能提前取。
let prefetchTimer = null;
const prewarmed = new Set();

/// 只取清单：主要是建连（DNS/TCP），顺带拿到最新分片地址交给首片预取。
/// 以前这里会把末两片整段下载下来，但分片 URL 带时效签名、响应还是 no-store，
/// 到播放时 URL 早就变了，缓存永远命中不了 —— 等于白占 8MB 带宽，还会跟
/// 真正的起播抢链路。现在只下清单（几百字节），分片交给 prefetchSegment 存内存。
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
      // 起播用的是最新那片（liveSyncDurationCount:1），预取它就够了
      let seg;
      try { seg = new URL(items[items.length - 1], url).toString(); } catch (e) { return; }
      prefetchSegment(seg);
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
  // 重复点同一个频道：正在播就别再重建播放器（重建一次白等好几秒）
  if (currentChannel && c && currentChannel.ename === c.ename &&
      hls && lines.length && $("video") && $("video").style.display !== "none" &&
      !loadingSince) {
    return;
  }
  const seq = ++playSeq;
  currentChannel = c;
  switchT0 = performance.now();
  playerT0 = 0;
  firstFrameHops = 0;                // 每个频道重新给两次换线机会
  renderChannels();
  cancelPrefetch();
  triedLines = new Set();            // 换频道重置换线记录
  stopWatchdog();
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
  // 线路优先级排序：①可播优先 ②已解析(kdsvod/htmlplay)排最前 ③纯直连 http/https 次之 ④rtmp 再次 ⑤其余垫底
  const rank = (l) => {
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
  // 该频道所有线路均不可播（源站维护 / 私有协议 / 废模板）→ 直接给出明确提示，不进入逐线尝试
  if (firstPlayable < 0) {
    hideLoading();
    $("video").style.display = "none";
    $("videoMsg").style.display = "block";
    setVideoMsg("该频道所有线路均暂不可播放，可能源站维护或需专用播放器，请稍后重试或换频道");
    return;
  }
  currentLineIdx = firstPlayable;
  // 注意：这里不要「点击时再预取首片」——hls.js 自己会立刻拉同一个清单和分片，
  // 再预取一份只是多占一条连接和带宽，反而拖慢真正的起播。
  // 首片预取只在鼠标悬停时做（那时用户还没点，带宽闲着）。
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
    opt.innerHTML = `<span class="lo-idx">${i + 1}</span><span class="lo-label">${escapeHtml(l.label || ("线路" + (i + 1)))}</span>`;
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

/** 只有后端解析出可直接拉流的地址（http/https/rtmp）才算可播 */
function isPlayable(l) {
  return !!(l && l.playable && l.url);
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

  if (!line.playable) {
    $("video").style.display = "none";
    $("videoMsg").style.display = "block";
    setVideoMsg("该线路需二次解析，暂不支持播放（" + (line.kind || "未知") + "）");
    return;
  }
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
    const hlsCfg = {
      // 解复用放到 Worker 里：单片 3~4.7MB，放主线程每 10 秒会卡一下，
      // 表现为「播放中轻微顿一下 / 界面响应慢」。Worker 起不来时 hls.js
      // 会自动回退主线程解析，不会播不了。
      enableWorker: true,
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
      // 超时/重试按「真实源站」来定，不是按「理想网络」来定：
      // 实测单片 3~4.7MB、TTFB 1.0~1.3s，源站偶尔还会 502（上游超时要等满 10s
      // 才返回）。以前 frag 超时 8s、只重试 2 次，等于把「慢但能播」的线路直接
      // 枪毙 → 自动换线 → 下一台同样慢 → 再换。这就是「才加载就换线」的由来。
      // 清单只有几百字节，10s 拿不到基本就是线路死了。
      manifestLoadingMaxRetry: 3,
      manifestLoadingRetryDelay: 800,
      manifestLoadingTimeOut: 10000,
      levelLoadingMaxRetry: 3,
      levelLoadingRetryDelay: 800,
      levelLoadingTimeOut: 15000,
      // 分片 3~4.7MB，慢宽带下光下载就要 6~8s，给足 25s；真正卡死由
      // 「无数据 15s」的首帧看门狗管，不靠这个超时
      fragLoadingMaxRetry: 3,
      fragLoadingRetryDelay: 800,
      fragLoadingTimeOut: 25000,
      // 初始带宽估计别太低，避免先播一档低码率再切
      abrEwmaDefaultEstimate: 3000000,
      xhrSetup: (xhr) => {
        xhr.setRequestHeader("User-Agent", isKdsvod ? APPLE_UA : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        xhr.setRequestHeader("Referer", lineReferer);
        // 每收到一块数据就踢一下看门狗：只要还在下，就不许判这条线路死。
        // 顺带把「正在下第几 MB」显示出来 —— 单片 3~4MB 要下好几秒，
        // 光转圈会让人以为卡死了。
        xhr.addEventListener("progress", (e) => {
          kickWatchdog();
          if (!loadingSince || !e || !e.lengthComputable || !e.total || e.total < 204800) return;
          const mb = (n) => (n / 1048576).toFixed(1);
          const pct = Math.min(99, Math.round((e.loaded / e.total) * 100));
          setLoadingProgress(`正在缓冲 ${mb(e.loaded)}MB / ${mb(e.total)}MB（${pct}%）`);
        });
      },
    };
    // 首片预取命中时直接把内存字节喂给 hls.js，省掉整段 3~5MB 下载；
    // 关掉（localStorage segPreload=0）或环境不支持时保持原生加载器。
    if (localStorage.getItem("segPreload") !== "0") {
      const LC = segLoaderClass();
      if (LC) hlsCfg.loader = LC;
    }
    hls = new Hls(hlsCfg);
    hls.loadSource(url);
    hls.attachMedia(video);

    // 播放器侧分阶段打点：只有拿到这几行，才能分辨「慢在后端取源」还是
    // 「慢在 CDN 拉清单/首片」——首帧总时长里这两块各占一半是常态。
    hls.on(Hls.Events.MANIFEST_LOADED, (_e, d) => {
      const nd = d && d.networkDetails;
      const first = nd && nd.loading ? nd.loading.first : 0;
      const end = nd && nd.loading ? nd.loading.end : 0;
      diag(`hls清单 ${first ? Math.round(first) + "ms/" : ""}${end ? Math.round(end) + "ms" : "?"}`);
    });
    hls.on(Hls.Events.FRAG_LOADED, (_e, d) => {
      if (!loadingSince) return;   // 只记起播时那一片，否则每 10s 都写一行日志
      const nd = d && d.networkDetails;
      const first = nd && nd.loading ? nd.loading.first : 0;
      const end = nd && nd.loading ? nd.loading.end : 0;
      if (first) diag(`hls首片 首字节${Math.round(first)}ms 下载完${Math.round(end)}ms`);
    });

    // MANIFEST 到手立刻起播（此时还在下分片），loading 等真正出帧再关
    hls.on(Hls.Events.MANIFEST_PARSED, () => { tryPlayVideo(video); });

    // 只有 hls.js 判定「致命」才换线。
    // 坑：fragLoadError / manifestLoadError 在**每次重试**时都会抛一次且 fatal=false，
    // 以前把它们也当致命，等于把重试配置全废掉 —— 源站抖一下（比如偶尔 502，
    // 要等满 10s 才返回）就直接换台，看起来就是「才加载就换线」。
    hls.on(Hls.Events.ERROR, (_e, data) => {
      if (!data || !data.fatal) return;   // 非致命：hls.js 自己在重试，别抢它的活
      stopWatchdog();
      onLineFail("线路错误：" + (data.type || data.details));
    });

    // 持续缓冲 12s 判卡死 → 换线（对应安卓 bufferWatchdog）
    video.addEventListener("waiting", onWaiting);
    bindFirstFrame();
    armFirstFrameWatchdog();
  } else {
    destroyHls();
    video.removeEventListener("waiting", onWaiting);
    video.src = url;
    video.addEventListener("progress", kickWatchdog);
    bindFirstFrame();
    tryPlayVideo(video);
    video.addEventListener("error", onVideoError);
    armFirstFrameWatchdog();
  }
}

function onWaiting() {
  if (!autoLine) return;
  if (bufferWatchdog) return;
  bufferWatchdog = setTimeout(() => onLineFail("持续缓冲，自动换线"), BUFFER_STALL_MS);
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
  // 换线原因写进 perf.log：以后判断「是不是又误判慢线」不用猜
  diag(`换线触发：${reason}（当前第 ${currentLineIdx + 1} 条，已用 ${Math.round(switchT0 ? performance.now() - switchT0 : 0)}ms）`);
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