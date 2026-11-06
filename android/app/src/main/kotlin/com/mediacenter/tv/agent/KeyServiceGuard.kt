// Android drops an app's accessibility service from the enabled list when the APK is updated,
// and KeyService (brand remote buttons, opening MediaCenter after power-on) then stays off until
// someone re-enables it over adb. A node that self-updates has nobody to do that, so the agent
// puts it back itself. Needs WRITE_SECURE_SETTINGS, granted once over adb
// (`pm grant com.mediacenter.tv android.permission.WRITE_SECURE_SETTINGS`, survives updates);
// the grant doubles as the opt-in, so TVs without it are left alone.
package com.mediacenter.tv.agent

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

object KeyServiceGuard {
    fun restore(ctx: Context) {
        if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return
        val me = "${ctx.packageName}/com.mediacenter.tv.KeyService"
        try {
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val list = cur.split(':').filter { it.isNotBlank() }
            if (me in list && Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1) return
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (list.filter { it != me } + me).joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i("keyservice: re-enabled after update")
        } catch (e: Exception) { Log.e("keyservice: ${e.message}") }
    }
}
