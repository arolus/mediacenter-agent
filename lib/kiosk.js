// Parental (kiosk) mode: the TV app is the launcher, so the box boots straight into MediaCenter
// and Home leads back to it. Leaving to the stock Google TV interface requires a PIN.
//
// The PIN lives in Firestore (config/tv.pin) and is edited from the dashboard. It is deliberately
// NEVER sent to the TV page: the page posts what the user typed, the agent compares it here.
// That way anyone on the LAN who opens the node's TV page cannot read the code.
const { execFile } = require("child_process");

// System launchers we may need to hand control back to. `pm`/`cmd` are unavailable from Termux
// on Android TV, so the list cannot be discovered at runtime — hence the known names, with an
// escape hatch via config.systemLauncher. Settings is the last resort: from there the user can
// reach everything anyway.
const LAUNCHERS = [
  "com.google.android.apps.tv.launcherx/.home.HomeActivity",
  "com.google.android.tvlauncher/.MainActivity",
  "com.google.android.apps.tv.launcher/.MainActivity"
];

function amStart(args, cb) {
  execFile("am", args, { timeout: 8000 }, (err, out) => cb(err, String(out || "")));
}

// `am start` exits 0 even when it could not resolve the component, so treat the "Error:" line
// in its output as failure and move on to the next candidate.
function tryLaunch(list, i, cb) {
  if (i >= list.length) {
    return amStart(["start", "-a", "android.settings.SETTINGS"], (err) =>
      cb(err ? new Error("не удалось открыть ни лаунчер, ни настройки") : null, "settings"));
  }
  amStart(["start", "-n", list[i]], (err, out) => {
    if (!err && !/Error:/i.test(out)) return cb(null, list[i]);
    tryLaunch(list, i + 1, cb);
  });
}

function leaveKiosk(config, cb) {
  const list = [config.systemLauncher, ...LAUNCHERS].filter(Boolean);
  tryLaunch(list, 0, cb);
}

module.exports = { leaveKiosk };
