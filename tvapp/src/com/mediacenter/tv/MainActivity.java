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
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    // Таймер неактивности: 3 часа без пульта/тача — гасим экран (см. resetIdle/idleOff).
    private static final long IDLE_OFF_MS = 3L * 60 * 60 * 1000;
    private static final float BRIGHTNESS = 0.3f; // рабочая яркость окна (HDMI не трогает)

    private WebView web;
    private String url;
    private Thread waiter; // фоновая проба агента, пока он не поднялся
    private final Handler handler = new Handler(Looper.getMainLooper());

    // «Выключение» экрана без спецправ: снимаем KEEP_SCREEN_ON (дальше системный таймаут
    // погасит экран сам) и роняем яркость окна в 0 — на OLED тёмный UI = погасшие пиксели,
    // так что темнеет сразу, не дожидаясь системного таймаута. Любая кнопка/тач будит.
    private final Runnable idleOff = new Runnable() {
        @Override public void run() {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setBrightness(0f);
        }
    };

    private void setBrightness(float b) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b;
        getWindow().setAttributes(lp);
    }

    // Любая активность пользователя: экран держим, яркость рабочая, отсчёт 3ч заново.
    private void resetIdle() {
        handler.removeCallbacks(idleOff);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setBrightness(BRIGHTNESS);
        handler.postDelayed(idleOff, IDLE_OFF_MS);
    }

    // dispatch* видят события раньше WebView — считаем их «активностью» и сбрасываем таймер.
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        resetIdle();
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        resetIdle();
        return super.dispatchTouchEvent(e);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
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
        // Мост для страницы: MCApp.exitApp() — «Да» в диалоге «Выйти из приложения?»
        web.addJavascriptInterface(new AppBridge(), "MCApp");
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
        resetIdle(); // KEEP_SCREEN_ON + рабочая яркость + старт отсчёта неактивности
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

    // Мост из WebView: полный выход из приложения (закрыть задачу и убить процесс).
    private class AppBridge {
        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> {
                finishAndRemoveTask();
                // Добиваем процесс — «закрыть и убить». Именно через handler активити:
                // web.postDelayed после destroy() WebView не выполняется, процесс оставался.
                handler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
            });
        }
    }

    // Кнопка «Назад» = история страницы (TV-навигация на History API); в корне (категории)
    // страница показывает диалог «Выйти из приложения?» (mcConfirmExit); если страница
    // не отвечает (агент лежит, экран ожидания) — старое поведение: в фон.
    @Override
    public void onBackPressed() {
        if (web.canGoBack()) { web.goBack(); return; }
        web.evaluateJavascript("window.mcConfirmExit ? mcConfirmExit() : false",
            (v) -> { if (!"true".equals(v)) moveTaskToBack(true); });
    }

    @Override
    protected void onPause() {
        // Мы не на экране (VLC, лончер…) — таймер не наш: чужие окна сами держат экран.
        handler.removeCallbacks(idleOff);
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        resetIdle(); // вернулись (например, после фильма в VLC) — экран включён, отсчёт заново
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
