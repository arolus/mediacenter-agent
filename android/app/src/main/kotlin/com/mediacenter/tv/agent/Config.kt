// Node config: same JSON shape as the Node.js agent's agent-config.json, stored in the app's
// private files dir. Provisioned once via intent extra (adb/QR flow): `--es cfg <base64-json>`.
package com.mediacenter.tv.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

class Config(val json: JSONObject) {
    val deviceId: String get() = json.optJSONObject("device")?.optString("id") ?: "node"
    val deviceName: String get() = json.optJSONObject("device")?.optString("name") ?: deviceId
    val authEmail: String get() = json.optJSONObject("auth")?.optString("email") ?: ""
    val authPassword: String get() = json.optJSONObject("auth")?.optString("password") ?: ""
    val firebase: JSONObject get() = json.optJSONObject("firebase") ?: JSONObject()
    val localPort: Int get() = json.optInt("localPort", 8088)
    val torrentPort: Int get() = json.optInt("torrentPort", 51413)
    val playerPackage: String get() = json.optJSONObject("player")?.optString("package")?.ifEmpty { null } ?: "org.videolan.vlc"

    // Media root:
    //   "usb"   — the app's own directory ON THE REMOVABLE VOLUME. Android 11+ forbids writing
    //             anywhere else on a USB stick (no permission helps, short of MANAGE_EXTERNAL_
    //             STORAGE), while this path needs no permission at all and survives reboots.
    //   <path>  — explicit path, e.g. /storage/emulated/0 on the TV box.
    //   empty   — app-private storage inside the device.
    fun mediaRoot(ctx: Context): File {
        val v = json.optString("mediaRoot", "")
        if (v == "usb") return usbDir(ctx) ?: File(ctx.filesDir, "media")
        return if (v.isNotEmpty() && !v.contains("REPLACE_ME")) File(v) else File(ctx.filesDir, "media")
    }

    // Первый съёмный том из getExternalFilesDirs: индекс 0 — встроенная память, дальше — USB/SD.
    fun usbDir(ctx: Context): File? =
        ctx.getExternalFilesDirs(null)
            .filterNotNull()
            .firstOrNull { d ->
                val p = d.absolutePath
                p.startsWith("/storage/") && !p.contains("/emulated/") &&
                    android.os.Environment.getExternalStorageState(d) == android.os.Environment.MEDIA_MOUNTED
            }
            ?.let { File(it, "media") }

    fun mediaDirs(ctx: Context): Map<String, File> {
        val root = mediaRoot(ctx)
        return mapOf(
            "movie" to File(root, "Movies"),
            "series" to File(root, "Series"),
            "cartoon" to File(root, "Cartoons")
        )
    }

    fun dirForType(ctx: Context, type: String): File =
        mediaDirs(ctx)[type] ?: mediaDirs(ctx)["movie"]!!

    fun ensureDirs(ctx: Context) {
        mediaDirs(ctx).values.forEach { it.mkdirs() }
    }

    companion object {
        private fun file(ctx: Context) = File(ctx.filesDir, "agent-config.json")

        @JvmStatic
        fun load(ctx: Context): Config? = try {
            Config(JSONObject(file(ctx).readText()))
        } catch (_: Exception) { null }

        fun save(ctx: Context, jsonText: String): Boolean = try {
            JSONObject(jsonText) // validate before writing
            file(ctx).writeText(jsonText)
            true
        } catch (_: Exception) { false }

        // QR enrollment for a fresh node (no config yet): a random token shown in the QR and
        // polled against the `enroll` Cloud Function. Kept on disk so reboots don't change
        // the QR mid-registration. The project id is hardcoded — there is no config to read
        // it from yet, and it is public anyway (it's in every dashboard URL).
        const val ENROLL_PROJECT = "mediacenter-49c3c"

        @JvmStatic
        fun enrollToken(ctx: Context): String {
            val f = File(ctx.filesDir, "enroll-token")
            runCatching {
                val t = f.readText().trim()
                if (t.matches(Regex("[a-f0-9]{32}"))) return t
            }
            val b = ByteArray(16)
            java.security.SecureRandom().nextBytes(b)
            val t = b.joinToString("") { "%02x".format(it) }
            f.writeText(t)
            return t
        }

        fun clearEnrollToken(ctx: Context) { File(ctx.filesDir, "enroll-token").delete() }
    }
}
