// Установка VLC на ноде: скачивает официальный APK с videolan.org под ABI устройства
// и запускает системный установщик (пользователь подтверждает одним тапом).
const { execFile } = require("child_process");
const fs = require("fs");

const DEFAULT_PKG = "org.videolan.vlc";

// Установлен ли пакет (через Android `pm`). На не-Android — вернёт false.
function isInstalled(pkg, cb) {
  execFile("pm", ["list", "packages", pkg || DEFAULT_PKG], { timeout: 8000 }, (err, out) => {
    cb(!err && String(out || "").includes(pkg || DEFAULT_PKG));
  });
}

function getprop(name) {
  return new Promise((resolve) => {
    execFile("getprop", [name], { timeout: 5000 }, (_e, out) => resolve(String(out || "").trim()));
  });
}

// Скачивает свежий VLC APK под ABI телефона в /sdcard/Download. Возвращает путь.
async function downloadVLC() {
  let abi = await getprop("ro.product.cpu.abi");
  if (!abi) abi = "arm64-v8a";
  const base = "https://get.videolan.org/vlc-android/last/";
  const listing = await (await fetch(base)).text();
  // Ищем APK ровно под нашу ABI (иначе — универсальный).
  const pick = (re) => (listing.match(re) || [])[0];
  const file =
    pick(new RegExp(`VLC-Android-[0-9.]+-${abi.replace(/[-.]/g, "\\$&")}\\.apk`)) ||
    pick(/VLC-Android-[0-9.]+-arm64-v8a\.apk/) ||
    pick(/VLC-Android-[0-9.]+-all\.apk/);
  if (!file) throw new Error("не нашёл VLC APK под " + abi);

  const res = await fetch(base + file);
  if (!res.ok) throw new Error("скачивание HTTP " + res.status);
  const buf = Buffer.from(await res.arrayBuffer());
  const dest = "/sdcard/Download/" + file;
  fs.writeFileSync(dest, buf);
  return { path: dest, file, size: buf.length };
}

// Запускает системный установщик APK. termux-open корректно отдаёт content:// (Android 7+),
// иначе пробуем через `am` VIEW.
function launchInstaller(apkPath, cb) {
  execFile("termux-open", [apkPath], { timeout: 8000 }, (err) => {
    if (!err) return cb(null, "termux-open");
    execFile("am", [
      "start", "-a", "android.intent.action.VIEW",
      "-d", "file://" + apkPath, "-t", "application/vnd.android.package-archive",
      "--grant-read-uri-permission"
    ], { timeout: 8000 }, (e2) => cb(e2 || null, "am"));
  });
}

module.exports = { isInstalled, downloadVLC, launchInstaller, DEFAULT_PKG };
