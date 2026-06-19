// Само-обновление агента.
// Логика: агент НЕ делает git pull сам (файлы заняты) — он лишь решает «пора обновиться»
// и завершает процесс; обёртка run.sh видит выход, делает git pull + npm install и
// перезапускает уже новый код. Триггеры: команда update, опрос remote git, config/version.
const { execFileSync } = require("child_process");
const path = require("path");
const { doc, onSnapshot } = require("firebase/firestore");

const REPO_DIR = path.join(__dirname, "..");
const POLL_MS = 10 * 60 * 1000; // как часто опрашивать remote git

function git(args) {
  return execFileSync("git", args, { cwd: REPO_DIR, stdio: ["ignore", "pipe", "ignore"] }).toString().trim();
}
function isGitRepo() {
  try { git(["rev-parse", "--is-inside-work-tree"]); return true; } catch (_) { return false; }
}
function currentSha() {
  try { return git(["rev-parse", "HEAD"]); } catch (_) { return null; }
}
function currentBranch() {
  try { return git(["rev-parse", "--abbrev-ref", "HEAD"]); } catch (_) { return "main"; }
}
function remoteSha(branch) {
  try { return (git(["ls-remote", "origin", branch]).split(/\s+/)[0]) || null; } catch (_) { return null; }
}

let restarting = false;
// Завершаем процесс — run.sh подтянет новый код и перезапустит.
function triggerRestart(reason) {
  if (restarting) return;
  restarting = true;
  console.log(`↻ Обновление (${reason}): выхожу, run.sh подтянет новый код и перезапустит.`);
  setTimeout(() => process.exit(0), 800); // дать дописать статусы в Firestore
}

function watchUpdates(ctx) {
  const { db } = ctx;
  if (!isGitRepo()) {
    console.log("ℹ Агент запущен не из git-репозитория — авто-обновление выключено.");
    return () => {};
  }
  const branch = currentBranch();
  console.log("обновления: ветка", branch, "| sha", (currentSha() || "").slice(0, 7));

  // 1) периодический опрос remote
  const timer = setInterval(() => {
    const r = remoteSha(branch);
    if (r && r !== currentSha()) { console.log("обновления: в репо новый коммит", r.slice(0, 7)); triggerRestart("git-poll"); }
  }, POLL_MS);

  // 2) мгновенно — если кто-то пишет sha в config/version (напр. GitHub Action на push)
  const unsub = onSnapshot(doc(db, "config", "version"), (snap) => {
    const v = snap.data();
    if (v && v.sha && v.sha !== currentSha()) { console.log("обновления: сигнал config/version", String(v.sha).slice(0, 7)); triggerRestart("config/version"); }
  }, () => {});

  return () => { clearInterval(timer); unsub(); };
}

module.exports = { watchUpdates, triggerRestart, currentSha, currentBranch, isGitRepo };
