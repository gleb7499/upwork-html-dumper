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

function detectBlock() {
  const title = getCleanTitle();
  const text = (document.body && document.body.innerText) || "";
  const markers = [
    /just a moment/i,
    /checking your browser/i,
    /cloudflare/i,
    /attention required/i,
    /pardon our interruption/i,
    /access denied/i,
    /verify you are (a )?human/i
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
    /verify you are (a )?human/i
  ];
  const head = text.slice(0, 1000);
  for (const re of bodyMarkers) {
    if (re.test(head)) return `Блокировка обнаружена на странице: ${re}`;
  }
  if (text.trim().length < 200) return "Страница пустая или не прогрузилась";
  return null;
}

async function scrollAndCapture() {
  const earlyBlock = detectBlock();
  if (earlyBlock) {
    chrome.runtime.sendMessage({ action: "pageError", error: earlyBlock });
    return;
  }

  for (let i = 0; i < 3; i++) {
    window.scrollTo(0, document.body.scrollHeight);
    await sleep(2000);
  }

  await sleep(3000);

  const lateBlock = detectBlock();
  if (lateBlock) {
    chrome.runtime.sendMessage({ action: "pageError", error: lateBlock });
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
