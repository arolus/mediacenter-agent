// APK self-update for TVs nobody can reach with adb (standalone installs at friends' homes).
// The agent looks at the latest GitHub release of the public agent repo; a release tagged
// v<versionCode> with an .apk asset newer than the running build is downloaded in the
// background. The TV page then offers "Update" and MainActivity hands the file to Android's
// PackageInstaller — the user confirms on screen, as Android requires for sideloaded apps.
// The package signature must match, so only builds signed with our key can ever install.
// Cloud nodes use it too; there the install runs unattended at a quiet moment (autoInstall).
package com.mediacenter.tv.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

object AppUpdater {
    private const val RELEASES = "https://api.github.com/repos/arolus/mediacenter-agent/releases/latest"
    private const val EVERY_MS = 6 * 3600_000L

    @Volatile var available: JSONObject? = null   // {code, name, path} once downloaded
    // Unattended install was refused or needs a tap: from then on only the TV page offers it.
    @Volatile var needsConfirm = false
    private var autoTried = 0L                    // build code the unattended install ran for
    private const val IDLE_MS = 30 * 60_000L

    fun dir(ctx: Context) = File(ctx.filesDir, "update").apply { mkdirs() }

    fun installedCode(ctx: Context): Long = try {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
    } catch (_: Exception) { 0 }

    fun start(ctx: Context, scope: CoroutineScope) {
        scope.launch {
            delay(2 * 60_000)   // let the TV settle after boot first
            var lastCheck = 0L
            while (true) {
                if (System.currentTimeMillis() - lastCheck >= EVERY_MS) {
                    lastCheck = System.currentTimeMillis()
                    try { check(ctx) } catch (e: Exception) { Log.e("appupdate: ${e.message}") }
                }
                try { autoInstall(ctx) } catch (e: Exception) { Log.e("appupdate: ${e.message}") }
                delay(10 * 60_000)
            }
        }
    }

    // Installing kills the running app, so it waits for a quiet moment: nothing streamed for
    // half an hour and either the screen is off or it is the small hours (boxes set to never
    // sleep keep the screen "on" all day). Once per build; if Android insists on a tap, the
    // TV page shows "Update" instead.
    private fun autoInstall(ctx: Context) {
        val a = available ?: return
        val code = a.optLong("code")
        if (needsConfirm || autoTried == code) return
        val apk = pendingApk(ctx)?.let { File(it) } ?: return
        if (System.currentTimeMillis() - Streams.lastReadAt < IDLE_MS) return
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (pm.isInteractive && hour !in 3..5) return
        if (android.os.Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            Log.e("appupdate: installing apps is not allowed for MediaCenter — update offered on the TV page")
            needsConfirm = true
            return
        }
        autoTried = code
        Log.i("appupdate: installing build $code unattended")
        InstallReceiver.install(ctx, apk, auto = true)
    }

    fun check(ctx: Context): JSONObject? {
        val r = httpFetch(RELEASES, headers = mapOf("Accept" to "application/vnd.github+json"), timeoutMs = 20000)
        if (r.status != 200) throw Exception("GitHub ${r.status}")
        val rel = JSONObject(String(r.body, Charsets.UTF_8))
        val code = Regex("(\\d+)").find(rel.optString("tag_name"))?.value?.toLongOrNull() ?: return null
        val have = installedCode(ctx)
        // Older files from previous updates are of no use any more.
        dir(ctx).listFiles()?.forEach { if (it.name.startsWith("mc-") && !it.name.startsWith("mc-$code.")) it.delete() }
        if (code <= have) { available = null; return null }
        if (available?.optLong("code") != code) needsConfirm = false
        val assets = rel.optJSONArray("assets") ?: return null
        val asset = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
        val out = File(dir(ctx), "mc-$code.apk")
        val size = asset.optLong("size")
        if (!out.isFile || out.length() != size) {
            Log.i("appupdate: скачиваю сборку $code (${size / 1048576} МБ)")
            httpDownload(asset.optString("browser_download_url"), out, 120000)
        }
        available = JSONObject().put("code", code).put("name", rel.optString("name")).put("path", out.path)
        Log.i("appupdate: готова сборка $code (установлена $have)")
        return available
    }

    // VLC for TVs without Play Store: the official build for this CPU from videolan.org.
    fun downloadVlc(ctx: Context): File {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a"
        val base = "https://get.videolan.org/vlc-android/last/"
        val list = String(httpFetch(base, timeoutMs = 20000).body, Charsets.UTF_8)
        val name = Regex("VLC-Android-[0-9.]+-${Regex.escape(abi)}\\.apk").find(list)?.value
            ?: throw Exception("на videolan.org нет сборки VLC для $abi")
        val out = File(dir(ctx), "vlc.apk")
        if (!out.isFile || File(dir(ctx), "vlc.name").let { !it.isFile || it.readText() != name }) {
            Log.i("vlc: скачиваю $name")
            httpDownload(base + name, out, 120000)
            File(dir(ctx), "vlc.name").writeText(name)
        }
        return out
    }

    // For MainActivity: the downloaded APK, if it is still newer than what runs.
    @JvmStatic
    fun pendingApk(ctx: Context): String? {
        val a = available ?: return null
        val f = File(a.optString("path"))
        return if (f.isFile && a.optLong("code") > installedCode(ctx)) f.path else null
    }
}
