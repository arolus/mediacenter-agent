// Разбор имени файла и (по запросу) обогащение метаданными TMDb.
// ВАЖНО: при скане ноды TMDb НЕ дёргаем — только parseName. TMDb используется лишь при
// добавлении с торрента (enrich): постер, описание, актёры.
const ptt = require("parse-torrent-title");

// Только парсинг имени файла (без сети). Тип контента берётся из папки, не отсюда.
function parseName(fileName) {
  const p = ptt.parse(fileName);
  const isSeries = p.season != null || p.episode != null;
  return {
    title: p.title || fileName,
    year: p.year || null,
    season: p.season ?? null,
    episode: p.episode ?? null,
    isSeries
  };
}

function hasTmdb(config) {
  const v3 = config.tmdbApiKey && !String(config.tmdbApiKey).includes("REPLACE_ME");
  return Boolean(v3 || config.tmdbBearer);
}

async function tmdbGet(pathAndQuery, config) {
  const lang = config.tmdbLang || "ru-RU";
  const sep = pathAndQuery.includes("?") ? "&" : "?";
  let url = `https://api.themoviedb.org/3/${pathAndQuery}${sep}language=${lang}`;
  const opts = {};
  if (config.tmdbBearer) opts.headers = { Authorization: "Bearer " + config.tmdbBearer };
  else url += `&api_key=${config.tmdbApiKey}`;
  const res = await fetch(url, opts);
  if (!res.ok) throw new Error("TMDb " + res.status);
  return res.json();
}

// Обогащение метаданными. kind=tv если это сериал/мультсериал (есть season/episode), иначе movie.
// Возвращает { tmdbId, kind, catalogId, title, year, poster, backdrop, overview, cast, rating } или null.
async function enrich({ title, year, isSeries }, config) {
  if (!hasTmdb(config) || !title) return null;
  const kind = isSeries ? "tv" : "movie";
  try {
    const q = `search/${kind}?include_adult=false&query=${encodeURIComponent(title)}` +
      (year ? (kind === "tv" ? `&first_air_date_year=${year}` : `&year=${year}`) : "");
    const found = await tmdbGet(q, config);
    const top = (found.results || [])[0];
    if (!top) return null;

    // Детали + актёрский состав.
    const d = await tmdbGet(`${kind}/${top.id}?append_to_response=credits`, config);
    const date = kind === "tv" ? d.first_air_date : d.release_date;
    return {
      tmdbId: d.id,
      kind,
      catalogId: `${kind}_${d.id}`,
      title: kind === "tv" ? d.name : d.title,
      originalTitle: kind === "tv" ? d.original_name : d.original_title,
      year: date ? Number(date.slice(0, 4)) : (year || null),
      poster: d.poster_path || null,
      backdrop: d.backdrop_path || null,
      overview: d.overview || "",
      rating: d.vote_average || 0,
      cast: (d.credits?.cast || []).slice(0, 8).map((c) => c.name)
    };
  } catch (e) {
    console.error("recognizer.enrich:", e.message);
    return null;
  }
}

module.exports = { parseName, enrich, hasTmdb };
