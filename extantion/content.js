function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function scrollAndCapture() {
  for (let i = 0; i < 3; i++) {
    window.scrollTo(0, document.body.scrollHeight);
    await sleep(2000);
  }

  await sleep(3000);

  const html = document.documentElement.outerHTML;
  chrome.runtime.sendMessage({
    action: "htmlReady",
    html: html,
    title: document.title
  });
}

scrollAndCapture();
