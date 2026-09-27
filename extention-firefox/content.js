function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function getCleanTitle() {
  // Page title mirrors the search query (e.g. "Upwork - (scraping OR...) AND (Cloudflare OR...)"),
  // which falsely triggers block markers like /cloudflare/i. Strip query terms first.
  let title = document.title || "";
  try {
    const q = new URLSearchParams(location.search).get("q");
    if (q) {
      const terms = q.match(/[\wа-яА-ЯёЁ-]{4,}/g) || [];
      for (const term of terms) {
        title = title.replace(new RegExp(term.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "gi"), "");
      }
    }
  } catch (e) {}
  return title;
}

function detectBlock(withEmptyCheck) {
  const title = getCleanTitle();
  const text = (document.body && document.body.innerText) || "";
  // Cloudflare is localized: on a Russian browser the challenge says
  // "Подтвердите, что вы человек", so markers must cover both languages.
  const markers = [
    /just a moment/i,
    /checking your browser/i,
    /cloudflare/i,
    /attention required/i,
    /pardon our interruption/i,
    /access denied/i,
    /verify you are (a )?human/i,
    /подтвердите/i,
    /один момент/i,
    /проверка браузера/i
  ];
  for (const re of markers) {
    if (re.test(title)) return `Блокировка в title: "${title.trim().slice(0, 100)}"`;
  }
  // Generic words like "cloudflare" appear in job listings; body check uses only strong markers.
  const bodyMarkers = [
    /just a moment/i,
    /checking your browser/i,
    /attention required/i,
    /pardon our interruption/i,
    /access denied/i,
    /verify you are (a )?human/i,
    /подтвердите, что вы человек/i,
    /проверка вашего браузера/i
  ];
  const head = text.slice(0, 1000);
  for (const re of bodyMarkers) {
    if (re.test(head)) return `Блокировка обнаружена на странице: ${re}`;
  }
  // Empty body late in the load cycle is almost always a challenge page.
  // Only checked when asked: at document_idle the SPA shell is legitimately thin.
  if (withEmptyCheck && text.trim().length < 200) return "Страница пустая или не прогрузилась";
  return null;
}

function ping() {
  try {
    chrome.runtime.sendMessage({ action: "ping" });
  } catch (e) {}
}

async function scrollAndCapture() {
  const earlyBlock = detectBlock(false);
  if (earlyBlock) {
    chrome.runtime.sendMessage({ action: "blocked", error: earlyBlock });
    return;
  }

  // Total on-page time budget: random 7-10s from script start to capture.
  // Long dwell triggers Cloudflare; a fixed rhythm would too, hence random.
  const budgetMs = 7000 + Math.random() * 3000;
  const t0 = Date.now();

  // Wait for the Nuxt app to render results (any jobs count incl. "0 jobs",
  // or a job tile link), but leave ~2s of the budget for scroll + settle.
  // Ping keeps the background event page awake.
  const resultsReady = () => {
    const el = document.querySelector('[data-test="JobsCountQA"]');
    const t = el ? el.textContent : "";
    if (/[\d][\d,]*\s+jobs?/i.test(t)) return true;
    return !!document.querySelector('a[href*="/jobs/"]');
  };
  for (let i = 0; i < 20 && Date.now() - t0 < budgetMs - 2000; i++) {
    if (resultsReady()) break;
    if (i % 6 === 0) ping();
    await sleep(500);
  }

  window.scrollTo(0, document.body.scrollHeight);
  await sleep(1000);
  ping();

  // Burn whatever remains of the budget so total stay lands in 7-10s.
  const remaining = budgetMs - (Date.now() - t0);
  if (remaining > 0) await sleep(remaining);

  const lateBlock = detectBlock(true);
  if (lateBlock) {
    chrome.runtime.sendMessage({ action: "blocked", error: lateBlock });
    return;
  }

  const html = document.documentElement.outerHTML;
  chrome.runtime.sendMessage({
    action: "htmlReady",
    html: html,
    title: document.title
  });
}

scrollAndCapture();
