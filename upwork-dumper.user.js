// ==UserScript==
// @name         Upwork HTML Dumper (iOS)
// @description  Dumps rendered Upwork search pages to HTML files. Manual dump of the current page + full queue run. iOS Safari via the Userscripts app.
// @match        https://www.upwork.com/*
// @run-at       document-idle
// @grant        none
// ==/UserScript==

// ---------------------------------------------------------------------------
// iOS counterpart of the Chrome extension (background.js / content.js).
// Cleaning, naming and pagination-discovery logic is ported 1:1.
// Differences vs the extension:
//   - queue state lives in localStorage instead of chrome.storage;
//   - navigation happens in the SAME tab (location.href), no tab management;
//   - downloads go through a blob <a download> click into Safari's Downloads;
//   - filenames are flat: "<runStamp>_<keyword>_p<N>.html" (no subfolders,
//     iOS Safari does not create directories from download names);
//   - the URL list is either edited in the on-page "URLs" panel (persisted in
//     localStorage) or by editing DEFAULT_URLS below. urls.json is NOT read
//     here — it stays the Chrome extension's source.
// ---------------------------------------------------------------------------

(function () {
  "use strict";

  if (window.top !== window.self) return; // top frame only

  const DEFAULT_PER_PAGE = 50;
  const STATE_KEY = "upworkDumper.state.v1";
  const CUSTOM_URLS_KEY = "upworkDumper.customUrls.v1";
  const PANEL_ID = "uw-dumper-panel";

  // Default queue. Editable at runtime via the "URLs" button (saved to
  // localStorage and takes precedence over this list).
  const DEFAULT_URLS = [
    "https://www.upwork.com/nx/search/jobs/?category2_uid=531770282580668418&contractor_tier=1,2&payment_verified=1&proposals=0-4,5-9,10-14&hourly_rate=15-&amount=50-&sort=recency&page=1&per_page=50&q=%28Java%20OR%20Spring%29%20AND%20%28developer%20OR%20engineer%20OR%20backend%29%20NOT%20%28homework%20OR%20assignment%20OR%20student%20OR%20intern%20OR%20free%20OR%20unpaid%20OR%20volunteer%20OR%20flutter%20OR%20%22react%20native%22%20OR%20wordpress%20OR%20shopify%20OR%20php%20OR%20python%20OR%20minecraft%29",
    "https://www.upwork.com/nx/search/jobs/?category2_uid=531770282580668418&contractor_tier=1,2&payment_verified=1&proposals=0-4,5-9,10-14&hourly_rate=15-&amount=50-&sort=recency&page=1&per_page=50&q=title%3A%28Java%20OR%20Spring%20OR%20%22Spring%20Boot%22%20OR%20Android%20OR%20Kotlin%29%20AND%20%28bugfix%20OR%20fix%20OR%20debug%20OR%20urgent%20OR%20error%20OR%20broken%29",
    "https://www.upwork.com/nx/search/jobs/?category2_uid=531770282580668418&contractor_tier=1,2&payment_verified=1&proposals=0-4,5-9,10-14&hourly_rate=15-&amount=50-&sort=recency&page=1&per_page=50&q=%28Microservice%20OR%20Microservices%29%20AND%20%28Java%20OR%20Spring%20OR%20Docker%29%20NOT%20%28python%20OR%20golang%20OR%20.net%20OR%20node%20OR%20php%29",
    "https://www.upwork.com/nx/search/jobs/?category2_uid=531770282580668418&contractor_tier=1,2&payment_verified=1&proposals=0-4,5-9,10-14&hourly_rate=15-&amount=50-&sort=recency&page=1&per_page=50&q=%28%22Spring%20Security%22%20OR%20JWT%20OR%20OAuth%29%20AND%20%28Spring%20OR%20Java%20OR%20backend%29%20NOT%20%28python%20OR%20django%20OR%20php%29",
    "https://www.upwork.com/nx/search/jobs/?category2_uid=531770282580668418&contractor_tier=1,2&payment_verified=1&proposals=0-4,5-9,10-14&hourly_rate=15-&amount=50-&sort=recency&page=1&per_page=50&q=%28Android%20OR%20Kotlin%29%20AND%20%28Java%20OR%20Firebase%20OR%20SQLite%20OR%20%22Jetpack%20Compose%22%29%20NOT%20%28flutter%20OR%20flutterflow%20OR%20dart%20OR%20%22react%20native%22%20OR%20unity%20OR%20ios%20OR%20swift%20OR%20game%29",
    "https://www.upwork.com/nx/search/jobs/?category2_uid=531770282580668418&contractor_tier=1,2&payment_verified=1&proposals=0-4,5-9,10-14&hourly_rate=15-&amount=50-&sort=recency&page=1&per_page=50&q=%28PostgreSQL%20OR%20Postgres%20OR%20Redis%29%20AND%20%28Java%20OR%20Spring%20OR%20JPA%20OR%20Hibernate%29%20NOT%20%28python%20OR%20django%20OR%20php%20OR%20ruby%20OR%20data%29",
    "https://www.upwork.com/nx/search/jobs/?category2_uid=531770282580668418&contractor_tier=1,2&payment_verified=1&proposals=0-4,5-9,10-14&hourly_rate=15-&amount=50-&sort=recency&page=1&per_page=50&q=%28Docker%20OR%20%22CI/CD%22%20OR%20%22GitHub%20Actions%22%20OR%20Jenkins%29%20AND%20%28deploy%20OR%20deployment%20OR%20pipeline%20OR%20backend%29%20NOT%20%28wordpress%20OR%20shopify%20OR%20php%20OR%20magento%29",
    "https://www.upwork.com/nx/search/jobs/?category2_uid=531770282580668418&contractor_tier=1,2&payment_verified=1&proposals=0-4,5-9,10-14&hourly_rate=15-&amount=50-&sort=recency&page=1&per_page=50&q=title%3A%28Java%20OR%20backend%29%20AND%20%28Spring%20OR%20Java%29%20NOT%20%28php%20OR%20python%20OR%20django%20OR%20minecraft%20OR%20android%20OR%20game%29",
    "https://www.upwork.com/nx/search/jobs/?category2_uid=531770282580668418&contractor_tier=1,2&payment_verified=1&proposals=0-4,5-9,10-14&hourly_rate=15-&amount=50-&sort=recency&page=1&per_page=50&q=%28%22Spring%20Boot%22%20OR%20%22Spring%20Security%22%20OR%20%22Spring%20MVC%22%29%20NOT%20%28flutter%20OR%20php%20OR%20%22react%20native%22%20OR%20magento%29",
    "https://www.upwork.com/nx/search/jobs/?category2_uid=531770282580668418&contractor_tier=1,2&payment_verified=1&proposals=0-4,5-9,10-14&hourly_rate=15-&amount=50-&sort=recency&page=1&per_page=50&q=%28Selenium%20OR%20%22browser%20automation%22%20OR%20%22web%20automation%22%29%20AND%20%28Java%20OR%20Kotlin%20OR%20Spring%29%20NOT%20%28python%20OR%20%22data%20scraping%22%29",
    "https://www.upwork.com/nx/search/jobs/?category2_uid=531770282580668418&contractor_tier=1,2&payment_verified=1&proposals=0-4,5-9,10-14&hourly_rate=15-&amount=50-&sort=recency&page=1&per_page=50&q=%28fullstack%20OR%20%22full%20stack%22%20OR%20%22full-stack%22%29%20AND%20%28Spring%20OR%20Java%29%20NOT%20%28php%20OR%20python%20OR%20django%20OR%20laravel%20OR%20wordpress%20OR%20.net%29"
  ];

  // --- helpers ported from background.js -----------------------------------

  function extractKeyword(url) {
    try {
      const q = new URL(url).searchParams.get("q");
      if (q) {
        const keyword = q.replace(/[^\wа-яА-ЯёЁ -]/g, "").trim().replace(/[\s-]+/g, "_");
        if (keyword) return keyword;
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
    if (!total) return [];
    const perPage = getPerPage(url);
    const pageCount = Math.ceil(total / perPage);
    const extra = [];
    for (let p = 2; p <= pageCount; p++) {
      extra.push(buildPageUrl(url, p));
    }
    return extra;
  }

  function sanitizeFileName(s) {
    const cleaned = String(s || "").replace(/[^\wа-яА-ЯёЁ -]/g, "").trim().replace(/[\s-]+/g, "_");
    return cleaned || "page";
  }

  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  // --- state (localStorage) ------------------------------------------------

  function loadState() {
    try {
      return JSON.parse(localStorage.getItem(STATE_KEY)) || {};
    } catch (e) {
      return {};
    }
  }

  function saveState(patch) {
    const state = Object.assign(loadState(), patch);
    localStorage.setItem(STATE_KEY, JSON.stringify(state));
    return state;
  }

  function getUrls() {
    try {
      const custom = JSON.parse(localStorage.getItem(CUSTOM_URLS_KEY));
      if (Array.isArray(custom) && custom.length > 0) return custom;
    } catch (e) {}
    return DEFAULT_URLS.slice();
  }

  // --- capture & download --------------------------------------------------

  // Scrolls to trigger lazy-loaded cards, waits for render, then captures
  // outerHTML. The control panel is temporarily removed so it never ends up
  // in the dumped file.
  async function scrollAndCapture() {
    for (let i = 0; i < 3; i++) {
      window.scrollTo(0, document.body.scrollHeight);
      await sleep(2000);
    }
    await sleep(3000);

    const panel = document.getElementById(PANEL_ID);
    if (panel) panel.remove();
    const html = document.documentElement.outerHTML;
    if (panel) document.body.appendChild(panel);
    return html;
  }

  function downloadHtml(filename, html) {
    const blob = new Blob([html], { type: "text/html;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  }

  // --- manual dump ---------------------------------------------------------

  async function dumpCurrentPage() {
    setStatus("Dumping page...", "running");
    try {
      const html = await scrollAndCapture();
      const keyword = new URL(location.href).searchParams.get("q");
      const name = sanitizeFileName(keyword || document.title).slice(0, 80);
      downloadHtml(`${name}.html`, cleanHtml(html));
      setStatus("Page saved", "done");
    } catch (e) {
      setStatus("Dump failed: " + e, "error");
    }
  }

  // --- queue run -----------------------------------------------------------

  // Loose comparison: Upwork may re-encode the query string, so compare the
  // parts that identify the target page instead of the raw href.
  function isTargetPage(currentHref, targetUrl) {
    try {
      const cur = new URL(currentHref);
      const tgt = new URL(targetUrl);
      if (cur.pathname !== tgt.pathname) return false;
      for (const key of ["q", "page", "per_page"]) {
        if ((cur.searchParams.get(key) || "") !== (tgt.searchParams.get(key) || "")) return false;
      }
      return true;
    } catch (e) {
      return false;
    }
  }

  function startRun() {
    const urls = getUrls();
    if (urls.length === 0) {
      setStatus("URL list is empty", "error");
      return;
    }
    saveState({ running: true, index: 0, urls: urls.slice(), runStamp: makeRunStamp() });
    updatePanel();
    if (isTargetPage(location.href, urls[0])) {
      runStep();
    } else {
      location.href = urls[0];
    }
  }

  function stopRun() {
    saveState({ running: false });
    updatePanel();
    setStatus("Stopped", "error");
  }

  function finishRun(total) {
    saveState({ running: false });
    updatePanel();
    setStatus(`Done: ${total} page(s)`, "done");
  }

  async function runStep() {
    const state = loadState();
    if (!state.running) return;
    const queue = state.urls || [];
    const index = state.index || 0;

    if (index >= queue.length) {
      finishRun(queue.length);
      return;
    }

    const url = queue[index];
    if (!isTargetPage(location.href, url)) {
      // Landed somewhere unexpected (or run was started from another page):
      // navigate to the queued URL and let the next page load continue.
      location.href = url;
      return;
    }

    setStatus(`Scraping ${index + 1}/${queue.length}...`, "running");

    let html;
    try {
      html = await scrollAndCapture();
    } catch (e) {
      setStatus("Capture failed, skipping", "error");
      advance(state, index);
      return;
    }

    try {
      const extra = discoverExtraPages(url, html);
      if (extra.length > 0) {
        state.urls = queue.slice(0, index + 1).concat(extra, queue.slice(index + 1));
      }
      const keyword = extractKeyword(url);
      const page = getPageNumber(url);
      const suffix = page > 1 ? `_p${page}` : "";
      downloadHtml(`${state.runStamp}_${keyword}${suffix}.html`, cleanHtml(html));
    } catch (e) {
      setStatus("Save failed, skipping", "error");
    }

    advance(state, index);
  }

  function advance(state, index) {
    const queue = state.urls || [];
    const nextIndex = index + 1;
    saveState({ urls: queue, index: nextIndex });
    updatePanel();

    if (nextIndex >= queue.length) {
      finishRun(queue.length);
      return;
    }
    const delay = 5000 + Math.random() * 3000;
    setTimeout(() => {
      location.href = queue[nextIndex];
    }, delay);
  }

  // --- control panel UI ----------------------------------------------------

  let statusEl = null;
  let progressEl = null;

  function setStatus(text, kind) {
    if (!statusEl) return;
    statusEl.textContent = text;
    statusEl.style.color =
      kind === "error" ? "#d93025" : kind === "done" ? "#1a73e8" : kind === "running" ? "#14a800" : "#5f6368";
  }

  function updatePanel() {
    const state = loadState();
    const total = (state.urls || []).length;
    const index = state.index || 0;
    if (progressEl) progressEl.textContent = total > 0 ? `${Math.min(index, total)} / ${total}` : "- / -";
  }

  function makeButton(label, onClick, bg) {
    const btn = document.createElement("button");
    btn.textContent = label;
    btn.style.cssText =
      `flex:1;padding:8px 0;border:none;border-radius:6px;font-size:13px;font-weight:600;` +
      `color:#fff;background:${bg};cursor:pointer;`;
    btn.addEventListener("click", onClick);
    return btn;
  }

  function openUrlsEditor() {
    const overlay = document.createElement("div");
    overlay.style.cssText =
      "position:fixed;inset:0;z-index:1000000;background:rgba(0,0,0,0.55);display:flex;" +
      "align-items:center;justify-content:center;padding:16px;";

    const box = document.createElement("div");
    box.style.cssText =
      "background:#fff;border-radius:10px;width:100%;max-width:640px;max-height:90%;" +
      "display:flex;flex-direction:column;padding:12px;gap:8px;font:13px system-ui,sans-serif;";

    const hint = document.createElement("div");
    hint.textContent = "One URL per line. Saved list overrides the defaults built into the script.";
    hint.style.cssText = "color:#5f6368;font-size:12px;";

    const textarea = document.createElement("textarea");
    textarea.value = getUrls().join("\n");
    textarea.style.cssText =
      "flex:1;min-height:40vh;width:100%;box-sizing:border-box;font:11px/1.5 monospace;" +
      "padding:8px;border:1px solid #d0d3d8;border-radius:6px;";

    const row = document.createElement("div");
    row.style.cssText = "display:flex;gap:8px;";

    const close = () => overlay.remove();

    const saveBtn = makeButton("Save", () => {
      const urls = textarea.value
        .split("\n")
        .map((s) => s.trim())
        .filter((s) => s.startsWith("http"));
      if (urls.length === 0) {
        hint.textContent = "List is empty — nothing saved.";
        hint.style.color = "#d93025";
        return;
      }
      localStorage.setItem(CUSTOM_URLS_KEY, JSON.stringify(urls));
      close();
      setStatus(`Saved ${urls.length} URL(s)`, "done");
      updatePanel();
    }, "#14a800");

    const resetBtn = makeButton("Reset to defaults", () => {
      localStorage.removeItem(CUSTOM_URLS_KEY);
      textarea.value = DEFAULT_URLS.join("\n");
      hint.textContent = "Custom list cleared, defaults restored (after Save).";
      hint.style.color = "#5f6368";
    }, "#f29900");

    const cancelBtn = makeButton("Cancel", close, "#5f6368");

    row.append(saveBtn, resetBtn, cancelBtn);
    box.append(hint, textarea, row);
    overlay.appendChild(box);
    document.body.appendChild(overlay);
  }

  function createPanel() {
    if (document.getElementById(PANEL_ID)) return;

    const panel = document.createElement("div");
    panel.id = PANEL_ID;
    panel.style.cssText =
      "position:fixed;right:10px;bottom:10px;z-index:999999;width:210px;background:#fff;" +
      "border:1px solid #d0d3d8;border-radius:10px;box-shadow:0 4px 16px rgba(0,0,0,0.25);" +
      "padding:10px;font:12px system-ui,sans-serif;color:#1f2328;";

    const title = document.createElement("div");
    title.textContent = "Upwork Dumper";
    title.style.cssText = "font-weight:700;font-size:13px;margin-bottom:8px;color:#14a800;";

    const row1 = document.createElement("div");
    row1.style.cssText = "display:flex;gap:6px;margin-bottom:6px;";
    row1.append(
      makeButton("Start", startRun, "#14a800"),
      makeButton("Stop", stopRun, "#d93025"),
      makeButton("URLs", openUrlsEditor, "#5f6368")
    );

    const dumpBtn = makeButton("Dump this page", dumpCurrentPage, "#1a73e8");
    dumpBtn.style.width = "100%";

    progressEl = document.createElement("div");
    progressEl.style.cssText = "margin-top:8px;color:#5f6368;";

    statusEl = document.createElement("div");
    statusEl.style.cssText = "margin-top:2px;word-break:break-word;";
    setStatus("Waiting...");

    panel.append(title, row1, dumpBtn, progressEl, statusEl);
    document.body.appendChild(panel);
    updatePanel();
  }

  // --- entry point ---------------------------------------------------------

  createPanel();

  // If a queue run is in progress, this page load is the next step of it.
  if (loadState().running) {
    runStep();
  }
})();
