// Headless Cloudflare clearance for phone nodes.
//
// ClearanceActivity needs to be on screen: Android 12+ blocks its start from the service (BAL)
// and pauses it the moment the user opens anything else — the challenge JS then freezes and
// the harvest times out. A WebView that is never attached to a window has none of that: it
// lives in the service process, loads the tracker, runs Cloudflare's script and hands over the
// httpOnly cookie via CookieManager whatever the phone is showing (or not showing).
// TVs keep using the activity: their UI is on screen anyway and the interactive Turnstile
// there is tapped on a real view.
package com.mediacenter.tv.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

class HeadlessClearance(private val ctx: Context, private val probeUrl: String = RtRelay.DEFAULT_PROBE) {
    private val origin = RtRelay.originOf(probeUrl)

    companion object {
        private const val POLL_MS = 1500L
        private const val AUTO_TAPS_MAX = 8
        private const val AUTO_TAP_EVERY_MS = 6_000L
        private const val WIDTH = 1280
        private const val HEIGHT = 720
    }

    private val main = Handler(Looper.getMainLooper())

    // Runs on the main thread (WebView demands it); reports exactly once.
    fun harvest(fresh: Boolean, timeoutMs: Long, done: (cookie: String?, ua: String?, error: String?) -> Unit) {
        main.post {
            var web: WebView? = null
            var reported = false
            fun finish(cookie: String?, ua: String?, error: String?) {
                if (reported) return
                reported = true
                main.removeCallbacksAndMessages(null)
                try { web?.stopLoading(); web?.destroy() } catch (_: Exception) {}
                web = null
                done(cookie, ua, error)
            }
            try {
                val w = WebView(ctx)
                web = w
                val s = w.settings
                s.javaScriptEnabled = true
                s.domStorageEnabled = true
                s.databaseEnabled = true
                // "; wv" marks an embedded WebView — Cloudflare treats those more harshly.
                s.userAgentString = s.userAgentString.replace("; wv", "")
                // Never attached to a window, so give it a viewport by hand: Turnstile lays out
                // (and getBoundingClientRect works) only with a non-zero size.
                w.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(WIDTH, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(HEIGHT, android.view.View.MeasureSpec.EXACTLY))
                w.layout(0, 0, WIDTH, HEIGHT)
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(w, true)
                if (fresh) cm.removeAllCookies(null)
                w.webViewClient = WebViewClient()
                w.loadUrl(probeUrl)
                Log.i("rt: headless WebView загрузил $probeUrl")

                val startedAt = SystemClock.elapsedRealtime()
                var taps = 0
                var lastTapAt = 0L
                val poll = object : Runnable {
                    override fun run() {
                        val v = web ?: return
                        cm.flush()
                        val cookie = cm.getCookie(origin)
                        val title = v.title
                        val challenged = title?.contains("just a moment", ignoreCase = true) == true
                        if (cookie != null && cookie.contains("cf_clearance") && !challenged) {
                            Log.i("rt: headless clearance obtained за ${(SystemClock.elapsedRealtime() - startedAt) / 1000}с")
                            finish(cookie, s.userAgentString, null)
                            return
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - startedAt > timeoutMs) {
                            finish(null, null, "headless: challenge not cleared in ${timeoutMs / 1000}s (title=$title)")
                            return
                        }
                        // Interactive Turnstile: press it ourselves, same synthetic tap as the activity.
                        if (challenged && now - startedAt > 4_000 && now - lastTapAt > AUTO_TAP_EVERY_MS && taps < AUTO_TAPS_MAX) {
                            lastTapAt = now
                            taps++
                            tapChallenge(v)
                        }
                        main.postDelayed(this, POLL_MS)
                    }
                }
                main.postDelayed(poll, POLL_MS)
            } catch (e: Exception) {
                finish(null, null, "headless WebView: ${e.message}")
            }
        }
    }

    private fun tapChallenge(web: WebView) {
        web.evaluateJavascript(
            "(function(){var f=document.querySelector('iframe[src*=\"challenges.cloudflare.com\"]')" +
            "||document.querySelector('#cf-turnstile,.cf-turnstile,#challenge-stage');" +
            "if(!f)return '';var r=f.getBoundingClientRect();" +
            "return (r.left+r.width/2)+','+(r.top+Math.min(r.height/2,40));})()") { v ->
            val parts = (v ?: "").replace("\"", "").trim().split(",")
            if (parts.size != 2) return@evaluateJavascript
            val x: Float; val y: Float
            try {
                val scale = web.scale
                x = parts[0].toFloat() * scale
                y = parts[1].toFloat() * scale - web.scrollY
            } catch (_: Exception) { return@evaluateJavascript }
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
            web.dispatchTouchEvent(down)
            web.dispatchTouchEvent(up)
            down.recycle(); up.recycle()
            Log.i("rt: headless tap at ${x.toInt()},${y.toInt()}")
        }
    }
}
