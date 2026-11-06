// Small shared helpers: LAN address + HTTP fetch built on HttpURLConnection (no extra deps).
package com.mediacenter.tv.agent

import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL

fun lanIp(): String? {
    return try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) { null }
}

data class FetchResult(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray
)

fun httpFetch(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
    followRedirects: Boolean = true,
    timeoutMs: Int = 30000
): FetchResult {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = method
    c.instanceFollowRedirects = followRedirects
    c.connectTimeout = timeoutMs
    c.readTimeout = timeoutMs
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    if (body != null) {
        c.doOutput = true
        c.outputStream.use { it.write(body) }
    }
    val status = c.responseCode
    val stream = if (status >= 400) c.errorStream else c.inputStream
    val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
    val hdrs = c.headerFields.filterKeys { it != null }.mapKeys { it.key!!.lowercase() }
    c.disconnect()
    return FetchResult(status, hdrs, bytes)
}

// Large files (APKs) straight to disk instead of into memory. Follows redirects by hand:
// HttpURLConnection will not cross between http and https on its own, and mirrors do.
fun httpDownload(url: String, out: java.io.File, timeoutMs: Int = 60000) {
    var u = url
    repeat(6) {
        val c = URL(u).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try {
            val st = c.responseCode
            if (st in 300..399) {
                u = URL(URL(u), c.getHeaderField("Location") ?: throw java.io.IOException("redirect without Location")).toString()
                return@repeat
            }
            if (st != 200) throw java.io.IOException("HTTP $st")
            val tmp = java.io.File(out.path + ".part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
            val len = c.contentLengthLong
            if (len > 0 && tmp.length() != len) { tmp.delete(); throw java.io.IOException("скачано не целиком") }
            if (!tmp.renameTo(out)) throw java.io.IOException("не удалось сохранить файл")
            return
        } finally { c.disconnect() }
    }
    throw java.io.IOException("слишком много перенаправлений")
}
