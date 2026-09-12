const progressEl = document.getElementById("progress");
const progressFill = document.getElementById("progressFill");
const statusEl = document.getElementById("status");
const statusDot = document.getElementById("statusDot");
const startBtn = document.getElementById("startBtn");
const stopBtn = document.getElementById("stopBtn");
const dumpBtn = document.getElementById("dumpBtn");

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
  const state = await chrome.storage.local.get(["urls", "index", "running"]);
  const total = (state.urls || []).length;
  const index = state.index || 0;
  render(index, total);
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
  }
});

init();
