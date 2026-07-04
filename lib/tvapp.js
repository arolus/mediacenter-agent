// Наше WebView-приложение «MediaCenter TV» (com.mediacenter.tv): честный fullscreen без
// белой полосы у выреза, жёсткий ландшафт, экран не гаснет, одна копия (singleTask).
// APK собирается на маке (tvapp/build.sh), лежит в репо (tv/app/) и приезжает на ноды
// обычным git-обновлением агента. Здесь — статус/установка/запуск с ноды.
const { execFile } = require("child_process");
const fs = require("fs");
const path = require("path");
const player = require("./player");

const PKG = "com.mediacenter.tv";
const APK = path.join(__dirname, "..", "tv", "app", "mediacenter-tv.apk");
const VERSION_FILE = path.join(__dirname, "..", "tv", "app", "version.json");

// Версия APK в репо — из сайдкара version.json (aapt на нодах нет).
function apkVersion() {
  try { return JSON.parse(fs.readFileSync(VERSION_FILE, "utf8")); } catch (_) { return null; }
}

// Версия установленного приложения. `dumpsys` из Termux запрещён, а у `pm list packages`
// флаг --show-versioncode есть с Android 8 — старее вернём null (без предложения обновиться).
function installedVersion(cb) {
  execFile("pm", ["list", "packages", "--show-versioncode", PKG], { timeout: 8000 }, (err, out) => {
    if (err) return cb(null);
    const m = String(out || "").match(/versionCode:(\d+)/);
    cb(m ? parseInt(m[1], 10) : null);
  });
}

function status(cb) {
  const apk = apkVersion();
  player.isInstalled(PKG, (installed) => {
    if (!installed) {
      return cb({ installed: false, package: PKG, apkAvailable: fs.existsSync(APK),
        apkVersionCode: apk ? apk.versionCode : null });
    }
    installedVersion((code) => cb({
      installed: true, package: PKG, apkAvailable: fs.existsSync(APK),
      versionCode: code, apkVersionCode: apk ? apk.versionCode : null,
      updateAvailable: !!(apk && code && apk.versionCode > code)
    }));
  });
}

// Установка/обновление: копируем APK из репо в Download (установщик не видит домашку Termux)
// и открываем системный установщик. Обновление поверх — та же подпись, данные сохраняются.
function install(cb) {
  if (!fs.existsSync(APK)) return cb(new Error("APK нет в репо агента (tv/app/)"));
  const dest = "/sdcard/Download/mediacenter-tv.apk";
  try { fs.copyFileSync(APK, dest); } catch (e) { return cb(e); }
  player.launchInstaller(dest, cb);
}

// Запуск приложения с портом TV-сервера (интент-extra). Повторный запуск копий не плодит.
function open(port, cb) {
  execFile("am", ["start", "-n", PKG + "/.MainActivity", "--ei", "port", String(port)],
    { timeout: 8000 }, (err) => cb && cb(err || null));
}

module.exports = { PKG, status, install, open };
