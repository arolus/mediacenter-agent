// Раскладка медиа по типам в подпапках корня (Android: /storage/emulated/0).
// Тип контента определяется папкой: Movies → movie, Series → series, Cartoons → cartoon.
const fs = require("fs");
const path = require("path");

const SUBDIRS = { movie: "Movies", series: "Series", cartoon: "Cartoons" };

function mediaRoot(config) {
  return config.mediaRoot || config.mediaDir || "/storage/emulated/0";
}

// { movie: <root>/Movies, series: <root>/Series, cartoon: <root>/Cartoons }
function mediaDirs(config) {
  const root = mediaRoot(config);
  const dirs = {};
  for (const [type, sub] of Object.entries(SUBDIRS)) dirs[type] = path.join(root, sub);
  return dirs;
}

function dirForType(config, type) {
  const dirs = mediaDirs(config);
  return dirs[type] || dirs.movie;
}

function allDirs(config) {
  return Object.values(mediaDirs(config));
}

function ensureDirs(config) {
  const dirs = mediaDirs(config);
  for (const d of Object.values(dirs)) {
    try { fs.mkdirSync(d, { recursive: true }); } catch (_) {}
  }
  return dirs;
}

module.exports = { SUBDIRS, mediaRoot, mediaDirs, dirForType, allDirs, ensureDirs };
