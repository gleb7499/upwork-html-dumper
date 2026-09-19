const TAB_TIMEOUT_MS = 30000;
const DEFAULT_PER_PAGE = 50;

let currentTabId = null;
let tabTimeout = null;
let delayTimeout = null;
let running = false;

function extractKeyword(url) {
  try {
    const u = new URL(url);
    const q = u.searchParams.get("q");
    if (q) {
      const keyword = q.replace(/[^\wа-яА-ЯёЁ -]/g, "").trim().replace(/[\s-]+/g, "_");
      if (keyword) return keyword;
    }
    const m = u.pathname.match(/^\/jobs\/([^/]+)/);
    if (m) {
      const slug = decodeURIComponent(m[1])
        .replace(/<[^>]*>/g, "")
        .replace(/[^\wа-яА-ЯёЁ -]/g, "")
        .trim()
        .replace(/[\s-]+/g, "_")
        .slice(0, 80);
      if (slug) return slug;
    }
  } catch (e) {}
  return "page";
}

function getPageNumber(url) {
  try {
    const p = parseInt(new URL(url).searchParams.get("page"), 10);
    if (Number.isFinite(p) && p > 0) return p;
  } catch (e) {}
  return 1;
}

function getPerPage(url) {
  try {
    const p = parseInt(new URL(url).searchParams.get("per_page"), 10);
    if (Number.isFinite(p) && p > 0) return p;
  } catch (e) {}
  return DEFAULT_PER_PAGE;
}

function makeRunStamp() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}_${pad(d.getHours())}-${pad(d.getMinutes())}`;
}

async function makeFilename(index, url) {
  const keyword = extractKeyword(url);
  const page = getPageNumber(url);
  const state = await chrome.storage.local.get("runStamp");
  const runStamp = state.runStamp || makeRunStamp();
  const suffix = page > 1 ? `_p${page}` : "";
  return `${runStamp}/${keyword}${suffix}.html`;
}

// Text-level cleanup only (no DOM parsing): strips dead weight after JS
// has already inlined all data into the markup.
function cleanHtml(html) {
  return html
    .replace(/<script\b[^>]*>[\s\S]*?<\/script\s*>/gi, "")
    .replace(/<style\b[^>]*>[\s\S]*?<\/style\s*>/gi, "")
    .replace(/<noscript\b[^>]*>[\s\S]*?<\/noscript\s*>/gi, "")
    .replace(/<link\b[^>]*>/gi, "")
    .replace(/<svg\b[^>]*>[\s\S]*?<\/svg\s*>/gi, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\s(on\w+|style)="[^"]*"/gi, "")
    .replace(/\s(on\w+|style)='[^']*'/gi, "")
    .replace(/\n\s*\n/g, "\n");
}

// Finds total results count in the rendered HTML string (regex on text,
// not DOM traversal). Returns null if nothing matched.
function parseTotalHits(html) {
  const patterns = [
    /"total(?:Hits|Results|Count)?"\s*:\s*"?(\d[\d,]*)"?/i,
    /([\d][\d,]*)\s+jobs?\s+found/i,
    /of\s+([\d][\d,]*)\s+jobs?/i
  ];
  for (const pattern of patterns) {
    const m = html.match(pattern);
    if (m) {
      const n = parseInt(m[1].replace(/[^\d]/g, ""), 10);
      if (n > 0) return n;
    }
  }
  return null;
}

function buildPageUrl(url, page) {
  const u = new URL(url);
  u.searchParams.set("page", String(page));
  return u.toString();
}

// For a page-1 URL, derives extra page URLs (page=2..N) from total hits.
function discoverExtraPages(url, html) {
  if (getPageNumber(url) !== 1) return [];
  const total = parseTotalHits(html);
  if (!total) {
    console.warn("Total hits not found in HTML, single page assumed:", url);
    return [];
  }
  const perPage = getPerPage(url);
  const pageCount = Math.ceil(total / perPage);
  const extra = [];
  for (let p = 2; p <= pageCount; p++) {
    extra.push(buildPageUrl(url, p));
  }
  if (extra.length > 0) {
    console.log(`Total hits: ${total}, adding ${extra.length} extra page(s) for ${url}`);
  }
  return extra;
}

async function downloadHtml(index, url, html) {
  const cleaned = cleanHtml(html);
  const dataUrl = "data:text/html;charset=utf-8," + encodeURIComponent(cleaned);
  const downloadId = await chrome.downloads.download({
    url: dataUrl,
    filename: await makeFilename(index, url),
    saveAs: false
  });
  await chrome.storage.local.set({ lastDownloadId: downloadId });
  return downloadId;
}

// Injected into the active tab by chrome.scripting for single-page dumps.
// Must stay self-contained: no closures or imports from this file.
async function scrollAndCapturePage() {
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  for (let i = 0; i < 3; i++) {
    window.scrollTo(0, document.body.scrollHeight);
    await sleep(2000);
  }
  await sleep(3000);
  return document.documentElement.outerHTML;
}

function sanitizeFileName(s) {
  const cleaned = String(s || "").replace(/[^\wа-яА-ЯёЁ -]/g, "").trim().replace(/[\s-]+/g, "_");
  return cleaned || "page";
}

async function dumpActiveTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab || !tab.url || !tab.url.startsWith("https://www.upwork.com/")) {
    return { ok: false, error: "Active tab is not an Upwork page" };
  }
  const [result] = await chrome.scripting.executeScript({
    target: { tabId: tab.id },
    func: scrollAndCapturePage
  });
  const html = result && result.result;
  if (!html) {
    return { ok: false, error: "Could not capture page HTML" };
  }
  const keyword = new URL(tab.url).searchParams.get("q");
  const name = sanitizeFileName(keyword || tab.title).slice(0, 80);
  const dataUrl = "data:text/html;charset=utf-8," + encodeURIComponent(cleanHtml(html));
  const downloadId = await chrome.downloads.download({
    url: dataUrl,
    filename: `${name}.html`,
    saveAs: false
  });
  await chrome.storage.local.set({ lastDownloadId: downloadId });
  return { ok: true };
}

function broadcast(message) {
  chrome.runtime.sendMessage(message).catch(() => {});
}

async function finish(total) {
  running = false;
  await chrome.storage.local.set({ running: false });
  broadcast({ action: "done", total });
  chrome.notifications.create("dump-done", {
    type: "basic",
    iconUrl: "icons/icon128.png",
    title: "Upwork HTML Dumper",
    message: `Готово: обработано ${total} стр. Нажми, чтобы открыть папку.`
  });
}

chrome.notifications.onClicked.addListener(async () => {
  const state = await chrome.storage.local.get("lastDownloadId");
  if (typeof state.lastDownloadId === "number") {
    chrome.downloads.show(state.lastDownloadId);
  } else {
    chrome.downloads.showDefaultFolder();
  }
});

async function finishTab(tabId) {
  if (tabTimeout) {
    clearTimeout(tabTimeout);
    tabTimeout = null;
  }
  try {
    await chrome.tabs.remove(tabId);
  } catch (e) {}
  currentTabId = null;
}

async function processNext() {
  const state = await chrome.storage.local.get(["urls", "index", "running"]);
  if (!state.running) return;
  const urls = state.urls || [];
  let index = state.index || 0;

  if (index >= urls.length) {
    await finish(urls.length);
    return;
  }

  const url = urls[index];
  broadcast({ action: "progress", index, total: urls.length, status: "Scraping..." });

  const tab = await chrome.tabs.create({ url, active: false });
  currentTabId = tab.id;

  tabTimeout = setTimeout(async () => {
    console.error(`Timeout: tab did not respond in ${TAB_TIMEOUT_MS / 1000}s, url: ${url}`);
    await failRun(`Таймаут: вкладка не ответила за ${TAB_TIMEOUT_MS / 1000}с`, url);
  }, TAB_TIMEOUT_MS);
}

async function failRun(reason, url) {
  running = false;
  if (tabTimeout) { clearTimeout(tabTimeout); tabTimeout = null; }
  if (delayTimeout) { clearTimeout(delayTimeout); delayTimeout = null; }
  if (currentTabId) {
    try { await chrome.tabs.remove(currentTabId); } catch (e) {}
    currentTabId = null;
  }
  await chrome.storage.local.set({ running: false });
  broadcast({ action: "error", error: reason, url });
  chrome.notifications.create("dump-error", {
    type: "basic",
    iconUrl: "icons/icon128.png",
    title: "Upwork HTML Dumper — ошибка",
    message: `Процесс прерван: ${reason}${url ? ". URL: " + url : ""}`
  });
}

async function advance(total, index, wasError) {
  const nextIndex = index + 1;
  await chrome.storage.local.set({ index: nextIndex });
  broadcast({
    action: "progress",
    index: nextIndex,
    total,
    status: wasError ? "Error (skipped)" : "Scraping..."
  });

  if (nextIndex >= total) {
    await finish(total);
    return;
  }

  const delay = 5000 + Math.random() * 3000;
  delayTimeout = setTimeout(processNext, delay);
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.action === "start") {
    (async () => {
      const state = await chrome.storage.local.get(["urls", "index", "running"]);
      if (!running && !state.running) {
        running = true;
        const oldUrls = state.urls || [];
        const oldIndex = state.index || 0;
        // Resume unfinished run (after error/stop) from saved index; otherwise start fresh.
        if (oldUrls.length > 0 && oldIndex > 0 && oldIndex < oldUrls.length) {
          await chrome.storage.local.set({ running: true });
          broadcast({ action: "progress", index: oldIndex, total: oldUrls.length, status: "Scraping..." });
          processNext();
        } else {
          const res = await fetch(chrome.runtime.getURL("urls.json"));
          const urls = await res.json();
          await chrome.storage.local.set({ urls, index: 0, runStamp: makeRunStamp(), running: true });
          broadcast({ action: "progress", index: 0, total: urls.length, status: "Scraping..." });
          processNext();
        }
      }
    })();
    sendResponse({ ok: true });
    return;
  }

  if (message.action === "stop") {
    (async () => {
      running = false;
      if (tabTimeout) {
        clearTimeout(tabTimeout);
        tabTimeout = null;
      }
      if (delayTimeout) {
        clearTimeout(delayTimeout);
        delayTimeout = null;
      }
      if (currentTabId) {
        try {
          await chrome.tabs.remove(currentTabId);
        } catch (e) {}
        currentTabId = null;
      }
      await chrome.storage.local.set({ running: false });
      broadcast({ action: "stopped" });
    })();
    sendResponse({ ok: true });
    return;
  }

  if (message.action === "dumpCurrent") {
    dumpActiveTab()
      .then((result) => sendResponse(result))
      .catch((e) => {
        console.error("Dump current page failed:", e);
        sendResponse({ ok: false, error: String(e) });
      });
    return true;
  }

  if (message.action === "pageError" && sender.tab && sender.tab.id === currentTabId) {
    (async () => {
      const state = await chrome.storage.local.get(["urls", "index"]);
      const urls = state.urls || [];
      const index = state.index || 0;
      await failRun(message.error || "Неизвестная ошибка страницы", urls[index]);
    })();
    return;
  }

  if (message.action === "htmlReady" && sender.tab && sender.tab.id === currentTabId) {
    (async () => {
      const state = await chrome.storage.local.get(["urls", "index"]);
      let urls = state.urls || [];
      const index = state.index || 0;
      const tabId = sender.tab.id;
      try {
        const extra = discoverExtraPages(urls[index], message.html);
        if (extra.length > 0) {
          urls = urls.slice(0, index + 1).concat(extra, urls.slice(index + 1));
        }
        await downloadHtml(index, urls[index], message.html);
        await chrome.storage.local.set({ urls });
      } catch (e) {
        console.error("Download failed:", e);
      }
      await finishTab(tabId);
      await advance(urls.length, index, false);
    })();
  }
});
