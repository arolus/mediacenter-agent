// Cloud mode: Db over FirebaseFirestore. A pass-through — data, threading and errors are exactly
// Firestore's, only the two sentinels are translated on the way in.
package com.mediacenter.tv.agent

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CompletableDeferred
import com.google.firebase.firestore.CollectionReference as FCol
import com.google.firebase.firestore.DocumentChange as FChange
import com.google.firebase.firestore.DocumentReference as FDoc
import com.google.firebase.firestore.DocumentSnapshot as FSnap
import com.google.firebase.firestore.FieldValue as FValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query as FQuery
import com.google.firebase.firestore.QuerySnapshot as FQuerySnap
import com.google.firebase.firestore.SetOptions as FSetOptions

class FireDb(val fs: FirebaseFirestore) : Db {
    override val isLocal = false
    override fun collection(path: String): CollectionReference = FireCol(fs.collection(path))
}

private fun <T, R> Task<T>.op(map: (T) -> R): Op<R> {
    val d = CompletableDeferred<R>()
    addOnSuccessListener { v -> try { d.complete(map(v)) } catch (e: Exception) { d.completeExceptionally(e) } }
    addOnFailureListener { d.completeExceptionally(it) }
    return Op(d)
}

private fun toFire(v: Any?): Any? = when (v) {
    is ServerTimestamp -> FValue.serverTimestamp()
    is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to toFire(x) }
    is List<*> -> v.map { toFire(it) }
    else -> v
}

@Suppress("UNCHECKED_CAST")
private fun fireMap(m: Map<String, Any?>): Map<String, Any?> = toFire(m) as Map<String, Any?>

private open class FireQuery(open val q: FQuery) : Query {
    override fun whereEqualTo(field: String, value: Any?): Query = FireQuery(q.whereEqualTo(field, value))
    override fun get(): Op<QuerySnapshot> = q.get().op { wrapQuery(it) }
    override fun addSnapshotListener(listener: (QuerySnapshot?, Exception?) -> Unit): ListenerRegistration {
        val reg = q.addSnapshotListener { snap, e -> listener(snap?.let { wrapQuery(it) }, e) }
        return ListenerRegistration { reg.remove() }
    }
}

private class FireCol(val c: FCol) : FireQuery(c), CollectionReference {
    override val id: String get() = c.id
    override val path: String get() = c.path
    override fun document(): DocumentReference = FireDocRef(c.document())
    override fun document(id: String): DocumentReference = FireDocRef(c.document(id))
    override fun add(data: Map<String, Any?>): Op<DocumentReference> = c.add(fireMap(data)).op { FireDocRef(it) }
}

private class FireDocRef(val d: FDoc) : DocumentReference {
    override val id: String get() = d.id
    override val path: String get() = d.path
    override fun collection(name: String): CollectionReference = FireCol(d.collection(name))
    override fun get(): Op<DocumentSnapshot> = d.get().op { FireSnap(it) }
    override fun set(data: Map<String, Any?>): Op<Unit> = d.set(fireMap(data)).op { }
    override fun set(data: Map<String, Any?>, options: SetOptions): Op<Unit> =
        (if (options.merge) d.set(fireMap(data), FSetOptions.merge()) else d.set(fireMap(data))).op { }
    override fun update(data: Map<String, Any?>): Op<Unit> = d.update(fireMap(data)).op { }
    override fun update(field: String, value: Any?, vararg more: Any?): Op<Unit> =
        d.update(fireMap(updateArgs(field, value, more))).op { }
    override fun delete(): Op<Unit> = d.delete().op { }
    override fun addSnapshotListener(listener: (DocumentSnapshot?, Exception?) -> Unit): ListenerRegistration {
        val reg = d.addSnapshotListener { snap, e -> listener(snap?.let { FireSnap(it) }, e) }
        return ListenerRegistration { reg.remove() }
    }
}

private class FireSnap(val s: FSnap) : DocumentSnapshot {
    override val id: String get() = s.id
    override val reference: DocumentReference get() = FireDocRef(s.reference)
    override val data: Map<String, Any?>? get() = s.data
    override fun exists(): Boolean = s.exists()
    override fun get(field: String): Any? = s.get(field)
}

private class FireQuerySnap(val s: com.google.firebase.firestore.QueryDocumentSnapshot) : QueryDocumentSnapshot {
    override val id: String get() = s.id
    override val reference: DocumentReference get() = FireDocRef(s.reference)
    override val data: Map<String, Any?> get() = s.data
    override fun exists(): Boolean = true
    override fun get(field: String): Any? = s.get(field)
}

private fun wrapQuery(q: FQuerySnap): QuerySnapshot = QuerySnapshot(
    q.documents.map { FireQuerySnap(it as com.google.firebase.firestore.QueryDocumentSnapshot) },
    q.documentChanges.map { ch ->
        DocumentChange(when (ch.type) {
            FChange.Type.ADDED -> DocumentChange.Type.ADDED
            FChange.Type.MODIFIED -> DocumentChange.Type.MODIFIED
            FChange.Type.REMOVED -> DocumentChange.Type.REMOVED
        }, FireQuerySnap(ch.document))
    })
