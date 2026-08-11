// Заставка приставки: вместо стандартного скринсейвера Google показываем постеры своей
// медиатеки. Данные и картинки берём у агента (те же, что идут в ленты домашнего экрана),
// поэтому заставка работает и без интернета — постеры уже лежат в кэше ноды.
package com.mediacenter.tv;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class Screensaver extends DreamService {
    private static final String TAG = "MCDream";
    private static final long SLIDE_MS = 12000;

    private ImageView image;
    private TextView caption;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String[]> slides = new ArrayList<>();   // {posterUrl, title}
    private int idx;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        caption = new TextView(this);
        caption.setTextColor(Color.parseColor("#a1a1aa"));
        caption.setTextSize(20);
        caption.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.bottomMargin = 48;
        root.addView(caption, lp);
        setContentView(root);

        new Thread(this::load).start();
    }

    private void load() {
        SharedPreferences p = getSharedPreferences("tv", MODE_PRIVATE);
        String base = p.getString("url", "http://127.0.0.1:8088/");
        try {
            JSONObject data = new JSONObject(get(base + "api/home-screen"));
            JSONArray arr = data.optJSONArray("channel");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                String poster = it != null ? it.optString("poster") : null;
                if (poster != null && !poster.isEmpty() && !"null".equals(poster)) {
                    int year = it.optInt("year", 0);
                    slides.add(new String[]{ poster, it.optString("title") + (year > 0 ? " (" + year + ")" : "") });
                }
            }
            Collections.shuffle(slides);
        } catch (Exception e) {
            Log.d(TAG, "load: " + e.getMessage());
        }
        if (!slides.isEmpty()) handler.post(this::next);
    }

    private void next() {
        if (slides.isEmpty()) return;
        final String[] slide = slides.get(idx++ % slides.size());
        new Thread(() -> {
            final Bitmap bmp = fetchBitmap(slide[0]);
            handler.post(() -> {
                if (bmp != null) image.setImageBitmap(bmp);
                caption.setText(slide[1]);
            });
        }).start();
        handler.postDelayed(this::next, SLIDE_MS);
    }

    private Bitmap fetchBitmap(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            return BitmapFactory.decodeStream(c.getInputStream());
        } catch (Exception e) {
            Log.d(TAG, "poster: " + e.getMessage());
            return null;
        } finally { if (c != null) c.disconnect(); }
    }

    private String get(String url) throws Exception {
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

    @Override public void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
