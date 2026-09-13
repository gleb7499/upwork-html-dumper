function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function detectBlock() {
  const title = document.title || "";
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
  const head = text.slice(0, 1000);
  for (const re of markers) {
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
