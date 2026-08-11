// Firebase bootstrap: options come from the node config (no google-services.json), the app
// signs in with the shared service account (agent@mediacenter.local) like every other node.
package com.mediacenter.tv.agent

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

object Fire {
    @Volatile var db: FirebaseFirestore? = null
    @Volatile var uid: String? = null

    suspend fun init(ctx: Context, config: Config): FirebaseFirestore {
        db?.let { return it }
        val f = config.firebase
        val opts = FirebaseOptions.Builder()
            .setProjectId(f.optString("projectId"))
            .setApplicationId(f.optString("appId").ifEmpty { "1:0:android:agent" })
            .setApiKey(f.optString("apiKey"))
            .build()
        val app = try {
            FirebaseApp.initializeApp(ctx.applicationContext, opts, "agent")
        } catch (_: IllegalStateException) {
            FirebaseApp.getInstance("agent")
        }
        val auth = FirebaseAuth.getInstance(app)
        val user = auth.currentUser
            ?: auth.signInWithEmailAndPassword(config.authEmail, config.authPassword).await().user
        uid = user?.uid
        val store = FirebaseFirestore.getInstance(app)
        db = store
        Log.i("firebase: signed in as ${config.authEmail}")
        return store
    }
}

// Tiny logging shim so agent classes don't repeat the tag everywhere.
object Log {
    const val TAG = "MCAgent"
    fun i(msg: String) = android.util.Log.i(TAG, msg)
    fun e(msg: String, t: Throwable? = null) = android.util.Log.e(TAG, msg, t)
}
