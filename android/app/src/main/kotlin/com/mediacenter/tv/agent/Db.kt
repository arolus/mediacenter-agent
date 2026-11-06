// Document-store interface: the exact subset of Firestore the agent uses, so the same agent code
// runs against Firestore (cloud mode, FireDb) or against the on-device store (standalone mode,
// LocalDb). Names mirror Firestore on purpose — call sites read the same as before.
package com.mediacenter.tv.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

interface Db {
    fun collection(path: String): CollectionReference
    // Standalone stores have no server: the LocalBackend plays the Cloud Functions' part.
    val isLocal: Boolean
}

interface Query {
    fun whereEqualTo(field: String, value: Any?): Query
    fun get(): Op<QuerySnapshot>
    fun addSnapshotListener(listener: (QuerySnapshot?, Exception?) -> Unit): ListenerRegistration
}

interface CollectionReference : Query {
    val id: String
    val path: String
    fun document(): DocumentReference
    fun document(id: String): DocumentReference
    fun add(data: Map<String, Any?>): Op<DocumentReference>
}

interface DocumentReference {
    val id: String
    val path: String
    fun collection(name: String): CollectionReference
    fun get(): Op<DocumentSnapshot>
    fun set(data: Map<String, Any?>): Op<Unit>
    fun set(data: Map<String, Any?>, options: SetOptions): Op<Unit>
    fun update(data: Map<String, Any?>): Op<Unit>
    fun update(field: String, value: Any?, vararg more: Any?): Op<Unit>
    fun delete(): Op<Unit>
    fun addSnapshotListener(listener: (DocumentSnapshot?, Exception?) -> Unit): ListenerRegistration
}

interface DocumentSnapshot {
    val id: String
    val reference: DocumentReference
    val data: Map<String, Any?>?
    fun exists(): Boolean = data != null
    fun get(field: String): Any? = data?.get(field)
    fun getString(field: String): String? = get(field) as? String
    fun getBoolean(field: String): Boolean? = get(field) as? Boolean
    fun getLong(field: String): Long? = (get(field) as? Number)?.toLong()
    fun getDouble(field: String): Double? = (get(field) as? Number)?.toDouble()
}

// A query result row always exists, so its data is non-null (as in Firestore).
interface QueryDocumentSnapshot : DocumentSnapshot {
    override val data: Map<String, Any?>
}

class QuerySnapshot(val documents: List<QueryDocumentSnapshot>, val documentChanges: List<DocumentChange>)

class DocumentChange(val type: Type, val document: QueryDocumentSnapshot) {
    enum class Type { ADDED, MODIFIED, REMOVED }
}

fun interface ListenerRegistration { fun remove() }

// Sentinels with Firestore's spelling: FieldValue.serverTimestamp(), SetOptions.merge().
object ServerTimestamp
object FieldValue { fun serverTimestamp(): Any = ServerTimestamp }
class SetOptions private constructor(val merge: Boolean) {
    companion object {
        private val MERGE = SetOptions(true)
        fun merge(): SetOptions = MERGE
    }
}

// A started write/read. Fire-and-forget is fine (failures are swallowed, like an un-awaited
// Firestore Task); await() rethrows them.
class Op<T>(private val d: Deferred<T>) {
    suspend fun await(): T = d.await()

    // Firestore completes a write only when the server confirms it, so without internet an
    // awaited write never returns — although it is already in the local cache (listeners see it)
    // and goes out once the connection is back. For writes nothing else depends on: wait a little
    // to surface errors, then move on. Returns false if the server did not confirm in time.
    suspend fun settle(ms: Long = 3_000): Boolean = withTimeoutOrNull(ms) { d.await(); true } ?: false

    companion object {
        fun <T> done(v: T): Op<T> = Op(CompletableDeferred(v))
        fun <T> failed(e: Throwable): Op<T> = Op(CompletableDeferred<T>().apply { completeExceptionally(e) })
    }
}

// Varargs of update("a", 1, "b", 2) → map, shared by both implementations.
internal fun updateArgs(field: String, value: Any?, more: Array<out Any?>): Map<String, Any?> {
    val m = linkedMapOf(field to value)
    var i = 0
    while (i + 1 < more.size) { m[more[i] as String] = more[i + 1]; i += 2 }
    return m
}
