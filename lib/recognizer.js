// Движок распознавания: имя файла -> название фильма/сериала через TMDb.
// 1) чистим имя файла парсером parse-torrent-title (год, качество, кодеки, релиз-группа,
//    season/episode для сериалов);
// 2) ищем в TMDb;
// 3) оцениваем уверенность: matched / ambiguous / unknown.
const ptt = require("parse-torrent-title");

const norm = (s) => String(s || "").toLowerCase().replace(/[^a-zа-я0-9]+/gi, " ").trim();

function tmdbNormalize(type, r) {
  const isTv = type === "series";
  const date = isTv ? r.first_air_date : r.release_date;
  return {
    tmdbId: r.id,
    type,
    title: isTv ? r.name : r.title,
    originalTitle: isTv ? r.original_name : r.original_title,
    year: date ? Number(date.slice(0, 4)) : null,
    poster: r.poster_path || null,
    rating: r.vote_average || 0
  };
}

function hasTmdb(config) {
  const v3 = config.tmdbApiKey && !String(config.tmdbApiKey).includes("REPLACE_ME");
  return Boolean(v3 || config.tmdbBearer);
}

async function tmdbSearch(type, title, year, config) {
  const kind = type === "series" ? "tv" : "movie";
  const lang = config.tmdbLang || "ru-RU";
  let url =
    `https://api.themoviedb.org/3/search/${kind}` +
    `?language=${lang}&include_adult=false&query=${encodeURIComponent(title)}`;
  if (year && type !== "series") url += `&year=${year}`;
  if (year && type === "series") url += `&first_air_date_year=${year}`;

  const opts = {};
  if (config.tmdbBearer) {
    opts.headers = { Authorization: "Bearer " + config.tmdbBearer };
  } else {
    url += `&api_key=${config.tmdbApiKey}`;
  }
  const res = await fetch(url, opts);
  if (!res.ok) throw new Error("TMDb " + res.status);
  const data = await res.json();
  return (data.results || []).map((r) => tmdbNormalize(type, r));
}

// Возвращает запись для devices/{id}/library
async function recognize(fileName, config) {
  const parsed = ptt.parse(fileName);
  const isSeries = parsed.season != null || parsed.episode != null;
  const type = isSeries ? "series" : "movie";
  const title = parsed.title;

  const base = { type, fileName, candidates: [] };

  if (!title) {
    return { ...base, recognized: "unknown", title: fileName };
  }

  // TMDb не настроен — отдаём как "не распознан", не дёргаем сеть.
  if (!hasTmdb(config)) {
    return { ...base, recognized: "unknown", title };
  }

  let results;
  try {
    results = await tmdbSearch(type, title, parsed.year, config);
  } catch (e) {
    console.error("recognizer: TMDb ошибка для", fileName, e.message);
    return { ...base, recognized: "unknown", title };
  }

  if (!results.length) {
    return { ...base, recognized: "unknown", title };
  }

  const top = results[0];
  const exact =
    norm(top.title) === norm(title) ||
    (top.originalTitle && norm(top.originalTitle) === norm(title));
  const yearOk = !parsed.year || !top.year || Math.abs(top.year - parsed.year) <= 1;

  if ((exact && yearOk) || (results.length === 1 && yearOk)) {
    return {
      type, fileName,
      recognized: "matched",
      title: top.title,
      year: top.year,
      poster: top.poster,
      tmdbId: top.tmdbId,
      catalogId: `${type}_${top.tmdbId}`,
      candidates: []
    };
  }

  return {
    type, fileName,
    recognized: "ambiguous",
    title,
    year: parsed.year || null,
    candidates: results.slice(0, 5).map((r) => ({
      tmdbId: r.tmdbId, type, title: r.title, year: r.year, poster: r.poster
    }))
  };
}

module.exports = { recognize };
