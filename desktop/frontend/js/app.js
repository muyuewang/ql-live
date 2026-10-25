// Tauri v2：优先取全局 __TAURI__.core.invoke，兼容 withGlobalTauri=false 时手动注入
const invoke = window.__TAURI__?.core?.invoke || window.__TAURI__?.invoke;

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
async function init() {
  if (!invoke) {
    showGroupError("运行时初始化失败：未检测到 Tauri 桥接（__TAURI__ 为空）");
    return;
  }
  try {
    const [provs, f] = await Promise.all([
      invoke("get_groups"),
      invoke("get_favs").catch(() => []),
    ]);
    groups = provs || [];
    favs = f || [];
    renderGroups();
    if (groups.length > 0) {
      selectGroup(groups[0]);
    } else {
      showGroupError("未获取到分组");
    }
    refreshToolbarState();
  } catch (e) {
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
function showLoading(text) {
  const o = $("loadingOverlay");
  if (!o) return;
  $("loadingText").textContent = text || "加载线路中…";
  o.classList.remove("hidden");
}
function hideLoading() {
  const o = $("loadingOverlay");
  if (o) o.classList.add("hidden");
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

async function selectGroup(g) {
  currentGroup = g;
  renderGroups();
  $("groupTitle").textContent = g;
  // 切换分组时清空搜索框，避免上一组的搜索词把本组频道全部过滤掉（导致列表为空）
  $("searchInput").value = "";
  $("channelList").innerHTML = `<div class="empty">加载频道中…</div>`;
  channels = await invoke("get_channels", { group: g }).catch((e) => { showError("加载频道失败: " + e); return []; });
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
    list.appendChild(card);
  });
}

// ===== 播放 =====
async function playChannel(c) {
  currentChannel = c;
  renderChannels();
  triedLines = new Set();            // 换频道重置换线记录
  stopWatchdog();
  $("nowTitle").textContent = c.name + (c.nowplay ? " · " + c.nowplay : "");
  if ($("lineMenu")) $("lineMenu").innerHTML = "";
  lines = await invoke("get_lines", { ename: c.ename }).catch((e) => { showError("取源失败: " + e); return []; });
  if (lines.length === 0) {
    $("videoMsg").style.display = "block";
    setVideoMsg("该频道暂无可用线路");
    $("video").style.display = "none";
    return;
  }
  // 线路优先级排序：①可播优先 ②已解析(kdsvod/htmlplay)排最前 ③纯直连 http/https 次之 ④rtmp 再次 ⑤其余(不可播)垫底
  const rank = (l) => {
    if (!l.playable) return 9;
    if (l.kind === "kdsvod" || l.kind === "htmlplay") return 0;   // 已解析、可用性最高
    if (l.kind === "http" || l.kind === "https") return 1;
    if (l.kind === "rtmp") return 2;
    return 3;
  };
  lines.sort((a, b) => rank(a) - rank(b));
  renderLines();
  const firstPlayable = lines.findIndex((l) => l.playable);
  // 该频道所有线路均不可播（全废，如 tvbus 私有协议 / 废模板）→ 直接给出明确提示，不进入逐线尝试
  if (firstPlayable < 0) {
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
    if (!l.playable) return;
    const opt = document.createElement("button");
    opt.className = "line-option" + (i === currentLineIdx ? " active" : "");
    opt.innerHTML = `<span class="lo-idx">${i + 1}</span><span class="lo-label">${escapeHtml(l.label || ("线路" + (i + 1)))}</span>`;
    opt.title = l.url;
    opt.onclick = () => { currentLineIdx = i; renderLines(); closeLineMenu(); playLine(i); };
    menu.appendChild(opt);
  });
  const playableIdx = lines.map((l, i) => i).filter((i) => lines[i].playable);
  const pos = playableIdx.indexOf(currentLineIdx) + 1;
  const t = $("lineTriggerText");
  if (t) t.textContent = playableIdx.length ? ("线路 " + (pos || 1) + "/" + playableIdx.length) : "线路";
}
function toggleLineMenu() { const el = $("lineSelect"); if (el) el.classList.toggle("open"); }
function closeLineMenu() { const el = $("lineSelect"); if (el) el.classList.remove("open"); }

let bufferWatchdog = null;   // 缓冲超时看门狗（12s，与安卓端一致）
let triedLines = new Set();  // 本轮已尝试过的线路索引（避免死循环）

function playLine(i) {
  const line = lines[i];
  if (!line) return;
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
  showLoading("加载线路中…");
  showToast("正在播放：" + (line.label || ("线路" + (i + 1))));

  const isHls = /\.m3u8(\?|$)/i.test(url);
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
      xhrSetup: (xhr) => {
        xhr.setRequestHeader("User-Agent", isKdsvod ? APPLE_UA : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        xhr.setRequestHeader("Referer", lineReferer);
      },
    });
    hls.loadSource(url);
    hls.attachMedia(video);

    hls.on(Hls.Events.MANIFEST_PARSED, () => { hideLoading(); video.play().catch(() => {}); });

    // 任何致命错误 / fragment 加载失败 → 触发换线
    hls.on(Hls.Events.ERROR, (_e, data) => {
      if (data.fatal || data.details === "fragLoadError" || data.details === "manifestLoadError") {
        stopWatchdog();
        onLineFail("线路错误：" + (data.type || data.details));
      }
    });

    // 持续缓冲 12s 判卡死 → 换线（对应安卓 bufferWatchdog）
    video.addEventListener("waiting", onWaiting);
  } else {
    destroyHls();
    video.removeEventListener("waiting", onWaiting);
    video.src = url;
    video.play().then(() => hideLoading()).catch(() => {});
    video.addEventListener("error", onVideoError);
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
  const next = lines.findIndex((l, i) => i > currentLineIdx && l.playable && !triedLines.has(i));
  if (next >= 0) {
    currentLineIdx = next;
    renderLines();
    showToast("自动换线：" + (lines[next].label || ("线路" + (next + 1))));
    playLine(currentLineIdx);
  } else {
      // 所有线路均不可播：回退到第一条重试一轮，仍未果则提示
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

// 启动
init();