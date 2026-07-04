// MediaCenter TV — тонкая WebView-обёртка над TV-страницей агента (http://127.0.0.1:<port>/).
// Зачем приложение, а не вкладка Chrome: честный fullscreen без белой полосы у выреза
// (shortEdges), жёсткий ландшафт, экран не гаснет (KEEP_SCREEN_ON), своя иконка,
// singleTask — нет дублей вкладок, съедавших пул соединений Chrome.
// Порт агента приходит интентом: am start -n com.mediacenter.tv/.MainActivity --ei port 8088
// (для отладки можно целиком: --es url http://10.0.2.2:8088/). Значение запоминается.
package com.mediacenter.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private WebView web;
    private String url;
    private Thread waiter; // фоновая проба агента, пока он не поднялся

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Телефон висит на HDMI как ТВ-приставка — экран не должен гаснуть.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        // Яркость 30%, пока приложение на экране: телефон меньше греется и жрёт, HDMI-выводу
        // всё равно. Действует только на наше окно — при выходе система вернёт свою яркость.
        lp.screenBrightness = 0.3f;
        // Рисуем под вырезом камеры: без этого система letterbox'ит зону выреза белой полосой.
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().setAttributes(lp);

        url = resolveUrl(getIntent());

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#09090b"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // По суффиксу UA страница понимает, что живёт в приложении (не просит fullscreen и т.п.)
        s.setUserAgentString(s.getUserAgentString() + " MediaCenterTV/1.0");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String target) {
                Uri uri = Uri.parse(target);
                String h = uri.getHost();
                if (h != null && (h.equals("127.0.0.1") || h.equals("localhost"))) return false;
                // Внешние ссылки (трейлер и т.п.) — системе: YouTube-апп или браузер.
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }
            // Деприкейтнутая сигнатура вызывается только для главного фрейма — то, что нужно
            // (новая срабатывает и на субресурсах, картинка 404 не должна ронять страницу).
            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                showWaiting();
            }
        });
        setContentView(web);
        hideSystemUi();
        web.loadUrl(url);
    }

    // Порт/URL из интента; запоминаем, чтобы следующий холодный старт (с ярлыка) шёл туда же.
    private String resolveUrl(Intent i) {
        SharedPreferences p = getSharedPreferences("tv", MODE_PRIVATE);
        String u = i != null ? i.getStringExtra("url") : null;
        if (u == null && i != null) {
            int port = i.getIntExtra("port", 0);
            if (port > 0) u = "http://127.0.0.1:" + port + "/";
        }
        if (u == null) u = p.getString("url", "http://127.0.0.1:8088/");
        p.edit().putString("url", u).apply();
        return u;
    }

    // Повторный am start (агент перезапустился) — singleTask, активити уже жива.
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String next = resolveUrl(i);
        if (!next.equals(url)) { url = next; web.loadUrl(url); }
        // тот же URL — страницу не трогаем: SSE сам дотянет изменения, а reload сбил бы фокус
    }

    // Экран ожидания: агент ещё поднимается (например, сразу после загрузки телефона).
    // Пробуем достучаться фоном и грузим страницу только когда она реально отвечает —
    // без мигания повторными loadUrl по таймеру.
    private void showWaiting() {
        web.loadDataWithBaseURL(url,
            "<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<style>body{margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;" +
            "justify-content:center;background:#09090b;color:#a1a1aa;font-family:sans-serif}" +
            "@keyframes r{to{transform:rotate(360deg)}}" +
            ".s{width:40px;height:40px;border:4px solid rgba(167,139,250,.2);border-top-color:#a78bfa;" +
            "border-radius:50%;animation:r 1s linear infinite;margin-bottom:16px}</style></head>" +
            "<body><div class='s'></div><div>Жду агента…</div></body></html>",
            "text/html", "utf-8", null);
        if (waiter != null && waiter.isAlive()) return;
        waiter = new Thread(() -> {
            while (!isFinishing()) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url + "api/device").openConnection();
                    c.setConnectTimeout(2000);
                    c.setReadTimeout(2000);
                    int rc = c.getResponseCode();
                    c.disconnect();
                    if (rc == 200) {
                        runOnUiThread(() -> web.loadUrl(url));
                        return;
                    }
                } catch (Exception ignored) {}
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        });
        waiter.start();
    }

    // Immersive sticky: система прячет статус-бар и навигацию, жест с края показывает их
    // на пару секунд. Возвращаем после диалогов/VLC (onWindowFocusChanged).
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    // Кнопка «Назад» = история страницы (TV-навигация на History API); с корня — в фон, не выходим.
    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
