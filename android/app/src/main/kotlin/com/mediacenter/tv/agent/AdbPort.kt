// Порт беспроводной отладки ноды — чтобы не диктовать его руками после каждой перезагрузки.
//
// Android 11+ выдаёт этот порт случайным при каждом включении отладки. Прямо прочитать его
// приложением не вышло: системное свойство service.adb.tls.port прошивка Samsung не отдаёт
// (возвращается пусто). Зато сам Android объявляет отладку в локальной сети по mDNS —
// служба `_adb-tls-connect._tcp`. Её и слушаем: находим запись со своим IP и берём порт.
//
// Результат кладётся в devices/{id}.adbPort вместе с пульсом.
package com.mediacenter.tv.agent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AdbPort {
    private const val SERVICE = "_adb-tls-connect._tcp"

    @Volatile var port: Int = 0
        private set

    fun start(ctx: Context, scope: CoroutineScope) {
        scope.launch {
            while (true) {
                // Сперва дешёвый путь: вдруг прошивка всё же отдаёт свойство.
                prop()?.let { if (it > 0) port = it }
                if (port == 0) discover(ctx)
                // Порт живёт до перезагрузки; проверяем раз в десять минут — этого хватает,
                // а эфир лишними запросами не забиваем.
                delay(10 * 60 * 1000)
            }
        }
    }

    private fun prop(): Int? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, "service.adb.tls.port", "") as? String)?.toIntOrNull()
    } catch (_: Exception) { null }

    private suspend fun discover(ctx: Context) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val mine = lanIp()
        var listener: NsdManager.DiscoveryListener? = null
        try {
            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, code: Int) {}
                override fun onStopDiscoveryFailed(type: String, code: Int) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    // Резолвим каждую найденную запись: адрес и порт приходят только здесь.
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            val ip = i.host?.hostAddress ?: return
                            // В сети могут объявляться и другие устройства с отладкой —
                            // берём только запись со своим адресом.
                            if (mine != null && ip == mine && i.port > 0) {
                                port = i.port
                                Log.i("adb: порт беспроводной отладки — ${i.port}")
                            }
                        }
                    })
                }
            }
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
            delay(8000)   // объявления прилетают за пару секунд
        } catch (e: Exception) {
            Log.e("adb: поиск порта не удался: ${e.message}")
        } finally {
            try { listener?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
        }
    }
}
