// Cloudflare clearance harvester for rutracker.
//
// Since 2026-08 rutracker serves every dynamic path (login.php / tracker.php / dl.php) behind a
// Cloudflare managed challenge: a plain HTTP client always gets 403 "Just a moment...", no matter
// the User-Agent or TLS fingerprint. Only a real browser passes it, and the resulting cf_clearance
// cookie is httpOnly (invisible to page JS) and bound to BOTH the exit IP and the User-Agent.
//
// So the node harvests it here: a WebView loads rutracker, Cloudflare's JS runs and clears the
// challenge (usually in a few seconds, no interaction), and CookieManager hands us the httpOnly
// cookie. The cookie plus this WebView's UA go to the agent, which replays rutracker requests
// from the same IP on the server's behalf (agent/lib/rtrelay.js).
//
// Launched by the agent:
//   am start -n com.mediacenter.tv/.ClearanceActivity --ei port 8088 [--ez fresh true]
// The activity is visible on purpose: if Cloudflare ever escalates to a click-through widget,
// the user can tap it on the phone. On success it closes itself.
package com.mediacenter.tv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ClearanceActivity extends Activity {
    private static final String TAG = "MCClearance";
    private static final String ORIGIN = "https://rutracker.org";
    private static final String PROBE_URL = ORIGIN + "/forum/tracker.php";
    private static final long POLL_MS = 1500;
    private static final long GIVE_UP_MS = 120_000; // Cloudflare usually clears within ~10s

    private WebView web;
    private TextView hint;
    private int agentPort = 8088;
    private long startedAt;
    private boolean reported;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        agentPort = getIntent().getIntExtra("port", 8088);
        startedAt = System.currentTimeMillis();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        hint = new TextView(this);
        hint.setText("Проверка rutracker…");
        hint.setTextColor(Color.WHITE);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        // "fresh" means the agent saw a challenge with the cookie we had: drop it so Cloudflare
        // issues a new one instead of the WebView happily reusing the stale one.
        if (getIntent().getBooleanExtra("fresh", false)) cm.removeAllCookies(null);

        web.setWebViewClient(new WebViewClient());
        web.loadUrl(PROBE_URL);
        handler.postDelayed(poll, POLL_MS);
    }

    // The challenge is cleared once cf_clearance exists AND we are off the interstitial (its title
    // is "Just a moment..."); the cookie can appear a moment before the redirect lands.
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (reported) return;
            CookieManager.getInstance().flush();
            String cookie = CookieManager.getInstance().getCookie(ORIGIN);
            String title = web.getTitle();
            boolean challenged = title != null && title.toLowerCase().contains("just a moment");
            if (cookie != null && cookie.contains("cf_clearance") && !challenged) {
                report(cookie, web.getSettings().getUserAgentString(), null);
                return;
            }
            if (System.currentTimeMillis() - startedAt > GIVE_UP_MS) {
                report(null, null, "timeout: challenge not cleared in "
                        + (GIVE_UP_MS / 1000) + "s (title=" + title + ")");
                return;
            }
            hint.setText("Проверка rutracker… " + ((System.currentTimeMillis() - startedAt) / 1000) + "с");
            handler.postDelayed(this, POLL_MS);
        }
    };

    // Hand the result to the agent's local server; it stores the cookie and resumes relaying.
    private void report(final String cookie, final String ua, final String error) {
        reported = true;
        Log.d(TAG, error == null ? "clearance obtained, ua=" + ua : "failed: " + error);
        final String payload = "{\"cookie\":" + jsonString(cookie)
                + ",\"ua\":" + jsonString(ua)
                + ",\"error\":" + jsonString(error) + "}";
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    URL u = new URL("http://127.0.0.1:" + agentPort + "/api/rt-clearance");
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    c.setRequestProperty("Content-Type", "application/json");
                    OutputStream os = c.getOutputStream();
                    os.write(payload.getBytes("UTF-8"));
                    os.close();
                    Log.d(TAG, "agent replied " + c.getResponseCode());
                    c.disconnect();
                } catch (Exception e) {
                    Log.d(TAG, "cannot reach agent: " + e.getMessage());
                }
                handler.post(new Runnable() {
                    @Override public void run() { finish(); }
                });
            }
        }).start();
    }

    private static String jsonString(String v) {
        if (v == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch == '\n') b.append("\\n");
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
