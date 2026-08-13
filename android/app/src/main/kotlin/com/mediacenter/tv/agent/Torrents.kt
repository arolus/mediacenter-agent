// BitTorrent for both roles, one libtorrent session:
//  - transfers/  : LAN-only P2P between devices (no DHT/trackers, LSD + direct addPeer),
//  - downloads/  : rutracker downloads (server supplies the .torrent; trackers come from it).
// Privacy rules mirror the Node agent: transfers never announce outside the LAN; rutracker
// downloads cap upload at 64 KB/s and the torrent is destroyed the moment it completes.
package com.mediacenter.tv.agent

import android.content.Context
import android.util.Base64
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.swig.settings_pack
import java.io.File

class Torrents(
    private val ctx: Context,
    private val db: FirebaseFirestore,
    private val config: Config,
    private val scope: CoroutineScope
) {
    private val session = SessionManager()
    // infoHash(hex) -> job descriptor
    private val jobs = HashMap<String, Job>()
    private val lastWrite = HashMap<String, Long>()
    private var subs = mutableListOf<ListenerRegistration>()

    data class Job(
        val kind: String,           // "seed" | "transfer" | "download"
        val docId: String,
        val meta: Map<String, Any?>,
        val saveDir: File,
        // Куда переложить по окончании, если качали в буфер (см. Storage.stagingFor).
        val finalDir: File? = null
    )

    fun start() {
        val sp = SettingsPack().listenInterfaces("0.0.0.0:${config.torrentPort}")
        // Privacy: no DHT (transfers must never be announced outside the LAN); local discovery on.
        sp.setEnableDht(false)
        sp.setEnableLsd(true)
        // Скорость: качаем от ОДНОГО пира в своей же сети, поэтому важны не лимиты, а окно
        // запросов; плюс снимаем шифрование протокола — на 32-битном ARM телевизора оно
        // съедает больше, чем даёт (трафик и так не покидает домашнюю сеть).
        val swig = sp.swig()
        val enc = org.libtorrent4j.swig.settings_pack.enc_policy.pe_disabled.swigValue()
        swig.set_int(org.libtorrent4j.swig.settings_pack.int_types.out_enc_policy.swigValue(), enc)
        swig.set_int(org.libtorrent4j.swig.settings_pack.int_types.in_enc_policy.swigValue(), enc)
        swig.set_int(org.libtorrent4j.swig.settings_pack.int_types.connections_limit.swigValue(), 200)
        swig.set_int(org.libtorrent4j.swig.settings_pack.int_types.max_out_request_queue.swigValue(), 1500)
        swig.set_int(org.libtorrent4j.swig.settings_pack.int_types.whole_pieces_threshold.swigValue(), 20)
        swig.set_int(org.libtorrent4j.swig.settings_pack.int_types.request_queue_time.swigValue(), 10)
        swig.set_int(org.libtorrent4j.swig.settings_pack.int_types.send_buffer_watermark.swigValue(), 6 * 1024 * 1024)
        swig.set_int(org.libtorrent4j.swig.settings_pack.int_types.send_buffer_low_watermark.swigValue(), 1024 * 1024)
        swig.set_int(org.libtorrent4j.swig.settings_pack.int_types.max_queued_disk_bytes.swigValue(), 16 * 1024 * 1024)

        session.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.TORRENT_FINISHED.swig(), AlertType.BLOCK_FINISHED.swig(),
                // Диагностика: без этих сообщений сбой выглядит как «висит на нуле» и
                // отличить «не подключились» от «не смогли записать файл» невозможно.
                AlertType.ADD_TORRENT.swig(), AlertType.TORRENT_ERROR.swig(),
                AlertType.FILE_ERROR.swig(), AlertType.METADATA_RECEIVED.swig(),
                AlertType.PEER_CONNECT.swig(), AlertType.PEER_DISCONNECTED.swig()
            )
            override fun alert(a: Alert<*>) {
                try { handleAlert(a) } catch (e: Exception) { Log.e("torrent alert: ${e.message}") }
            }
        })
        session.start(SessionParams(sp))
        watchdog()
        watchTransfers()
        watchDownloads()
        Log.i("torrents: session on port ${config.torrentPort}")
    }

    // Торрент, добавленный уже скачанным (агент перезапустился на середине, файл на диске),
    // НЕ присылает torrent_finished: libtorrent шлёт его только при переходе состояния.
    // Поэтому раз в несколько секунд сами проверяем, не готово ли — иначе задача висела бы
    // в «скачивается» вечно, хотя файл давно на месте.
    private fun watchdog() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000)
                for ((hash, job) in jobs.toList()) {
                    if (job.kind == "seed") continue
                    val h = try { session.find(org.libtorrent4j.Sha1Hash.parseHex(hash)) } catch (_: Exception) { null }
                    if (h != null && h.isValid && h.status().isFinished) finished(h)
                }
            }
        }
    }

    // Идёт ли сейчас приём/раздача: пока да, полный скан медиатеки только мешает
    // (каждый записанный кусок будит FileObserver, а скан ходит в Firestore).
    fun busy(): Boolean = jobs.isNotEmpty()

    fun stop() {
        subs.forEach { it.remove() }
        subs.clear()
        try { session.stop() } catch (_: Exception) {}
    }

    private fun handleAlert(a: Alert<*>) {
        when (a.type()) {
            AlertType.BLOCK_FINISHED -> {
                val h = (a as org.libtorrent4j.alerts.BlockFinishedAlert).handle()
                progress(h)
            }
            AlertType.TORRENT_FINISHED -> {
                val h = (a as TorrentFinishedAlert).handle()
                finished(h)
            }
            AlertType.ADD_TORRENT -> Log.i("torrent: добавлен ${a.message()}")
            AlertType.METADATA_RECEIVED -> Log.i("torrent: метаданные получены")
            AlertType.TORRENT_ERROR, AlertType.FILE_ERROR -> Log.e("torrent: ${a.message()}")
            AlertType.PEER_CONNECT -> Log.i("torrent: пир подключён")
            AlertType.PEER_DISCONNECTED -> Log.i("torrent: пир отвалился — ${a.message()}")
            else -> {}
        }
    }

    private fun progress(h: TorrentHandle) {
        val hash = h.infoHash().toHex()
        val job = jobs[hash] ?: return
        if (job.kind == "seed") return
        val now = System.currentTimeMillis()
        if (now - (lastWrite[hash] ?: 0) < 2000) return
        lastWrite[hash] = now
        val st = h.status()
        val col = if (job.kind == "download") "downloads" else "transfers"
        db.collection(col).document(job.docId).update(
            mapOf(
                "status" to "downloading",
                "progress" to st.progress().toDouble(),
                "speed" to st.downloadRate(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
    }

    private fun finished(h: TorrentHandle) {
        val hash = h.infoHash().toHex()
        val job = jobs[hash] ?: return
        if (job.kind == "seed") return
        val ti = h.torrentFile() ?: return
        // largest video file = the movie
        var best = 0
        for (i in 0 until ti.numFiles()) {
            if (ti.files().fileSize(i) > ti.files().fileSize(best)) best = i
        }
        val rel = ti.files().filePath(best)
        val staged = File(job.saveDir, rel)
        val magnet = ti.makeMagnetUri()
        val infoHash = hash
        val col = if (job.kind == "download") "downloads" else "transfers"
        jobs.remove(hash)
        // stop seeding IMMEDIATELY (public tracker already announced us)
        try { session.remove(h) } catch (_: Exception) {}
        scope.launch {
            // Качали в буфер — перекладываем на носитель. Пока идёт копирование, задача
            // остаётся видимой со своим прогрессом: на медленной флешке это минуты.
            val dest = try {
                job.finalDir?.let { target ->
                    db.collection(col).document(job.docId).update(
                        mapOf("status" to "moving", "progress" to 0.0, "speed" to 0,
                            "updatedAt" to FieldValue.serverTimestamp()))
                    Log.i("staging → ${target.path}: ${staged.name}")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        Storage.moveToTarget(staged, target) { p ->
                            db.collection(col).document(job.docId).update(
                                mapOf("progress" to p, "updatedAt" to FieldValue.serverTimestamp()))
                        }
                    }
                } ?: staged
            } catch (e: Exception) {
                Log.e("staging move: ${e.message}")
                db.collection(col).document(job.docId).update(
                    mapOf("status" to "error", "error" to (e.message ?: "не удалось переложить файл")))
                return@launch
            }
            try {
                if (job.kind == "download") {
                    val t = job.meta
                    val tid = t["tid"] as? String
                    val rutrackerUrl = tid?.let { "https://rutracker.org/forum/viewtopic.php?t=$it" }
                    Library.addFile(ctx, db, config, dest.path, mapOf(
                        "type" to (t["type"] ?: "movie"),
                        "title" to t["title"], "year" to t["year"],
                        "magnet" to magnet, "infoHash" to infoHash,
                        "rutrackerTid" to tid, "rutrackerUrl" to rutrackerUrl
                    ))
                    (t["torrentFile"] as? String)?.let { tf ->
                        db.collection("devices").document(config.deviceId)
                            .collection("torrents").document(Library.libIdFor(dest.path))
                            .set(mapOf("torrentFile" to tf, "magnet" to magnet, "infoHash" to infoHash,
                                "tid" to tid, "rutrackerUrl" to rutrackerUrl,
                                "savedAt" to FieldValue.serverTimestamp())).await()
                    }
                    db.collection("downloads").document(job.docId).delete().await()
                } else {
                    val t = job.meta
                    Library.addFile(ctx, db, config, dest.path, mapOf(
                        "type" to (t["type"] ?: "movie"),
                        "title" to t["title"], "year" to t["year"],
                        "tmdbId" to t["tmdbId"], "catalogId" to t["catalogId"],
                        "poster" to t["poster"], "backdrop" to t["backdrop"],
                        "overview" to t["overview"], "cast" to t["cast"], "rating" to t["rating"]
                    ))
                    db.collection("transfers").document(job.docId).update(
                        mapOf("progress" to 1, "speed" to 0, "status" to "done",
                            "updatedAt" to FieldValue.serverTimestamp())).await()
                }
                Log.i("torrent done: ${dest.name}")
            } catch (e: Exception) { Log.e("torrent finish: ${e.message}") }
        }
    }

    // Задачу убрали из Firestore (кнопка «Остановить» в дашборде) — прекращаем и раздачу,
    // и скачивание: недокачанные куски останутся на диске, но сеть освободится сразу.
    private fun stopByDoc(docId: String) {
        val hashes = jobs.filterValues { it.docId == docId }.keys.toList()
        for (h in hashes) {
            val job = jobs.remove(h) ?: continue
            try {
                val handle = session.find(org.libtorrent4j.Sha1Hash.parseHex(h))
                if (handle != null && handle.isValid) session.remove(handle)
            } catch (e: Exception) { Log.e("stop ${job.kind}: ${e.message}") }
            Log.i("torrent: остановлен ${job.kind} (${job.meta["title"]})")
        }
    }

    // --- transfers/ ---------------------------------------------------------------------------
    private fun watchTransfers() {
        subs.add(db.collection("transfers").addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { ch ->
                val id = ch.document.id
                val t = ch.document.data
                if (ch.type == DocumentChange.Type.REMOVED) { stopByDoc(id); return@forEach }
                val status = t["status"] as? String
                // Источник берётся за задачу не только по свежему запросу: агент мог
                // перезапуститься (обновление, перезагрузка, телевизор выключали) уже посреди
                // переноса — тогда раздавать некому, и приёмник вечно висит на нуле.
                if (t["source"] == config.deviceId &&
                    (status == "requested" || status == "seeding" || status == "downloading")) startSeed(id, t)
                // "downloading" — тоже наш случай: агент мог перезапуститься (обновление,
                // перезагрузка), а перенос продолжается — иначе он навсегда завис бы на месте.
                if (t["target"] == config.deviceId && (status == "seeding" || status == "downloading") &&
                    t["magnet"] != null) startTransferDownload(id, t)
            }
        })
    }

    private fun startSeed(id: String, t: Map<String, Any?>) {
        val fp = t["filePath"] as? String ?: return
        val f = File(fp)
        if (!f.exists()) {
            db.collection("transfers").document(id).update("status", "error", "error", "файл не найден на источнике")
            return
        }
        if (jobs.values.any { it.kind == "seed" && it.docId == id }) return
        scope.launch {
            try {
                val entry = TorrentBuilder().path(f).generate()
                val ti = TorrentInfo(entry.entry().bencode())
                jobs[ti.infoHash().toHex()] = Job("seed", id, t, f.parentFile!!)
                session.download(ti, f.parentFile)
                val addr = "${lanIp() ?: "127.0.0.1"}:${config.torrentPort}"
                db.collection("transfers").document(id).update(
                    mapOf("magnet" to ti.makeMagnetUri(), "seederAddr" to addr,
                        "status" to "seeding", "updatedAt" to FieldValue.serverTimestamp())).await()
                Log.i("transfer: seeding ${t["title"]}")
            } catch (e: Exception) {
                Log.e("transfer seed: ${e.message}")
                db.collection("transfers").document(id).update("status", "error", "error", (e.message ?: "?"))
            }
        }
    }

    private fun startTransferDownload(id: String, t: Map<String, Any?>) {
        val magnet = t["magnet"] as? String ?: return
        val hashHex = Regex("btih:([0-9a-fA-F]{40})").find(magnet)?.groupValues?.get(1)?.lowercase()
        if (hashHex != null && jobs.containsKey(hashHex)) return
        val size = (t["sizeBytes"] as? Number)?.toLong() ?: 0L
        val target = Storage.targetDir(ctx, t["type"] as? String ?: "movie", size)
        // Файл уже лежит на месте целиком (перенос повторили, или он приехал раньше другим
        // путём) — закрываем задачу сразу, не трогая торрент. Мало того что это быстрее:
        // на телефоне libtorrent ПАДАЕТ, когда проверяет готовый файл, и агент уходил в
        // бесконечный перезапуск (поймано на Galaxy S10+, Android 11).
        val name = (t["filePath"] as? String)?.substringAfterLast('/') ?: ""
        if (size > 0 && name.isNotEmpty()) {
            val exists = File(target, name)
            if (exists.isFile && exists.length() == size) {
                Log.i("transfer: ${t["title"]} уже на месте — задача закрыта без торрента")
                scope.launch {
                    try {
                        Library.addFile(ctx, db, config, exists.path, mapOf(
                            "type" to (t["type"] ?: "movie"),
                            "title" to t["title"], "year" to t["year"],
                            "tmdbId" to t["tmdbId"], "catalogId" to t["catalogId"],
                            "poster" to t["poster"], "backdrop" to t["backdrop"],
                            "overview" to t["overview"], "cast" to t["cast"], "rating" to t["rating"]
                        ))
                        db.collection("transfers").document(id).update(
                            mapOf("progress" to 1, "speed" to 0, "status" to "done",
                                "updatedAt" to FieldValue.serverTimestamp())).await()
                    } catch (e: Exception) { Log.e("transfer skip: ${e.message}") }
                }
                return
            }
        }
        val stage = Storage.stagingFor(ctx, target, size)
        val dir = stage ?: target
        if (hashHex != null) jobs[hashHex] = Job("transfer", id, t, dir, if (stage != null) target else null)
        // Куски строго по порядку — запись на носитель становится последовательной. На USB
        // телевизора это принципиально: вразнобой он пишет ~0,7 МБ/с, подряд — ~2,4 МБ/с.
        session.download(magnet, dir, org.libtorrent4j.TorrentFlags.SEQUENTIAL_DOWNLOAD)
        // direct LAN peer — LSD may be slow, the address is authoritative
        (t["seederAddr"] as? String)?.let { addr ->
            scope.launch {
                repeat(30) {
                    val h = hashHex?.let { hx -> session.find(org.libtorrent4j.Sha1Hash.parseHex(hx)) }
                    if (h != null && h.isValid) {
                        val ip = addr.substringBeforeLast(":")
                        val port = addr.substringAfterLast(":").toInt()
                        // The seeder address is authoritative in our own network; LSD alone is slow.
                        try {
                            h.swig().connect_peer(org.libtorrent4j.swig.tcp_endpoint(
                                org.libtorrent4j.swig.address.from_string(ip, org.libtorrent4j.swig.error_code()), port))
                        } catch (e: Exception) { Log.e("addPeer: ${e.message}") }
                        Log.i("transfer: подключаюсь к раздающему $addr")
                        return@launch
                    }
                    kotlinx.coroutines.delay(1000)
                }
                Log.e("transfer: торрент так и не появился в сессии — раздающий $addr не подключён")
            }
        }
        db.collection("transfers").document(id).update("status", "downloading")
        Log.i("transfer: downloading ${t["title"]}")
    }

    // --- downloads/ ---------------------------------------------------------------------------
    private fun watchDownloads() {
        subs.add(db.collection("downloads").addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { ch ->
                val id = ch.document.id
                if (ch.type == DocumentChange.Type.REMOVED) { stopByDoc(id); return@forEach }
                val t = ch.document.data
                val status = t["status"] as? String
                if (t["target"] == config.deviceId && (status == "fetched" || status == "downloading") &&
                    t["torrentFile"] != null) startRutrackerDownload(id, t)
            }
        })
    }

    private fun startRutrackerDownload(id: String, t: Map<String, Any?>) {
        try {
            val buf = Base64.decode(t["torrentFile"] as String, Base64.DEFAULT)
            val ti = TorrentInfo(buf)
            val hash = ti.infoHash().toHex()
            if (jobs.containsKey(hash)) return
            val target = Storage.targetDir(ctx, t["type"] as? String ?: "movie", ti.totalSize())
            val stage = Storage.stagingFor(ctx, target, ti.totalSize())
            val dir = stage ?: target
            dir.mkdirs()
            jobs[hash] = Job("download", id, t, dir, if (stage != null) target else null)
            session.download(ti, dir)
            session.find(ti.infoHash())?.let { h ->
                // never become a meaningful seeder on a public tracker
                h.setUploadLimit(64 * 1024)
                h.setFlags(org.libtorrent4j.TorrentFlags.SEQUENTIAL_DOWNLOAD)
            }
            db.collection("downloads").document(id).update("status", "downloading")
            Log.i("download: ${t["title"]} → $dir")
        } catch (e: Exception) {
            Log.e("download start: ${e.message}")
            db.collection("downloads").document(id).update("status", "error", "error", (e.message ?: "?"))
        }
    }
}
