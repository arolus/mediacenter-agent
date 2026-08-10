// MediaCenter TV — тонкая WebView-обёртка над TV-страницей агента (http://127.0.0.1:<port>/).
// Зачем приложение, а не вкладка Chrome: честный fullscreen без белой полосы у выреза
// (shortEdges), жёсткий ландшафт, экран не гаснет (KEEP_SCREEN_ON), своя иконка,
// singleTask — нет дублей вкладок, съедавших пул соединений Chrome.
// Порт агента приходит интентом: am start -n com.mediacenter.tv/.MainActivity --ei port 8088
// (для отладки можно целиком: --es url http://10.0.2.2:8088/). Значение запоминается.
package com.mediacenter.tv;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    // Таймер неактивности: столько без пульта/тача — гасим экран. Переопределяется intent-extra
    // "idle" (секунды) для теста: am start … --ei idle 60.
    private static final long IDLE_OFF_DEFAULT = 3L * 60 * 60 * 1000;
    private static final float BRIGHTNESS = 0.3f; // рабочая яркость окна (HDMI не трогает)
    private long idleMs = IDLE_OFF_DEFAULT;

    private WebView web;
    private String url;
    private Thread waiter; // фоновая проба агента, пока он не поднялся
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Момент последнего РЕАЛЬНОГО ввода (пульт/тач). Отсчёт простоя ведём от него, а НЕ от
    // onResume: иначе ночные onPause/onResume (зарядка, системные события) сбрасывали таймер
    // в ноль и экран горел вечно.
    private long lastInput;
    private DevicePolicyManager dpm;
    private ComponentName admin;

    // Настоящее гашение экрана. Обычному приложению система не даёт «усыпить» дисплей
    // (яркость 0 — лишь чёрный AMOLED, дисплей включён и не гаснет по таймауту), поэтому
    // используем Device Admin lockNow() — он реально гасит и блокирует. Если админ не
    // активирован — фолбэк: снять KEEP_SCREEN_ON + яркость 0 (хотя бы затемнение).
    private final Runnable idleOff = new Runnable() {
        @Override public void run() {
            if (dpm != null && admin != null && dpm.isAdminActive(admin)) {
                dpm.lockNow();
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setBrightness(0f);
            }
        }
    };

    private void setBrightness(float b) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b;
        getWindow().setAttributes(lp);
    }

    // Экран горит, яркость рабочая; отсчёт гашения — от последнего ввода (lastInput).
    // fromInput=true — это реальный ввод, обновляем lastInput; false (onResume) — только
    // восстановить экран и перепланировать от уже накопленного простоя.
    private void wakeAndSchedule(boolean fromInput) {
        if (fromInput) lastInput = SystemClock.uptimeMillis();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setBrightness(BRIGHTNESS);
        handler.removeCallbacks(idleOff);
        long delay = idleMs - (SystemClock.uptimeMillis() - lastInput);
        handler.postDelayed(idleOff, Math.max(0, delay));
    }

    // dispatch* видят события раньше WebView — считаем их «активностью» и сбрасываем таймер.
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        wakeAndSchedule(true);
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        wakeAndSchedule(true);
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

        // Device Admin для настоящего гашения экрана (lockNow). Если ещё не активирован —
        // просим один раз (пользователь подтверждает; на тесте — adb dpm set-active-admin).
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, AdminReceiver.class);
        if (dpm != null && !dpm.isAdminActive(admin)) {
            try {
                Intent it = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                it.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
                it.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Нужно, чтобы гасить экран после простоя (телефон-приставка ночью).");
                startActivity(it);
            } catch (Exception ignored) {}
        }

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
        // idle-таймаут из intent (секунды) — для теста; иначе 3 часа
        long idleSec = getIntent() != null ? getIntent().getLongExtra("idle", 0) : 0;
        if (idleSec > 0) idleMs = idleSec * 1000;
        wakeAndSchedule(true); // KEEP_SCREEN_ON + рабочая яркость + старт отсчёта неактивности
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
            "border-radius:50%;animation:r 1s linear infinite;margin-bottom:16px}" +
            "button{margin-top:28px;padding:10px 22px;font-size:16px;color:#a1a1aa;background:#18181b;" +
            "border:1px solid #3f3f46;border-radius:12px;outline:none}" +
            "button:focus{color:#fff;border-color:#a78bfa;box-shadow:0 0 0 4px rgba(167,139,250,.25)}" +
            "</style></head>" +
            // Аварийный выход. Если агент не поднимется (а в режиме домашнего экрана уходить
            // больше некуда), приставка не должна запирать пользователя: отсюда всегда можно
            // попасть в системные настройки — пультом, без adb и клавиатуры.
            "<body><div class='s'></div><div>Жду агента…</div>" +
            "<button autofocus onclick='MCApp.openSettings()'>Открыть настройки Android</button>" +
            "<script>document.querySelector('button').focus()</script></body></html>",
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
        // Запуск плеера ДОЛЖЕН идти отсюда, а не из агента. Начиная с Android 12 система режет
        // "background activity launch": Termux сидит в фоне, и его `am start` на VLC отбивается
        // с BAL_BLOCK (проверено на Xiaomi TV Box S, Android 14). Приложение же на переднем плане,
        // ему запускать активности можно. Агент по-прежнему решает ЧТО играть и отдаёт готовый
        // адрес потока — здесь только сам запуск.
        @JavascriptInterface
        public void playVideo(final String url, final String pkg, final String title, final String subtitles) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse(url), "video/*");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (title != null && !title.isEmpty()) i.putExtra("title", title);
                // VLC подхватит одноимённый .srt, если агент его нашёл рядом с фильмом
                if (subtitles != null && !subtitles.isEmpty()) i.putExtra("subtitles_location", subtitles);
                if (pkg != null && !pkg.isEmpty()) i.setPackage(pkg);
                try {
                    startActivity(i);
                } catch (Exception e) {
                    // нет такого плеера — отдаём системе, пусть предложит чем открыть
                    i.setPackage(null);
                    try { startActivity(i); } catch (Exception ignored) {}
                }
            });
        }

        // Аварийный выход в системные настройки — доступен и с экрана «Жду агента…»,
        // чтобы отсутствие агента никогда не запирало приставку.
        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> {
                Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); } catch (Exception ignored) {}
            });
        }

        // Внешняя ссылка (трейлер и т.п.) — по той же причине открывается отсюда.
        @JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); } catch (Exception ignored) {}
            });
        }

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

    // Кнопка «Назад» целиком отдаётся странице (mcHandleBack): она сама закрывает оверлеи,
    // ходит по History API и в корне спрашивает про выход. НЕ используем web.goBack() напрямую —
    // иначе Back проскакивал бы мимо модалок (пикер/диалог) в историю. Страница не отвечает
    // (агент лежит, экран ожидания) → false → сворачиваем в фон.
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.mcHandleBack ? mcHandleBack() : false",
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
        // Вернулись на экран (пробуждение после lockNow, возврат из VLC) — это реальное
        // действие пользователя: экран включён, даём свежий отсчёт. Ложных onResume тут не
        // бывает: пока экран погашен нами, активити не в foreground и onResume не приходит.
        wakeAndSchedule(true);
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
