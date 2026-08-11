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
        val saveDir: File
    )

    fun start() {
        val sp = SettingsPack().listenInterfaces("0.0.0.0:${config.torrentPort}")
        // Privacy: no DHT (transfers must never be announced outside the LAN); local discovery on.
        sp.setEnableDht(false)
        sp.setEnableLsd(true)
        session.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.TORRENT_FINISHED.swig(), AlertType.BLOCK_FINISHED.swig()
            )
            override fun alert(a: Alert<*>) {
                try { handleAlert(a) } catch (e: Exception) { Log.e("torrent alert: ${e.message}") }
            }
        })
        session.start(SessionParams(sp))
        watchTransfers()
        watchDownloads()
        Log.i("torrents: session on port ${config.torrentPort}")
    }

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
        val dest = File(job.saveDir, rel)
        val magnet = ti.makeMagnetUri()
        val infoHash = hash
        jobs.remove(hash)
        // stop seeding IMMEDIATELY (public tracker already announced us)
        try { session.remove(h) } catch (_: Exception) {}
        scope.launch {
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
                if (t["source"] == config.deviceId && status == "requested") startSeed(id, t)
                if (t["target"] == config.deviceId && status == "seeding" && t["magnet"] != null)
                    startTransferDownload(id, t)
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
        val dir = Storage.targetDir(ctx, t["type"] as? String ?: "movie")
        if (hashHex != null) jobs[hashHex] = Job("transfer", id, t, dir)
        session.download(magnet, dir, org.libtorrent4j.swig.torrent_flags_t())
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
                        return@launch
                    }
                    kotlinx.coroutines.delay(1000)
                }
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
            val dir = Storage.targetDir(ctx, t["type"] as? String ?: "movie")
            dir.mkdirs()
            jobs[hash] = Job("download", id, t, dir)
            session.download(ti, dir)
            // never become a meaningful seeder on a public tracker
            session.find(ti.infoHash())?.setUploadLimit(64 * 1024)
            db.collection("downloads").document(id).update("status", "downloading")
            Log.i("download: ${t["title"]} → $dir")
        } catch (e: Exception) {
            Log.e("download start: ${e.message}")
            db.collection("downloads").document(id).update("status", "error", "error", (e.message ?: "?"))
        }
    }
}
