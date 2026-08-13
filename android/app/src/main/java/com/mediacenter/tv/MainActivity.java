// MediaCenter TV — тонкая WebView-обёртка над TV-страницей агента (http://127.0.0.1:<port>/).
// Зачем приложение, а не вкладка Chrome: честный fullscreen без белой полосы у выреза
// (shortEdges), жёсткий ландшафт, экран не гаснет (KEEP_SCREEN_ON), своя иконка,
// singleTask — нет дублей вкладок, съедавших пул соединений Chrome.
// Порт агента приходит интентом: am start -n com.mediacenter.tv/.MainActivity --ei port 8088
// (для отладки можно целиком: --es url http://10.0.2.2:8088/). Значение запоминается.
package com.mediacenter.tv;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.IntentFilter;
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
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
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
    private BroadcastReceiver agentReady;
    private BroadcastReceiver uiUpdated;
    private boolean reloadedOnAgentReady;
    private String url;
    private Thread waiter; // фоновая проба агента, пока он не поднялся
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Момент последнего РЕАЛЬНОГО ввода (пульт/тач). Отсчёт простоя ведём от него, а НЕ от
    // onResume: иначе ночные onPause/onResume (зарядка, системные события) сбрасывали таймер
    // в ноль и экран горел вечно.
    private long lastInput;
    private DevicePolicyManager dpm;
    private ComponentName admin;
    // Что сейчас играет: VLC при выходе возвращает позицию, и мы отдаём её агенту —
    // из неё живёт лента «Продолжить просмотр» и продолжение с той же секунды.
    private String playingId;
    // Сколько плеер реально играл: VLC свою позицию наружу не отдаёт (см. playVideo), поэтому
    // считаем от момента запуска. Пауза внутри плеера в эту оценку не попадает — она нужна
    // только для подписи «Продолжить с …»; перематывает же VLC сам и точно.
    private long playStartPos;
    private long playStartedAt;
    private static final int REQ_PLAY = 1001;
    private static final int REQ_TREE = 1002;   // системный выбор папки на USB (OTG)

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
        // Права администратора нужны ТОЛЬКО телефону-приставке: погасить AMOLED по таймеру
        // простоя (lockNow). Телевизор и ТВ-бокс гасят экран сами, и системный диалог при
        // каждом запуске выглядел как навязчивый запрос лишних прав. Поэтому спрашиваем
        // ровно один раз за установку; отказ запоминаем и больше не возвращаемся к вопросу.
        SharedPreferences prefs = getSharedPreferences("tv", MODE_PRIVATE);
        if (dpm != null && !dpm.isAdminActive(admin) && !prefs.getBoolean("adminAsked", false)) {
            prefs.edit().putBoolean("adminAsked", true).apply();
            try {
                Intent it = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                it.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
                it.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Нужно, чтобы гасить экран после простоя (телефон-приставка ночью).");
                startActivity(it);
            } catch (Exception ignored) {}
        }

        // Агент живёт в этом же APK (foreground-сервис): поднимаем его при каждом старте
        // приложения. Сервис сам решит, есть ли конфиг и не запущен ли он уже.
        // Провижен ноды приходит сюда же: `am start … --es cfg <base64>` (сам сервис не
        // exported, и запустить его снаружи нельзя — что и правильно).
        String cfg = getIntent() != null ? getIntent().getStringExtra("cfg") : null;
        com.mediacenter.tv.agent.AgentService.start(this, cfg);

        // Телефон-нода: медиатека лежит в общей памяти, а на Android 11+ читать чужие файлы
        // можно только с доступом «Все файлы». Просим до тех пор, пока не выдан: без него
        // агент честно не увидит ни одного фильма. Телевизора и приставки это не касается —
        // у них mediaRoot не указывает на общую память, и запроса не будет.
        if (Build.VERSION.SDK_INT >= 30 && com.mediacenter.tv.agent.Storage.INSTANCE.needsAllFilesAccess(this)) {
            try {
                Intent it = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
                startActivity(it);
            } catch (Exception e) {
                try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                catch (Exception ignored) {}
            }
        }

        url = resolveUrl(getIntent());

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#09090b"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // Страница живёт на самой ноде и меняется с каждым обновлением агента — кэш WebView
        // тут только вредит: после git pull приложение показывало старый app.js.
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        // По суффиксу UA страница понимает, что живёт в приложении (не просит fullscreen и т.п.)
        s.setUserAgentString(s.getUserAgentString() + " MediaCenterTV/1.0");
        // Мост для страницы: MCApp.exitApp() — «Да» в диалоге «Выйти из приложения?»
        web.addJavascriptInterface(new AppBridge(), "MCApp");
        // Ошибки страницы — в logcat (adb logcat -s MCWeb). Без этого JS-исключение выглядит
        // как «кнопка не нажимается», и причину приходится угадывать.
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                android.util.Log.d("MCWeb", m.messageLevel() + " " + m.message()
                        + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
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
        // Агент поднимается через пару секунд после активити: как только его сервер готов,
        // перезагружаем страницу — до этого WebView мог показать её из офлайн-кэша.
        agentReady = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (reloadedOnAgentReady) return;
                reloadedOnAgentReady = true;
                web.post(new Runnable() { @Override public void run() { web.reload(); } });
            }
        };
        registerReceiver(agentReady, new IntentFilter("com.mediacenter.tv.AGENT_READY"),
                Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
        // Интерфейс обновился (WebUpdater скачал новую версию) — перезагружаем страницу.
        // Через SSE это делать НЕЛЬЗЯ: команду понимает только страница, которая уже содержит
        // обработчик, а обновляем мы как раз тех, у кого его ещё нет. Плюс SSE отключается,
        // когда вкладка уходит в фон, — и обновление до неё просто не доезжало.
        uiUpdated = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                web.post(new Runnable() { @Override public void run() { web.reload(); } });
            }
        };
        registerReceiver(uiUpdated, new IntentFilter("com.mediacenter.tv.UI_UPDATED"),
                Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
        syncHomeScreen();
        cacheKioskPin();
        // Пришли по карточке из ленты «Продолжить просмотр» — сразу открываем этот фильм
        handlePlayIntent(getIntent());
        handleAgentIntent(getIntent());
    }

    // Сервис-агент не может запускать активности из фона (Android 12+), поэтому просит нас:
    // playUrl/playPkg — открыть плеер, openUrl — внешнюю ссылку (трейлер в браузере/YouTube).
    private void handleAgentIntent(Intent i) {
        if (i == null) return;
        String playUrl = i.getStringExtra("playUrl");
        if (playUrl != null) {
            i.removeExtra("playUrl");
            new AppBridge().playVideo(playUrl, i.getStringExtra("playPkg"),
                i.getStringExtra("playTitle"), i.getStringExtra("playSubtitles"),
                null, i.getBooleanExtra("playFromStart", false),
                i.getLongExtra("playPosition", 0));
        }
        String open = i.getStringExtra("openUrl");
        if (open != null) { i.removeExtra("openUrl"); new AppBridge().openUrl(open); }
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
        handlePlayIntent(i);
        handleAgentIntent(i);
        // Перепровижен ноды на лету: `am start … --es cfg <base64>` на уже открытое приложение
        String cfg2 = i != null ? i.getStringExtra("cfg") : null;
        if (cfg2 != null) com.mediacenter.tv.agent.AgentService.start(this, cfg2);
        String next = resolveUrl(i);
        if (!next.equals(url)) { url = next; web.loadUrl(url); }
        // тот же URL — страницу не трогаем: SSE сам дотянет изменения, а reload сбил бы фокус
        handleHomeIntent(i);
    }

    // Брендовая кнопка пульта (KeyService) — просим страницу вернуться на экран категорий.
    // Не reload: перезагрузка гасит экран на секунду и теряет уже загруженную библиотеку.
    private void handleHomeIntent(Intent i) {
        if (i == null || !i.getBooleanExtra("goHome", false)) return;
        i.removeExtra("goHome");
        web.evaluateJavascript("window.mcGoHome ? mcGoHome() : false", null);
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
            "#pin{margin-top:18px;font-size:28px;letter-spacing:.4em;color:#a78bfa;height:34px}" +
            "#hint{margin-top:6px;font-size:13px;color:#52525b}" +
            "</style></head>" +
            // Аварийный выход. Если агент не поднимется (а в режиме домашнего экрана уходить
            // больше некуда), приставка не должна запирать пользователя: отсюда всегда можно
            // попасть в системные настройки — пультом, без adb и клавиатуры.
            // Кнопка выхода появляется не сразу: обычный старт агента занимает секунды, и
            // мелькать ею незачем. Через 2 минуты становится ясно, что он не поднимется —
            // тогда даём выход, но по родительскому коду (проверяет MCApp локально).
            "<body><div class='s'></div><div>Жду агента…</div>" +
            "<div id='pin'></div><div id='hint'></div>" +
            "<button id='b' style='display:none'>Открыть настройки Android</button>" +
            "<script>" +
            "var code='',ask=false;" +
            "setTimeout(function(){var b=document.getElementById('b');b.style.display='';b.focus();" +
            "  document.getElementById('hint').textContent='агент не отвечает 2 минуты';},120000);" +
            "document.getElementById('b').onclick=function(){ask=true;" +
            "  document.getElementById('hint').textContent='введите родительский код (цифры на пульте), OK — подтвердить';};" +
            "document.addEventListener('keydown',function(e){ if(!ask) return;" +
            "  if(e.key>='0'&&e.key<='9'){code+=e.key;document.getElementById('pin').textContent='•'.repeat(code.length);}" +
            "  else if(e.key==='Enter'){ if(MCApp.checkPin(code)){MCApp.openSettings();} " +
            "    else {document.getElementById('hint').textContent='неверный код';} code='';" +
            "    document.getElementById('pin').textContent='';}" +
            "  else if(e.key==='Backspace'){code=code.slice(0,-1);document.getElementById('pin').textContent='•'.repeat(code.length);}" +
            "});" +
            "</script></body></html>",
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


    // Код родительского режима держим и локально: экран «Жду агента…» должен уметь
    // проверить его тогда, когда агента как раз и нет.
    private void cacheKioskPin() {
        final String base = url;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + "api/kiosk-pin").openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(3000);
                StringBuilder sb = new StringBuilder();
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                c.disconnect();
                String pin = new org.json.JSONObject(sb.toString()).optString("pin", "");
                getSharedPreferences("tv", MODE_PRIVATE).edit().putString("kioskPin", pin).apply();
            } catch (Exception ignored) {}
        }).start();
    }

    // --- Домашний экран Android TV -------------------------------------------------------------

    // Ленты обновляем в фоне: при старте и после каждого возврата из плеера.
    private void syncHomeScreen() {
        final String base = url;
        new Thread(() -> HomeScreen.sync(getApplicationContext(), base)).start();
    }

    // Карточка из ленты «Продолжить просмотр» открывает нас с id фильма — страница поймёт
    // параметр ?play= и сразу запустит его.
    private void handlePlayIntent(Intent i) {
        String id = i != null ? i.getStringExtra("playId") : null;
        if (id == null || id.isEmpty()) return;
        String target = url + (url.contains("?") ? "&" : "?") + "play=" + Uri.encode(id);
        web.loadUrl(target);
    }

    // VLC вернул позицию остановки — отдаём агенту (он же решит, считать ли фильм досмотренным).
    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_TREE) {
            android.net.Uri tree = data != null ? data.getData() : null;
            if (tree != null) {
                String label = "USB-накопитель";
                try {
                    // В идентификаторе дерева первым идёт UUID тома — им и подписываем накопитель
                    String docId = android.provider.DocumentsContract.getTreeDocumentId(tree);
                    if (docId != null && docId.contains(":")) label = "USB " + docId.split(":")[0];
                } catch (Exception ignored) {}
                com.mediacenter.tv.agent.SafStore.INSTANCE.save(this, tree, label);
                web.reload();
            }
            return;
        }
        if (req != REQ_PLAY || playingId == null) return;
        final String id = playingId;
        playingId = null;
        long pos = data != null ? data.getLongExtra("extra_position", -1) : -1;
        final long position = pos;
        final long duration = data != null ? data.getLongExtra("extra_duration", -1) : -1;
        new Thread(() -> {
            if (position >= 0) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(
                        url + "api/position?id=" + Uri.encode(id) + "&pos=" + position
                        + (duration > 0 ? "&dur=" + duration : "")).openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    c.getResponseCode();
                    c.disconnect();
                } catch (Exception ignored) {}
            }
            HomeScreen.sync(getApplicationContext(), url);
        }).start();
    }

    // Вернулись из плеера — сохраняем, до какой секунды досмотрели. Оценка по времени в
    // плеере: VLC свою позицию наружу не отдаёт (см. playVideo). Нужна она для подписи на
    // кнопке («Продолжить с 00:36»); саму перемотку делает VLC по своей памяти, точно.
    private void reportWatchedSoFar() {
        if (playingId == null || playStartedAt == 0) return;
        final String id = playingId;
        final long pos = playStartPos + (android.os.SystemClock.elapsedRealtime() - playStartedAt);
        playingId = null;
        playStartedAt = 0;
        // Меньше половины минуты — человек просто передумал, отметку не трогаем.
        if (pos < 30_000) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                    url + "api/position?id=" + Uri.encode(id) + "&pos=" + pos).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) {}
            HomeScreen.sync(getApplicationContext(), url);
        }).start();
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
        public void playVideo(final String url, final String pkg, final String title,
                              final String subtitles, final String id, final boolean fromStart,
                              final long positionMs) {
            playingId = id;
            playStartPos = fromStart ? 0 : positionMs;
            playStartedAt = android.os.SystemClock.elapsedRealtime();
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse(url), "video/*");
                // Где остановились, VLC помнит сам — мы лишь говорим, когда нужно начать заново
                // (зритель выбрал «Начать сначала»). ВАЖНО: extra "from_start" VLC 3.7 при
                // ACTION_VIEW ИГНОРИРУЕТ (проверено на телевизоре: фильм всё равно продолжался
                // с сохранённой секунды). А вот "position" (мс) он честно отрабатывает — им и
                // отматываем в ноль; from_start оставляем на случай других плееров.
                // position=0 VLC считает «не задано» и продолжает со своей сохранённой секунды
                // (поймано на сериале: «Начать сначала» молча продолжал). 1 мс — это честный
                // ноль для зрителя, но уже ЗАДАННАЯ позиция, и VLC её отрабатывает всегда.
                if (fromStart) { i.putExtra("from_start", true); i.putExtra("position", 1L); }
                else if (positionMs > 0) i.putExtra("position", positionMs);
                if (title != null && !title.isEmpty()) i.putExtra("title", title);
                // VLC подхватит одноимённый .srt, если агент его нашёл рядом с фильмом
                if (subtitles != null && !subtitles.isEmpty()) i.putExtra("subtitles_location", subtitles);
                // ВАЖНО: у VLC интент ACTION_VIEW принимает «прихожая» StartActivity, которая
                // строит для плеера СВОЙ интент и наши extra выбрасывает — из-за этого в шапке
                // висел мусор из метаданных файла (CP1251), а «начать сначала» не работало.
                // Целимся прямо в плеерную активность (она экспортирована), а если не пустит —
                // откатываемся на обычный выбор по пакету.
                if (pkg != null && !pkg.isEmpty()) {
                    i.setPackage(pkg);
                    if (pkg.startsWith("org.videolan.vlc")) {
                        i.setClassName(pkg, "org.videolan.vlc.gui.video.VideoPlayerActivity");
                    }
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    // ТОЛЬКО обычный startActivity. Пробовали startActivityForResult, чтобы
                    // забрать у VLC "extra_position" (секунду остановки) — VLC при этом вообще
                    // перестаёт открываться, сколько бы флагов ни ставили. Проверено, больше
                    // не пробовать: позицию считаем сами (см. playStartedAt).
                    startActivity(i);
                } catch (Exception e) {
                    // Не пустила плеерная активность — пробуем тот же пакет обычным путём
                    try {
                        Intent p2 = new Intent(i);
                        p2.setComponent(null);
                        if (pkg != null && !pkg.isEmpty()) p2.setPackage(pkg);
                        p2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(p2);
                    } catch (Exception e2) {
                        // нет такого плеера — отдаём системе, пусть предложит чем открыть
                        Intent p3 = new Intent(i);
                        p3.setComponent(null);
                        p3.setPackage(null);
                        p3.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { startActivity(p3); } catch (Exception ignored) {}
                    }
                }
            });
        }

        // Флешку, воткнутую в телефон через OTG, прошивка не отдаёт приложениям как папку —
        // единственный путь к ней лежит через системный выбор папки. Показываем его; дальше
        // разрешение сохраняется навсегда (см. SafStore).
        @JavascriptInterface
        public void pickUsbFolder() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(i, REQ_TREE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Не удалось открыть выбор папки", Toast.LENGTH_LONG).show();
                }
            });
        }

        // Доступ «Все файлы»: без него нода не видит ни медиатеку в общей памяти, ни ту, что
        // лежит в корне флешки. Своего диалога у этого разрешения нет — система открывает
        // отдельный экран настроек, где владелец включает переключатель (так же, как он даётся
        // файловым менеджерам). При первом запуске мы просим сами, а эта кнопка — для случая,
        // когда флешку воткнули позже или в разрешении отказали.
        @JavascriptInterface
        public void requestAllFiles() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT < 30) return;
                try {
                    startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try { startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                    catch (Exception ignored) {
                        Toast.makeText(MainActivity.this,
                            "Не удалось открыть настройки доступа", Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        // Сверка родительского кода без агента — по локальному кэшу (см. cacheKioskPin).
        // Пустой код означает, что родительский режим не настроен: тогда выход свободный.
        @JavascriptInterface
        public boolean checkPin(String entered) {
            String pin = getSharedPreferences("tv", MODE_PRIVATE).getString("kioskPin", "");
            return pin == null || pin.isEmpty() || pin.equals(entered);
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
        reportWatchedSoFar();
        // Вернулись на экран (пробуждение после lockNow, возврат из VLC) — это реальное
        // действие пользователя: экран включён, даём свежий отсчёт. Ложных onResume тут не
        // бывает: пока экран погашен нами, активити не в foreground и onResume не приходит.
        wakeAndSchedule(true);
    }

    @Override
    protected void onDestroy() {
        if (agentReady != null) { try { unregisterReceiver(agentReady); } catch (Exception ignored) {} }
        if (uiUpdated != null) { try { unregisterReceiver(uiUpdated); } catch (Exception ignored) {} }
        web.destroy();
        super.onDestroy();
    }
}
