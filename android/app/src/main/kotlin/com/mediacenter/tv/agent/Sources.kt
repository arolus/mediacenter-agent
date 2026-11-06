// Search sources for standalone mode: JS plugins the user installs from the phone setup page.
// The APK knows no tracker — a source is a script that registers itself:
//
//   MC.registerSource({
//     id: "example", name: "Example tracker", version: 1,
//     probe: "https://example.org/search",          // optional: page to clear Cloudflare on
//     settings: [{ key: "user", label: "Login", type: "text" },
//                { key: "pass", label: "Password", type: "password" }],
//     async login(ctx, { captcha }) { ... },        // throw MC.captcha(imageDataUrl, state) to ask
//     async search(ctx, { phrase, days, order, categories }) { return [{ tid, label, sublabel,
//                        year, meta, category, type, size, seeds, downloads }] },
//     async download(ctx, tid) { return "<.torrent as base64>" },
//   });
//
// ctx = { settings, store: { get(k), set(k, v) }, log(msg),
//         http(url, { method, body, headers, cookie, encoding }) -> { status, headers, setCookie,
//         text, base64 } }  — requests run on this device through RtRelay.fetch (Cloudflare-aware).
//
// Plugins run in one hidden WebView (DOMParser/TextDecoder for free, no JS engine in the APK).
// The bridge is strictly asynchronous: a blocked JS thread would stall the renderer that the
// clearance WebView shares. The host is created on demand and torn down after idling.
package com.mediacenter.tv.agent

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SourceException(val info: JSONObject) : Exception(info.optString("message", "ошибка источника"))

class Sources(private val ctx: Context, private val scope: CoroutineScope) {
    companion object {
        @Volatile var instance: Sources? = null
        private const val CALL_TIMEOUT_MS = 240_000L
        private const val IDLE_MS = 5 * 60_000L
        private const val MAX_CODE = 512 * 1024

        // Runtime injected before any plugin: registration, the ctx object, the async bridge.
        private val RUNTIME = """
(function () {
  var MC = window.MC = { _sources: {}, _pending: {}, _seq: 0, _last: null };
  MC.registerSource = function (def) {
    if (!def || !def.id || !/^[a-z0-9_-]{1,40}${'$'}/.test(def.id)) throw new Error("source id must be [a-z0-9_-]");
    MC._sources[def.id] = def; MC._last = def.id;
  };
  MC.captcha = function (image, state) { var e = new Error("captcha"); e.captcha = { image: image, state: state || null }; return e; };
  MC._meta = function (id) {
    var d = MC._sources[id]; if (!d) return null;
    return { id: d.id, name: d.name || d.id, version: d.version || 1, probe: d.probe || null,
             settings: (d.settings || []).map(function (s) { return { key: s.key, label: s.label || s.key, type: s.type || "text" }; }) };
  };
  function b64ToBytes(b64) { var s = atob(b64 || ""), a = new Uint8Array(s.length); for (var i = 0; i < s.length; i++) a[i] = s.charCodeAt(i); return a; }
  function http(id, url, opts) {
    opts = opts || {};
    var cid = "h" + (++MC._seq);
    return new Promise(function (ok, fail) {
      MC._pending[cid] = { ok: function (r) {
        var bytes = b64ToBytes(r.base64);
        try { r.text = new TextDecoder(opts.encoding || "utf-8").decode(bytes); } catch (e) { r.text = ""; }
        r.bytes = bytes; ok(r);
      }, fail: fail };
      MCHost.http(cid, id, JSON.stringify({ url: url, method: opts.method || "GET", body: opts.body == null ? null : String(opts.body),
        bodyB64: opts.bodyB64 || null, headers: opts.headers || {}, cookie: opts.cookie || "", probe: opts.probe || null }));
    });
  }
  MC._httpDone = function (cid, r) { var p = MC._pending[cid]; delete MC._pending[cid]; if (p) p.ok(r); };
  MC._httpFail = function (cid, msg) { var p = MC._pending[cid]; delete MC._pending[cid]; if (p) p.fail(new Error(msg)); };
  MC._call = function (cid, id, method, settings, args) {
    var d = MC._sources[id];
    var ctx = {
      settings: settings || {},
      store: { get: function (k) { var v = MCHost.storeGet(id, k); return v ? JSON.parse(v) : null; },
               set: function (k, v) { MCHost.storeSet(id, k, JSON.stringify(v == null ? null : v)); } },
      log: function (m) { MCHost.log(id, String(m)); },
      http: function (url, opts) { if (d.probe && opts && !opts.probe) opts.probe = d.probe; return http(id, url, opts || (d.probe ? { probe: d.probe } : {})); }
    };
    Promise.resolve().then(function () {
      if (!d) throw new Error("источник не установлен: " + id);
      if (typeof d[method] !== "function") throw new Error("источник не умеет " + method);
      return method === "download" ? d.download(ctx, args.tid) : d[method](ctx, args);
    }).then(function (res) { MCHost.done(cid, JSON.stringify({ result: res === undefined ? null : res })); },
            function (e) { MCHost.fail(cid, JSON.stringify({ message: (e && e.message) || String(e), captcha: (e && e.captcha) || null })); });
  };
})();
"""
    }

    private val main = Handler(Looper.getMainLooper())
    private val dir = File(ctx.filesDir, "sources").apply { mkdirs() }
    private val calls = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val seq = AtomicLong()
    private val hostLock = Mutex()
    @Volatile private var web: WebView? = null
    @Volatile private var lastUse = 0L

    fun start() {
        instance = this
        scope.launch {
            while (true) {
                delay(60_000)
                if (web != null && calls.isEmpty() && System.currentTimeMillis() - lastUse > IDLE_MS) destroyHost()
            }
        }
    }

    fun stop() { instance = null; destroyHost() }

    // ---- installed sources (metadata lives in LocalSettings, code in files/sources/<id>.js) ----
    fun list(): List<JSONObject> {
        val all = LocalSettings.load(ctx).optJSONObject("sources") ?: return emptyList()
        return all.keys().asSequence().mapNotNull { all.optJSONObject(it) }.toList()
    }

    fun get(id: String): JSONObject? = LocalSettings.load(ctx).optJSONObject("sources")?.optJSONObject(id)

    private fun settingsOf(id: String): JSONObject = get(id)?.optJSONObject("values") ?: JSONObject()

    suspend fun installFromUrl(url: String): JSONObject {
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw Exception("нужна ссылка http(s)")
        val r = withContext(Dispatchers.IO) { httpFetch(url, timeoutMs = 30000) }
        if (r.status != 200) throw Exception("ссылка не открылась: HTTP ${r.status}")
        return install(String(r.body, Charsets.UTF_8), url)
    }

    suspend fun install(code: String, url: String?): JSONObject {
        if (code.length > MAX_CODE) throw Exception("файл слишком большой для источника")
        if (!code.contains("registerSource")) throw Exception("это не источник MediaCenter (нет MC.registerSource)")
        val meta = hostLock.withLock {
            ensureHost()
            eval("MC._last = null;\n$code\n;JSON.stringify(MC._last ? MC._meta(MC._last) : null)")
        }
        val m = try { JSONObject(unquote(meta)) } catch (_: Exception) {
            throw Exception("источник не зарегистрировался — файл повреждён или с ошибкой")
        }
        val id = m.getString("id")
        File(dir, "$id.js").writeText(code)
        LocalSettings.update(ctx) { s ->
            val all = s.optJSONObject("sources") ?: JSONObject()
            val prev = all.optJSONObject(id)
            m.put("url", url ?: prev?.optString("url") ?: JSONObject.NULL)
            m.put("values", prev?.optJSONObject("values") ?: JSONObject())
            all.put(id, m)
            s.put("sources", all)
        }
        Log.i("sources: установлен ${m.optString("name")} v${m.optInt("version")}")
        return m
    }

    fun remove(id: String) {
        File(dir, "$id.js").delete()
        File(dir, "$id.store.json").delete()
        LocalSettings.update(ctx) { s -> s.optJSONObject("sources")?.remove(id) }
        destroyHost()   // next call reloads only what is still installed
    }

    fun saveValues(id: String, values: JSONObject) {
        LocalSettings.update(ctx) { s ->
            val src = s.optJSONObject("sources")?.optJSONObject(id) ?: throw Exception("нет такого источника")
            val cur = src.optJSONObject("values") ?: JSONObject()
            for (k in values.keys()) cur.put(k, values.opt(k))
            src.put("values", cur)
        }
    }

    // ---- calls -------------------------------------------------------------------------------
    suspend fun call(id: String, method: String, args: JSONObject): Any? {
        if (get(id) == null) throw Exception("источник не установлен: $id")
        val cid = "c" + seq.incrementAndGet()
        val d = CompletableDeferred<JSONObject>()
        calls[cid] = d
        lastUse = System.currentTimeMillis()
        try {
            hostLock.withLock { ensureHost() }
            val js = "MC._call(${q(cid)}, ${q(id)}, ${q(method)}, ${safe(settingsOf(id).toString())}, ${safe(args.toString())})"
            withContext(Dispatchers.Main) { web?.evaluateJavascript(js, null) }
            return withTimeout(CALL_TIMEOUT_MS) { d.await() }.opt("result")
        } finally {
            calls.remove(cid)
            lastUse = System.currentTimeMillis()
        }
    }

    // ---- host WebView ------------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureHost() {
        if (web != null) return
        val ready = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            val w = WebView(ctx)
            w.settings.javaScriptEnabled = true
            w.settings.allowFileAccess = false
            w.settings.allowContentAccess = false
            w.addJavascriptInterface(Bridge(), "MCHost")
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { ready.complete(Unit) }
            }
            w.loadDataWithBaseURL("https://sources.invalid/",
                "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>",
                "text/html", "utf-8", null)
            web = w
        }
        withTimeout(20_000) { ready.await() }
        eval(RUNTIME)
        for (f in dir.listFiles { f -> f.name.endsWith(".js") } ?: emptyArray()) {
            if (get(f.name.removeSuffix(".js")) == null) continue
            try { eval(f.readText() + "\n;0") } catch (e: Exception) { Log.e("sources: ${f.name}: ${e.message}") }
        }
        Log.i("sources: хост поднят")
    }

    private fun destroyHost() {
        val w = web ?: return
        web = null
        main.post { try { w.destroy() } catch (_: Exception) {} }
        calls.values.forEach { it.completeExceptionally(Exception("источник перезапущен")) }
        calls.clear()
    }

    private suspend fun eval(js: String): String {
        val d = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            val w = web ?: throw Exception("хост источников не поднят")
            w.evaluateJavascript(js) { d.complete(it ?: "null") }
        }
        return withTimeout(30_000) { d.await() }
    }

    private fun post(js: String) = main.post { web?.evaluateJavascript(js, null) }

    // evaluateJavascript returns a JSON-encoded string value: "\"{...}\"".
    private fun unquote(v: String): String = try { JSONArray("[$v]").getString(0) } catch (_: Exception) { v }

    private fun q(s: String) = JSONObject.quote(s)
    // JSON is valid JS except for U+2028/U+2029 inside strings on old engines.
    private fun safe(json: String) = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun storeFile(id: String) = File(dir, "${id.replace(Regex("[^a-z0-9_-]"), "")}.store.json")

    private inner class Bridge {
        @JavascriptInterface
        fun http(cid: String, sourceId: String, req: String) {
            scope.launch {
                try {
                    val o = JSONObject(req)
                    val relay = RtRelay.instance ?: throw Exception("сетевой модуль не запущен")
                    val headers = HashMap<String, String>()
                    o.optJSONObject("headers")?.let { h -> h.keys().forEach { headers[it] = h.optString(it) } }
                    val body = when {
                        !o.isNull("bodyB64") -> android.util.Base64.decode(o.getString("bodyB64"), android.util.Base64.DEFAULT)
                        !o.isNull("body") -> o.getString("body").toByteArray(Charsets.UTF_8)
                        else -> null
                    }
                    val r = relay.fetch(o.getString("url"), o.optString("method", "GET"), headers, body,
                        o.optString("cookie").ifEmpty { null }, if (o.isNull("probe")) null else o.optString("probe"))
                    val res = JSONObject()
                        .put("status", r.status)
                        .put("headers", JSONObject(r.headers.mapValues { it.value.joinToString(", ") }))
                        .put("setCookie", JSONArray(r.headers["set-cookie"] ?: emptyList<String>()))
                        .put("base64", android.util.Base64.encodeToString(r.body, android.util.Base64.NO_WRAP))
                    post("MC._httpDone(${q(cid)}, ${safe(res.toString())})")
                } catch (e: Exception) {
                    Log.e("sources[$sourceId] http: ${e.message}")
                    post("MC._httpFail(${q(cid)}, ${q(e.message ?: "сеть недоступна")})")
                }
            }
        }

        @JavascriptInterface
        fun done(cid: String, json: String) { calls[cid]?.complete(JSONObject(json)) }

        @JavascriptInterface
        fun fail(cid: String, json: String) {
            calls[cid]?.completeExceptionally(SourceException(try { JSONObject(json) } catch (_: Exception) { JSONObject() }))
        }

        @JavascriptInterface
        fun storeGet(id: String, key: String): String? = synchronized(this@Sources) {
            try { JSONObject(storeFile(id).readText()).opt(key)?.takeIf { it != JSONObject.NULL }?.let {
                if (it is String) JSONObject.quote(it) else it.toString() } } catch (_: Exception) { null }
        }

        @JavascriptInterface
        fun storeSet(id: String, key: String, json: String) = synchronized(this@Sources) {
            val f = storeFile(id)
            val o = try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
            val v = try { JSONArray("[$json]").get(0) } catch (_: Exception) { JSONObject.NULL }
            o.put(key, v)
            f.writeText(o.toString())
        }

        @JavascriptInterface
        fun log(id: String, msg: String) = Log.i("sources[$id]: ${msg.take(500)}")
    }
}
