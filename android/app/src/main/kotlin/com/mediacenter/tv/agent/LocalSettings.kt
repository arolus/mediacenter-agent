// Standalone-mode settings entered from the phone setup page: the user's own API keys, seeding
// policy and search sources. Kept in app-private storage (other apps cannot read it) and never
// sent back to the page — the setup API only reports whether a secret is set.
package com.mediacenter.tv.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

object LocalSettings {
    private fun file(ctx: Context) = File(ctx.filesDir, "standalone.json")

    @Synchronized
    fun load(ctx: Context): JSONObject =
        try { JSONObject(file(ctx).readText()) } catch (_: Exception) { JSONObject() }

    @Synchronized
    fun update(ctx: Context, patch: (JSONObject) -> Unit): JSONObject {
        val cur = load(ctx)
        patch(cur)
        file(ctx).writeText(cur.toString())
        return cur
    }

    fun tmdbKey(ctx: Context): String = load(ctx).optString("tmdbKey")
    fun omdbKey(ctx: Context): String = load(ctx).optString("omdbKey")

    // Seeding defaults: give back what we took (ratio 1.0) but never longer than a week, and cap
    // upload so the home connection stays usable. 0 = unlimited.
    fun seed(ctx: Context): JSONObject = load(ctx).optJSONObject("seed")
        ?: JSONObject().put("ratio", 1.0).put("days", 7).put("uploadKbps", 2048)

    // The minimum to be useful: titles get recognized. Search sources are optional.
    fun configured(ctx: Context): Boolean = tmdbKey(ctx).isNotEmpty()

    // Gate for the setup API: the TV shows it inside the QR, so only someone who sees the screen
    // can change settings from the LAN.
    fun setupToken(ctx: Context): String {
        val f = File(ctx.filesDir, "setup-token")
        runCatching {
            val t = f.readText().trim()
            if (t.matches(Regex("[a-f0-9]{24}"))) return t
        }
        val b = ByteArray(12)
        java.security.SecureRandom().nextBytes(b)
        val t = b.joinToString("") { "%02x".format(it) }
        f.writeText(t)
        return t
    }
}
