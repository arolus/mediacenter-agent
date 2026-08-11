// The node agent as a foreground service — the whole reason for the Kotlin rewrite:
// Android keeps a foreground service alive, while Termux (a background app) was killed by
// the phantom-process reaper and could not launch activities at all.
package com.mediacenter.tv.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.util.Base64
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.mediacenter.tv.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var http: HttpServer? = null
    private var torrents: Torrents? = null
    private var relay: RtRelay? = null
    private var commandsSub: com.google.firebase.firestore.ListenerRegistration? = null
    private val observers = mutableListOf<FileObserver>()
    private var scanJob: Job? = null
    private lateinit var config: Config
    private lateinit var db: FirebaseFirestore

    companion object {
        const val CHANNEL = "agent"
        const val NOTIF_ID = 42
        @Volatile var running = false

        // @JvmStatic: MainActivity is still Java and calls AgentService.start(this)
        @JvmStatic
        @JvmOverloads
        fun start(ctx: Context, cfgBase64: String? = null) {
            val i = Intent(ctx, AgentService::class.java)
            if (cfgBase64 != null) i.putExtra("cfg", cfgBase64)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, notification("Запускаюсь…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Provisioning: `--es cfg <base64 json>` writes the node config on first run.
        var reconfigured = false
        intent?.getStringExtra("cfg")?.let { b64 ->
            val text = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            val old = Config.load(this)?.json?.toString()
            if (Config.save(this, text) && old != JSONObject(text).toString()) {
                Log.i("config: обновлён — перезапускаю агента")
                reconfigured = true
            }
        }
        // Смена конфига (например, медиатека переехала на флешку) должна применяться сразу,
        // без переустановки приложения: гасим подписки и поднимаем агента заново.
        if (reconfigured && running) { shutdownParts(); running = false }
        if (!running) { running = true; scope.launch { boot() } }
        return START_STICKY
    }

    private suspend fun boot() {
        val cfg = Config.load(this)
        if (cfg == null) {
            Log.e("config: нет agent-config.json — жду провижена")
            notify("Нет конфигурации ноды")
            running = false
            return
        }
        config = cfg
        Storage.ensureDirs(this)
        try {
            db = Fire.init(this, config)
        } catch (e: Exception) {
            Log.e("firebase: ${e.message}")
            notify("Firebase: ${e.message}")
            running = false
            delay(15000)
            scope.launch { boot() }   // сеть могла ещё не подняться после включения
            return
        }

        val devRef = db.collection("devices").document(config.deviceId)
        val base = hashMapOf<String, Any?>(
            "online" to true,
            "version" to appVersion(),
            "branch" to "android",
            "lanIp" to lanIp(),
            "tvPort" to config.localPort,
            "disk" to disk(),
            "lastSeen" to FieldValue.serverTimestamp()
        )
        val existing = runCatching { devRef.get().await() }.getOrNull()
        if (existing == null || !existing.exists() || existing.getString("name").isNullOrEmpty())
            base["name"] = config.deviceName
        devRef.set(base, SetOptions.merge()).await()
        Log.i("device registered: ${config.deviceName} (${config.deviceId})")

        http = HttpServer(this, db, config, scope,
            onPlay = { item, url, pkg, subtitles, fromStart -> launchPlayer(item, url, pkg, subtitles, fromStart) },
            onOpenUrl = { url -> openUrl(url) },
            onStorageChanged = { rescan() }
        ).also { it.startAll() }

        // Страница в WebView стартует раньше сервера и в этот момент получает ответ из
        // офлайн-кэша (service worker). Сообщаем активности, что агент готов, — она перезагрузит
        // страницу, иначе после обновления APK телевизор крутил бы старый интерфейс.
        sendBroadcast(Intent("com.mediacenter.tv.AGENT_READY").setPackage(packageName))

        torrents = Torrents(this, db, config, scope).also { it.start() }
        relay = RtRelay(this, db, config, scope).also { it.start() }
        commandsSub = Commands.watch(this, db, config, scope) { /* library listener refreshes UI */ }

        rescan()
        watchFolders()
        heartbeat(devRef)
        notify("Нода ${config.deviceName} работает")
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "app"
    } catch (_: Exception) { "app" }

    private fun disk(): Map<String, Long>? = try {
        val st = android.os.StatFs(config.mediaRoot(this).path)
        mapOf("freeBytes" to st.availableBytes, "totalBytes" to st.totalBytes)
    } catch (_: Exception) { null }

    private fun heartbeat(devRef: com.google.firebase.firestore.DocumentReference) {
        scope.launch {
            while (true) {
                try {
                    devRef.set(mapOf(
                        "online" to true, "lanIp" to lanIp(), "disk" to disk(),
                        "lastSeen" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
                    http?.firebaseOk = true
                    http?.lastFirebaseOk = System.currentTimeMillis()
                } catch (e: Exception) {
                    http?.firebaseOk = false
                    Log.e("heartbeat: ${e.message}")
                }
                delay(30_000)
            }
        }
    }

    private fun rescan() {
        scanJob?.cancel()
        scanJob = scope.launch {
            try { Library.sync(this@AgentService, db, config) }
            catch (e: Exception) { Log.e("scan: ${e.message}") }
        }
    }

    // fs.watch equivalent + a periodic sweep, exactly like the Node agent's watcher.js:
    // FileObserver misses nested changes on Android, so the poll is the reliable baseline.
    private fun watchFolders() {
        Storage.scanDirs(this).values.flatten().forEach { dir ->
            dir.mkdirs()
            val obs = object : FileObserver(dir.path, CREATE or DELETE or MOVED_TO or MOVED_FROM or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) { debouncedRescan() }
            }
            obs.startWatching()
            observers.add(obs)
        }
        scope.launch {
            // Тот же цикл сторожит и появление новой флешки: воткнули — папки создаются,
            // медиатека на ней подхватывается сама, без единого нажатия.
            var knownVolumes = Storage.volumes(this@AgentService).map { it.id }.toSet()
            while (true) {
                delay(60_000)
                val now = Storage.volumes(this@AgentService).map { it.id }.toSet()
                if (now != knownVolumes) {
                    Log.i("storage: носители изменились (${knownVolumes.joinToString()} → ${now.joinToString()})")
                    knownVolumes = now
                    Storage.ensureDirs(this@AgentService)
                }
                rescan()
            }
        }
    }

    private var debounceJob: Job? = null
    private fun debouncedRescan() {
        debounceJob?.cancel()
        debounceJob = scope.launch { delay(2000); rescan() }
    }

    // Player and links are launched through MainActivity: a service may not start activities
    // from the background on Android 12+, but the visible activity may.
    private fun launchPlayer(item: JSONObject, url: String, pkg: String, subtitles: String?, fromStart: Boolean) {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("playUrl", url)
            putExtra("playPkg", pkg)
            putExtra("playTitle", item.optString("title"))
            putExtra("playSubtitles", subtitles)
            putExtra("playFromStart", fromStart)
        }
        runCatching { startActivity(i) }.onFailure { Log.e("play: ${it.message}") }
    }

    private fun openUrl(url: String) {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("openUrl", url)
        }
        runCatching { startActivity(i) }.onFailure {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun notification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "MediaCenter", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("MediaCenter")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification(text))
    }

    // Остановка рабочих частей без убийства сервиса — для перезапуска после смены конфига.
    private fun shutdownParts() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        commandsSub?.remove(); commandsSub = null
        relay?.stop(); relay = null
        torrents?.stop(); torrents = null
        http?.stopAll(); http = null
    }

    override fun onDestroy() {
        running = false
        observers.forEach { it.stopWatching() }
        commandsSub?.remove()
        relay?.stop()
        torrents?.stop()
        http?.stopAll()
        runCatching {
            db.collection("devices").document(config.deviceId)
                .set(mapOf("online" to false, "lastSeen" to FieldValue.serverTimestamp()), SetOptions.merge())
        }
        scope.cancel()
        super.onDestroy()
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("boot: starting agent")
            AgentService.start(ctx)
        }
    }
}
