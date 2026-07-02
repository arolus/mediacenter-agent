// Транскодинг на лету (ffmpeg): AVI/MKV/HEVC/AC3 → fragmented MP4 (h264+aac),
// который умеет играть любой браузер. Если видео уже h264 — только ремукс контейнера
// (copy, почти без нагрузки); перекодируем лишь то, что реально нужно.
// Termux: pkg install ffmpeg (install.sh ставит при бутстрапе).
const { spawn, execFile } = require("child_process");

// Chrome на один <video> открывает 2+ соединения (проба + основное) — лимит с запасом.
const MAX_ACTIVE = 4;
let active = 0;

function ffmpegAvailable(cb) {
  execFile("ffmpeg", ["-version"], { timeout: 5000 }, (err) => cb(!err));
}

// Кодеки первого видео/аудио потока: решаем, что копировать, а что перекодировать.
function probeCodecs(file, cb) {
  execFile("ffprobe", [
    "-v", "error", "-show_entries", "stream=codec_type,codec_name", "-of", "json", file
  ], { timeout: 15000 }, (err, out) => {
    if (err) return cb(null);
    try {
      const streams = JSON.parse(out).streams || [];
      const v = streams.find((s) => s.codec_type === "video");
      const a = streams.find((s) => s.codec_type === "audio");
      cb({ video: v && v.codec_name, audio: a && a.codec_name });
    } catch (_) { cb(null); }
  });
}

// Стримим fMP4 в res. startSec — смещение (перезапуск с места при доработке перемотки).
function streamTranscode(item, startSec, res) {
  if (active >= MAX_ACTIVE) {
    console.warn(`transcode: 503 — занято ${active}/${MAX_ACTIVE}`);
    res.writeHead(503, { "Content-Type": "application/json" });
    return res.end(JSON.stringify({ error: "транскодер занят (максимум " + MAX_ACTIVE + " потока)" }));
  }
  probeCodecs(item.filePath, (codecs) => {
    const vCopy = codecs && codecs.video === "h264";
    const aCopy = codecs && ["aac", "mp3"].includes(codecs.audio);
    const args = ["-hide_banner", "-loglevel", "error"];
    if (startSec > 0) args.push("-ss", String(startSec));
    args.push(
      "-i", item.filePath,
      "-map", "0:v:0", "-map", "0:a:0?",
      ...(vCopy
        ? ["-c:v", "copy"]
        : ["-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-crf", "25",
           // частые ключевые кадры: браузер получает первый фрагмент через ~2с видео, а не минуты
           "-g", "48", "-keyint_min", "48",
           "-vf", "scale='min(1280,iw)':-2", "-maxrate", "4M", "-bufsize", "8M"]),
      ...(aCopy ? ["-c:a", "copy"] : ["-c:a", "aac", "-b:a", "128k", "-ac", "2"]),
      "-movflags", "frag_keyframe+empty_moov+default_base_moof",
      "-frag_duration", "1000000", // фрагмент минимум раз в секунду — старт без ожидания ключа
      "-f", "mp4", "pipe:1"
    );
    active++;
    const ff = spawn("ffmpeg", args, { stdio: ["ignore", "pipe", "pipe"] });
    console.log(`transcode: ${item.title || item.fileName} (src v:${codecs && codecs.video} a:${codecs && codecs.audio} → v:${vCopy ? "copy" : "x264"} a:${aCopy ? "copy" : "aac"}${startSec ? " от " + startSec + "с" : ""})`);

    // Chrome-медиастек не играет chunked-потоки без длины — даём огромный фейковый
    // Content-Length (классический приём DLNA/Plex): поток закончится раньше, это ок.
    res.writeHead(200, {
      "Content-Type": "video/mp4",
      "Cache-Control": "no-store",
      "Accept-Ranges": "none",
      "Content-Length": String(64 * 1024 * 1024 * 1024)
    });
    ff.stdout.pipe(res);
    let errBuf = "";
    ff.stderr.on("data", (d) => { errBuf = (errBuf + d).slice(-500); });
    const stop = () => { try { ff.kill("SIGKILL"); } catch (_) {} };
    res.on("close", stop);
    ff.on("close", (code) => {
      active--;
      if (code && !res.writableEnded) console.error("transcode ffmpeg:", errBuf.trim().slice(0, 300));
      try { res.end(); } catch (_) {}
    });
  });
}

module.exports = { ffmpegAvailable, streamTranscode };
