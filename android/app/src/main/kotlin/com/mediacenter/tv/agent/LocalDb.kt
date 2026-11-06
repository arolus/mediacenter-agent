// Standalone mode: the document store lives on the device. All documents are held in memory
// (a TV library is a few thousand docs at most) and written through to SQLite on one background
// thread. Semantics follow Firestore where the agent depends on them: set/merge (deep), update
// fails on a missing doc, whereEqualTo listeners get REMOVED when a doc stops matching, and
// listeners run on the main thread. Bursts of writes are coalesced into one snapshot per listener
// (a library scan writes hundreds of docs; Firestore batches those too).
package com.mediacenter.tv.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class LocalDb(ctx: Context) : Db {
    override val isLocal = true

    private val docs = HashMap<String, Map<String, Any?>>()              // full path -> data
    private val byCol = HashMap<String, LinkedHashSet<String>>()          // collection path -> doc ids
    private val colListeners = mutableListOf<ColListener>()
    private val docListeners = mutableListOf<DocListener>()
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val sql = Helper(ctx).writableDatabase

    init {
        sql.rawQuery("SELECT path, data FROM docs", null).use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0)
                val data = try { fromJson(JSONObject(c.getString(1))) } catch (_: Exception) { continue }
                docs[path] = data
                byCol.getOrPut(path.substringBeforeLast('/')) { LinkedHashSet() }.add(path.substringAfterLast('/'))
            }
        }
        Log.i("localdb: loaded ${docs.size} docs")
    }

    override fun collection(path: String): CollectionReference = LCol(path.trim('/'), null)

    // ---- writes ----------------------------------------------------------------------------
    private fun write(path: String, data: Map<String, Any?>?) {
        val col = path.substringBeforeLast('/')
        val id = path.substringAfterLast('/')
        val before: Map<String, Any?>?
        synchronized(this) {
            before = docs[path]
            if (data == null) {
                docs.remove(path); byCol[col]?.remove(id)
            } else {
                docs[path] = data; byCol.getOrPut(col) { LinkedHashSet() }.add(id)
            }
        }
        writer.execute {
            try {
                if (data == null) sql.delete("docs", "path = ?", arrayOf(path))
                else sql.insertWithOnConflict("docs", null, ContentValues().apply {
                    put("path", path); put("data", toJson(data).toString())
                }, SQLiteDatabase.CONFLICT_REPLACE)
            } catch (e: Exception) { Log.e("localdb: write $path: ${e.message}") }
        }
        dispatch(col, id, before, data)
    }

    private fun dispatch(col: String, id: String, before: Map<String, Any?>?, after: Map<String, Any?>?) {
        val path = "$col/$id"
        val cls = synchronized(colListeners) { colListeners.filter { it.col == col } }
        for (l in cls) {
            val was = before != null && l.matches(before)
            val now = after != null && l.matches(after)
            val type = when {
                !was && now -> DocumentChange.Type.ADDED
                was && now -> DocumentChange.Type.MODIFIED
                was && !now -> DocumentChange.Type.REMOVED
                else -> continue
            }
            l.push(DocumentChange(type, LSnap(path, (after ?: before)!!)))
        }
        val dls = synchronized(docListeners) { docListeners.filter { it.path == path } }
        for (l in dls) main.post { if (l.active) l.cb(LDocSnap(path, after), null) }
    }

    private fun current(path: String): Map<String, Any?>? = synchronized(this) { docs[path] }

    private fun query(col: String, filter: Pair<String, Any?>?): List<QueryDocumentSnapshot> = synchronized(this) {
        (byCol[col] ?: emptySet<String>()).mapNotNull { id ->
            val d = docs["$col/$id"] ?: return@mapNotNull null
            if (filter != null && !eq(d[filter.first], filter.second)) null else LSnap("$col/$id", d)
        }
    }

    // ---- listeners -------------------------------------------------------------------------
    private inner class ColListener(val col: String, val filter: Pair<String, Any?>?,
                                    val cb: (QuerySnapshot?, Exception?) -> Unit) {
        @Volatile var active = true
        private val pending = mutableListOf<DocumentChange>()
        private var scheduled = false

        fun matches(d: Map<String, Any?>) = filter == null || eq(d[filter.first], filter.second)

        fun push(ch: DocumentChange) {
            synchronized(this) {
                pending.add(ch)
                if (scheduled) return
                scheduled = true
            }
            main.post { flush() }
        }

        fun flush() {
            val changes: List<DocumentChange>
            synchronized(this) { changes = pending.toList(); pending.clear(); scheduled = false }
            if (!active || changes.isEmpty()) return
            // Several writes to one doc in a burst collapse to its latest state, like Firestore.
            val last = LinkedHashMap<String, DocumentChange>()
            for (c in changes) {
                val prev = last[c.document.id]
                last[c.document.id] = when {
                    prev == null -> c
                    prev.type == DocumentChange.Type.ADDED && c.type == DocumentChange.Type.REMOVED -> { last.remove(c.document.id); continue }
                    prev.type == DocumentChange.Type.ADDED -> DocumentChange(DocumentChange.Type.ADDED, c.document)
                    else -> c
                }
            }
            try { cb(QuerySnapshot(query(col, filter), last.values.toList()), null) }
            catch (e: Exception) { Log.e("localdb listener $col: ${e.message}") }
        }
    }

    private class DocListener(val path: String, val cb: (DocumentSnapshot?, Exception?) -> Unit) {
        @Volatile var active = true
    }

    // ---- references ------------------------------------------------------------------------
    private inner class LCol(override val path: String, val filter: Pair<String, Any?>?) : CollectionReference {
        override val id: String get() = path.substringAfterLast('/')
        override fun whereEqualTo(field: String, value: Any?): Query = LCol(path, field to norm(value))
        override fun get(): Op<QuerySnapshot> = Op.done(QuerySnapshot(query(path, filter), emptyList()))
        override fun document(): DocumentReference = LDoc("$path/${newId()}")
        override fun document(id: String): DocumentReference = LDoc("$path/$id")
        override fun add(data: Map<String, Any?>): Op<DocumentReference> {
            val ref = LDoc("$path/${newId()}")
            write(ref.path, normMap(data))
            return Op.done(ref)
        }
        override fun addSnapshotListener(listener: (QuerySnapshot?, Exception?) -> Unit): ListenerRegistration {
            val l = ColListener(path, filter, listener)
            synchronized(colListeners) { colListeners.add(l) }
            main.post {
                if (!l.active) return@post
                val all = query(path, filter)
                try { listener(QuerySnapshot(all, all.map { DocumentChange(DocumentChange.Type.ADDED, it) }), null) }
                catch (e: Exception) { Log.e("localdb listener $path: ${e.message}") }
            }
            return ListenerRegistration { l.active = false; synchronized(colListeners) { colListeners.remove(l) } }
        }
    }

    private inner class LDoc(override val path: String) : DocumentReference {
        override val id: String get() = path.substringAfterLast('/')
        override fun collection(name: String): CollectionReference = LCol("$path/$name", null)
        override fun get(): Op<DocumentSnapshot> = Op.done(LDocSnap(path, current(path)))
        override fun set(data: Map<String, Any?>): Op<Unit> { write(path, normMap(data)); return Op.done(Unit) }
        override fun set(data: Map<String, Any?>, options: SetOptions): Op<Unit> {
            if (!options.merge) return set(data)
            synchronized(this@LocalDb) { write(path, deepMerge(current(path) ?: emptyMap(), normMap(data))) }
            return Op.done(Unit)
        }
        override fun update(data: Map<String, Any?>): Op<Unit> {
            synchronized(this@LocalDb) {
                val cur = current(path) ?: return Op.failed(Exception("NOT_FOUND: $path"))
                write(path, cur + normMap(data))
            }
            return Op.done(Unit)
        }
        override fun update(field: String, value: Any?, vararg more: Any?): Op<Unit> =
            update(updateArgs(field, value, more))
        override fun delete(): Op<Unit> {
            synchronized(this@LocalDb) { if (current(path) != null) write(path, null) }
            return Op.done(Unit)
        }
        override fun addSnapshotListener(listener: (DocumentSnapshot?, Exception?) -> Unit): ListenerRegistration {
            val l = DocListener(path, listener)
            synchronized(docListeners) { docListeners.add(l) }
            main.post { if (l.active) listener(LDocSnap(path, current(path)), null) }
            return ListenerRegistration { l.active = false; synchronized(docListeners) { docListeners.remove(l) } }
        }
    }

    private inner class LDocSnap(val path: String, override val data: Map<String, Any?>?) : DocumentSnapshot {
        override val id: String get() = path.substringAfterLast('/')
        override val reference: DocumentReference get() = LDoc(path)
    }

    private inner class LSnap(val path: String, override val data: Map<String, Any?>) : QueryDocumentSnapshot {
        override val id: String get() = path.substringAfterLast('/')
        override val reference: DocumentReference get() = LDoc(path)
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "localdb.sqlite", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE docs (path TEXT PRIMARY KEY, data TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    companion object {
        // One store per process: the agent restarts its parts on reconfiguration, the data stays.
        @Volatile private var instance: LocalDb? = null
        fun get(ctx: Context): LocalDb = instance ?: synchronized(this) {
            instance ?: LocalDb(ctx.applicationContext).also { instance = it }
        }

        private val ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val rnd = java.security.SecureRandom()
        fun newId(): String = (1..20).map { ID_CHARS[rnd.nextInt(ID_CHARS.length)] }.joinToString("")

        // Stored values are plain JSON types; numbers normalise to Long/Double as Firestore returns
        // them, so `as? Long` and `as? Number` behave the same in both modes.
        fun norm(v: Any?): Any? = when (v) {
            null -> null
            is ServerTimestamp -> System.currentTimeMillis()
            is Int -> v.toLong()
            is Short -> v.toLong()
            is Byte -> v.toLong()
            is Float -> v.toDouble()
            is Long, is Double, is String, is Boolean -> v
            is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to norm(x) }
            is List<*> -> v.map { norm(it) }
            is Array<*> -> v.map { norm(it) }
            is JSONObject -> fromJson(v)
            is JSONArray -> fromJsonArray(v)
            JSONObject.NULL -> null
            else -> v.toString()
        }

        @Suppress("UNCHECKED_CAST")
        fun normMap(m: Map<String, Any?>): Map<String, Any?> = norm(m) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        private fun deepMerge(base: Map<String, Any?>, patch: Map<String, Any?>): Map<String, Any?> {
            val out = LinkedHashMap(base)
            for ((k, v) in patch) {
                val b = out[k]
                out[k] = if (v is Map<*, *> && b is Map<*, *>)
                    deepMerge(b as Map<String, Any?>, v as Map<String, Any?>) else v
            }
            return out
        }

        private fun eq(a: Any?, b: Any?): Boolean =
            if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

        fun toJson(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
            is List<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
            else -> v
        }

        fun fromJson(o: JSONObject): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            for (k in o.keys()) m[k] = fromJsonValue(o.opt(k))
            return m
        }

        private fun fromJsonArray(a: JSONArray): List<Any?> = (0 until a.length()).map { fromJsonValue(a.opt(it)) }

        private fun fromJsonValue(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> fromJson(v)
            is JSONArray -> fromJsonArray(v)
            is Double -> v
            is Float -> v.toDouble()
            is Number -> v.toLong()
            else -> v
        }
    }
}
