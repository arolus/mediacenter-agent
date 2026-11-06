// Installs an APK through PackageInstaller (self-update, VLC) and relays its answers. Android
// asks the user to confirm every sideloaded install: STATUS_PENDING_USER_ACTION carries the
// system dialog, which we open (our activity is on screen, so the start is allowed).
package com.mediacenter.tv.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Unattended update that Android still wants confirmed: opening the dialog from the
                // background is blocked anyway, so leave it to the "Update" button on the TV page.
                if (intent.getBooleanExtra(EXTRA_AUTO, false)) {
                    AppUpdater.needsConfirm = true
                    Log.i("install: Android asks to confirm the update — offered on the TV page")
                    return
                }
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(confirm) } catch (e: Exception) { Log.e("install: ${e.message}") }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("install: готово")
            else -> {
                if (intent.getBooleanExtra(EXTRA_AUTO, false)) AppUpdater.needsConfirm = true
                Log.e("install: статус $st — ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }

    companion object {
        private const val EXTRA_AUTO = "auto"

        // Streams the file into an installer session on a background thread. auto = nobody is
        // at the TV: ask Android to skip the confirmation (allowed for an app updating itself on
        // Android 12+ when its target level is recent enough), never pop a dialog.
        @JvmStatic
        @JvmOverloads
        fun install(ctx: Context, apk: File, auto: Boolean = false) {
            Thread {
                try {
                    val pi = ctx.packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    if (auto && Build.VERSION.SDK_INT >= 31)
                        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    val id = pi.createSession(params)
                    pi.openSession(id).use { s ->
                        s.openWrite("app.apk", 0, apk.length()).use { out ->
                            apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                            s.fsync(out)
                        }
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                        val pending = PendingIntent.getBroadcast(ctx, id,
                            Intent(ctx, InstallReceiver::class.java).putExtra(EXTRA_AUTO, auto), flags)
                        s.commit(pending.intentSender)
                    }
                    Log.i("install: ${apk.name} передан установщику")
                } catch (e: Exception) { Log.e("install ${apk.name}: ${e.message}") }
            }.start()
        }
    }
}
