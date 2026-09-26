const progressEl = document.getElementById("progress");
const progressFill = document.getElementById("progressFill");
const statusEl = document.getElementById("status");
const statusDot = document.getElementById("statusDot");
const startBtn = document.getElementById("startBtn");
const stopBtn = document.getElementById("stopBtn");
const dumpBtn = document.getElementById("dumpBtn");
const resetBtn = document.getElementById("resetBtn");
const logEl = document.getElementById("log");

function log(msg) {
  const t = new Date().toLocaleTimeString();
  logEl.textContent += `[${t}] ${msg}\n`;
  logEl.scrollTop = logEl.scrollHeight;
}

function brief(m) {
  if (!m) return String(m);
  const copy = { ...m };
  if (copy.html !== undefined) copy.html = `<${copy.html.length} chars>`;
  return JSON.stringify(copy);
}

function resetUi() {
  setRunning(false);
  render(0, 0);
  setStatus("Waiting...", "");
}

resetBtn.addEventListener("click", () => {
  chrome.runtime.sendMessage({ action: "reset" });
  resetUi();
});

function render(index, total) {
  progressEl.textContent = total > 0 ? `${index} / ${total}` : "- / -";
  progressFill.style.width = total > 0 ? `${Math.round((index / total) * 100)}%` : "0%";
}

function setStatus(text, dotClass) {
  statusEl.textContent = text;
  statusDot.className = "status-dot" + (dotClass ? " " + dotClass : "");
}

function setRunning(isRunning) {
  startBtn.disabled = isRunning;
  stopBtn.disabled = !isRunning;
}

async function init() {
  const state = await chrome.storage.local.get(["urls", "index", "running", "logs"]);
  const total = (state.urls || []).length;
  const index = state.index || 0;
  render(index, total);
  if (state.logs && state.logs.length) {
    logEl.textContent = state.logs.join("\n") + "\n";
    logEl.scrollTop = logEl.scrollHeight;
  }
  log(`init: index=${index} total=${total} running=${!!state.running}`);
  if (state.running) {
    setRunning(true);
    setStatus("Scraping...", "running");
  } else if (total > 0 && index >= total) {
    setRunning(false);
    setStatus("Done", "done");
  } else {
    setRunning(false);
    setStatus("Waiting...", "");
  }
}

startBtn.addEventListener("click", () => {
  setRunning(true);
  setStatus("Scraping...", "running");
  chrome.runtime.sendMessage({ action: "start" });
});

stopBtn.addEventListener("click", () => {
  chrome.runtime.sendMessage({ action: "stop" });
});

dumpBtn.addEventListener("click", () => {
  dumpBtn.disabled = true;
  setStatus("Dumping page...", "running");
  chrome.runtime.sendMessage({ action: "dumpCurrent" }, (resp) => {
    dumpBtn.disabled = false;
    if (chrome.runtime.lastError || !resp || !resp.ok) {
      const err = (resp && resp.error) || "Failed (not an Upwork page?)";
      setStatus(err, "error");
    } else {
      setStatus("Page saved", "done");
    }
  });
});

chrome.runtime.onMessage.addListener((message) => {
  if (message.action === "log") {
    log(message.msg);
    return;
  }
  log(`recv ${brief(message)}`);
  if (message.action === "progress") {
    setRunning(true);
    render(message.index, message.total);
    setStatus(message.status || "Scraping...", message.status && message.status.startsWith("Error") ? "error" : "running");
  } else if (message.action === "done") {
    setRunning(false);
    setStatus("Done", "done");
  } else if (message.action === "stopped") {
    setRunning(false);
    setStatus("Stopped", "error");
  } else if (message.action === "error") {
    setRunning(false);
    setStatus(`Ошибка: ${message.error}`, "error");
  } else if (message.action === "downloadError") {
    setStatus(`Ошибка скачивания: ${message.error}`, "error");
  } else if (message.action === "reset") {
    resetUi();
  }
});

init();
