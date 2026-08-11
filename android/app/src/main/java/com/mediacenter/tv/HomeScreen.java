// Ленты Android TV на домашнем экране: «Продолжить просмотр» (Watch Next) и собственный
// канал «MediaCenter» с новинками медиатеки.
//
// Данные берём у агента (/api/home-screen) — он один знает медиатеку и позиции просмотра.
// Постеры отдаются по LAN-адресу ноды, а не по 127.0.0.1: картинки скачивает системный
// провайдер Android в своём процессе, для которого localhost — он сам.
//
// Пишем через TvContract напрямую (без androidx.tvprovider): в проекте нет Gradle и внешних
// зависимостей, а нужные колонки доступны с API 26.
package com.mediacenter.tv;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressLint("RestrictedApi")
public class HomeScreen {
    private static final String TAG = "MCHome";
    private static final String CHANNEL_ID = "mediacenter-new";   // наш internal id канала

    // Синхронизация целиком: тянем данные и перекладываем их в системные ленты.
    // Зовётся из фонового потока.
    static void sync(Context ctx, String baseUrl) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;   // TvContract preview — с API 26
        try {
            JSONObject data = new JSONObject(get(baseUrl + "api/home-screen"));
            syncWatchNext(ctx, data.optJSONArray("continueWatching"), baseUrl);
            syncChannel(ctx, data.optJSONArray("channel"), baseUrl);
        } catch (Exception e) {
            Log.d(TAG, "sync failed: " + e.getMessage());
        }
    }

    // --- «Продолжить просмотр» ---------------------------------------------------------------
    private static void syncWatchNext(Context ctx, JSONArray items, String baseUrl) {
        if (items == null) return;
        ContentResolver cr = ctx.getContentResolver();
        // Перестраиваем свои записи целиком: их единицы, дельту считать незачем. Удалять
        // приходится по одной — провайдер отвергает delete с условием ("Selection not allowed"),
        // а видим мы через query только собственные строки.
        deleteAll(cr, TvContract.WatchNextPrograms.CONTENT_URI);
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            ContentValues v = new ContentValues();
            v.put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, it.optString("id"));
            v.put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_MOVIE);
            v.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                    TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
            v.put(TvContract.WatchNextPrograms.COLUMN_TITLE, title(it));
            v.put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, it.optString("description"));
            v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, it.optString("poster"));
            v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                    TvContract.WatchNextPrograms.ASPECT_RATIO_2_3);
            v.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, playIntentUri(ctx, it.optString("id")));
            v.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, System.currentTimeMillis());
            // Прогресс не показываем: точную секунду знает только сам плеер, а врать полоской
            // на карточке хуже, чем не рисовать её вовсе.
            long dur = it.optLong("durationMs");
            if (dur > 0) v.put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, (int) dur);
            try { cr.insert(TvContract.WatchNextPrograms.CONTENT_URI, v); }
            catch (Exception e) { Log.d(TAG, "watchNext insert: " + e.getMessage()); }
        }
        Log.d(TAG, "watch next: " + items.length() + " шт.");
    }

    // --- Свой ряд на домашнем экране ----------------------------------------------------------
    private static void syncChannel(Context ctx, JSONArray items, String baseUrl) {
        if (items == null) return;
        ContentResolver cr = ctx.getContentResolver();
        long channelId = findOrCreateChannel(ctx);
        if (channelId < 0) return;

        Uri progUri = TvContract.PreviewPrograms.CONTENT_URI;
        deleteAll(cr, TvContract.buildPreviewProgramsUriForChannel(channelId));
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            ContentValues v = new ContentValues();
            v.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId);
            v.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, it.optString("id"));
            v.put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_MOVIE);
            v.put(TvContract.PreviewPrograms.COLUMN_TITLE, title(it));
            v.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, it.optString("description"));
            v.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, it.optString("poster"));
            v.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                    TvContract.PreviewPrograms.ASPECT_RATIO_2_3);
            v.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, playIntentUri(ctx, it.optString("id")));
            long dur = it.optLong("durationMs");
            if (dur > 0) v.put(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, (int) dur);
            try { cr.insert(progUri, v); }
            catch (Exception e) { Log.d(TAG, "preview insert: " + e.getMessage()); }
        }
        Log.d(TAG, "channel: " + items.length() + " шт.");
    }

    // Провайдер TV не принимает delete с selection — забираем свои _ID и сносим поштучно.
    private static void deleteAll(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{ TvContract.WatchNextPrograms._ID }, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                try { cr.delete(ContentUris.withAppendedId(uri, c.getLong(0)), null, null); }
                catch (Exception ignored) {}
            }
        } catch (Exception e) { Log.d(TAG, "cleanup: " + e.getMessage()); }
    }

    // Канал создаём один раз и запоминаем по INTERNAL_PROVIDER_ID. Показывать его в ленте или
    // нет — решает пользователь (система спросит при первом запросе), поэтому просто просим.
    private static long findOrCreateChannel(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        try (Cursor c = cr.query(TvContract.Channels.CONTENT_URI,
                new String[]{ TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID },
                null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    if (CHANNEL_ID.equals(c.getString(1))) return c.getLong(0);
                }
            }
        } catch (Exception e) { Log.d(TAG, "channels query: " + e.getMessage()); }

        ContentValues v = new ContentValues();
        v.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW);
        v.put(TvContract.Channels.COLUMN_DISPLAY_NAME, "MediaCenter");
        v.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, CHANNEL_ID);
        v.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, playIntentUri(ctx, ""));
        try {
            Uri uri = cr.insert(TvContract.Channels.CONTENT_URI, v);
            if (uri == null) return -1;
            long id = ContentUris.parseId(uri);
            TvContract.requestChannelBrowsable(ctx, id);   // системный диалог «показывать ряд?»
            return id;
        } catch (Exception e) {
            Log.d(TAG, "channel create: " + e.getMessage());
            return -1;
        }
    }

    // Клик по карточке возвращает в наше приложение с id фильма — оно откроет его и запустит.
    private static String playIntentUri(Context ctx, String id) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (id != null && !id.isEmpty()) i.putExtra("playId", id);
        return i.toUri(Intent.URI_INTENT_SCHEME);
    }

    private static String title(JSONObject it) {
        String t = it.optString("title");
        int year = it.optInt("year", 0);
        return year > 0 ? t + " (" + year + ")" : t;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } finally { c.disconnect(); }
        return sb.toString();
    }
}
