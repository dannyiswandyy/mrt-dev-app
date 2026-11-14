
package sg.mrttracker.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import androidx.webkit.WebViewAssetLoader;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {

    /** The only sites the tracker page may ask the app to fetch. */
    private static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "datamall2.mytransport.sg",
            "news.google.com",
            "api-open.data.gov.sg"));      // NEA live weather (data.gov.sg)

    /** The app's own page, bundled inside the app. */
    private static final String PAGE_URL = "https://sg.mrttracker.app/assets/index.html";
    private WebViewAssetLoader assetLoader;
    /** Where LTA's temporary timetable download links point. */
    private static final String LTA_FILES_HOST = "dmprod-datasets.s3.ap-southeast-1.amazonaws.com";

    /**
     * The Live cam tab's player page (assets/livecam.html) is served at this address, never fetched from
     * the internet. YouTube only plays embedded videos for an app that identifies itself with an https
     * address (its "Referer"), which the tracker page itself, a bundled file, can't give.
     */
    private static final String CAM_HOST = "sg.mrttracker.app";
    private static final String CAM_CSP = "default-src 'none'; script-src 'unsafe-inline' https://www.youtube.com https://s.ytimg.com; "
            + "frame-src https://www.youtube.com https://www.youtube-nocookie.com; style-src 'unsafe-inline'; img-src https: data:; "
            + "base-uri 'none'; form-action 'none'";

    /** The OpenAI API (ChatGPT), used by the Ask tab, and the model it uses. */
    private static final String AI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String AI_MODEL = "gpt-5-mini";

    private WebView web;
    private String cachedKey = null;
    private String cachedAiKey = null;
    private SharedPreferences prefs;
    private boolean pageLoaded = false;
    private boolean updating = false;
    /** On screen right now (between onResume and onPause). */
    private boolean resumed = false;
    /** A new LTA timetable is waiting: load the page again next time the app is brought back. */
    private boolean timetablePending = false;
    /** A video shown full screen (the Live cam's full-screen button), and how to hand it back. */
    private View fullView = null;
    private WebChromeClient.CustomViewCallback fullDone = null;
    // DEV:START
    /** Train spotting (MRT Tracker's Live cam): the box over the tracks, in the page's CSS pixels, and how often to look. */
    private final android.os.Handler camHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private android.graphics.RectF camBox = null;
    private int camEvery = 200;
    private boolean camCopying = false;
    private final Runnable camTick = new Runnable() {
        @Override
        public void run() {
            if (camBox == null) return;
            camCapture();
            camHandler.postDelayed(this, camEvery);
        }
    };
    // DEV:END
    private static java.lang.ref.WeakReference<MainActivity> current = new java.lang.ref.WeakReference<>(null);
    private static android.content.Context appContext = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        appContext = getApplicationContext();
        prefs.edit().remove("spot_host").apply();   // spotter feed from older versions is no longer used
        prefs.edit().remove("lta_key").apply();   // keys typed into older versions are no longer used
        getSharedPreferences("game", MODE_PRIVATE).edit().clear().apply();   // the spotting game was removed

        // Nobody can attach a debugger to the page inside this app.
        WebView.setWebContentsDebuggingEnabled(false);

        web = new WebView(this);
        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(CAM_HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // The page only needs its own bundled file; it may not read other files on the phone.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        // The Live cam starts playing as soon as its tab opens.
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "MrtApp");
        // Offline: with no connection, map tiles and fonts come from the WebView's own cache.
        watchNetwork();

        // Full screen for the Live cam video (YouTube's full-screen button), sideways while it's shown.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullView = view;
                fullDone = callback;
                ((FrameLayout) getWindow().getDecorView()).addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                systemBars(false);
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }

            @Override
            public void onHideCustomView() {
                leaveFullScreen();
            }

            /** No grey "play" placeholder before the video starts. */
            @Override
            public android.graphics.Bitmap getDefaultVideoPoster() {
                return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
            }
        });

        // Keep the tracker inside the app. Web links (https/http) open in the browser or the
        // X/Facebook app; every other kind of link (app intents, scripts, other files) is blocked.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Frames inside the page (the Live cam player) may only go where the page's
                // security rules allow (the player page, then YouTube), so let them load.
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                String u = uri.toString();
                if ("https".equals(uri.getScheme()) && CAM_HOST.equals(uri.getHost()) && (uri.getPath().startsWith("/assets/") || "/livecam.html".equals(uri.getPath()))) return false;
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
                if (scheme.equals("https") || scheme.equals("http")) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, uri);
                        i.addCategory(Intent.CATEGORY_BROWSABLE);
                        startActivity(i);
                    } catch (Exception ignored) {
                    }
                }
                return true;
            }

            /** Serves the Live cam player page at https://sg.mrttracker.app/livecam.html (nothing else there). */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !CAM_HOST.equals(uri.getHost())) return null;
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Content-Security-Policy", CAM_CSP);
                h.put("Referrer-Policy", "strict-origin-when-cross-origin");
                h.put("Cache-Control", "no-store");
                if ("/livecam.html".equals(uri.getPath())) {
                    try {
                        return new WebResourceResponse("text/html", "utf-8", 200, "OK", h, getAssets().open("livecam.html"));
                    } catch (Exception ignored) {
                    }
                }
                WebResourceResponse local = assetLoader.shouldInterceptRequest(uri);
                if (local != null) return local;
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h,
                        new java.io.ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                if (widgetStation != null) {                // opened from a widget: show its station
                    final String js = "window.appOpenStation && window.appOpenStation(" + JSONObject.quote(widgetStation) + ")";
                    widgetStation = null;
                    web.evaluateJavascript(js, null);
                }
                checkForUpdate();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                pageLoaded = false;
                return true;
            }

        });

        setContentView(web);
        web.loadUrl(PAGE_URL);

        // Updates: check in the background every few hours, and ask once to allow notifications.
        Updater.schedule(this);
        // Notification channels (disruptions, trip reminders) and the background disruption check
        Alerts.channels(this);
        Alerts.schedule(this);
        // LTA's timetable: checked for a new one every 6 hours or so, even while the app is closed
        Timetable.schedule(this);
        openFromWidget(getIntent());
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notify", false)
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("asked_notify", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromWidget(intent);
        checkForUpdate();
    }

    /** Checks LTA for a new timetable in the background; with an id, the page gets the answer. */
    private void checkTimetableNow(final String id) {
        new Thread(() -> {
            JSONObject r = Timetable.refresh(getApplicationContext());
            if (id == null) {
                if ("new".equals(r.optString("status"))) timetableUpdated();
                return;
            }
            final String js = "window.__native(" + JSONObject.quote(id) + ",200," + JSONObject.quote(r.toString()) + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        }).start();
    }

    /**
     * A new LTA timetable was saved (by the check above or the background job). The page reads the timetable when it
     * loads, so load it again: straight away if the app isn't on screen, else the next time it's brought back.
     */
    static void timetableUpdated() {
        if (appContext != null) TrainWidget.updateAll(appContext);   // widgets use the new timetable too
        MainActivity a = current.get();
        if (a == null) return;                     // the app isn't open: it uses the new timetable when it next starts
        a.runOnUiThread(() -> {
            if (a.resumed) a.timetablePending = true;
            else if (a.pageLoaded) a.web.reload();
        });
    }

    /** A home screen widget was tapped: open its station (now, or once the page has loaded). */
    private String widgetStation = null;

    private void openFromWidget(Intent intent) {
        String st = intent == null ? null : intent.getStringExtra(TrainWidget.EXTRA_STATION);
        if (st == null || st.isEmpty()) return;
        intent.removeExtra(TrainWidget.EXTRA_STATION);
        if (pageLoaded) web.evaluateJavascript("window.appOpenStation && window.appOpenStation(" + JSONObject.quote(st) + ")", null);
        else widgetStation = st;
    }

    /** Asks GitHub whether a newer version exists; if so, the page shows the update banner. */
    private void checkForUpdate() {
        if (!Updater.enabled() || updating) return;
        new Thread(() -> {
            org.json.JSONObject j = Updater.newer(getApplicationContext());
            if (j == null) return;
            final String js = "window.updateAvailable && window.updateAvailable(" + JSONObject.quote(j.optString("versionName", "")) + ")";
            runOnUiThread(() -> { if (pageLoaded) web.evaluateJavascript(js, null); });
        }).start();
    }

    /** Called by the installer if an update couldn't be installed. */
    static void reportUpdateError(String message) {
        MainActivity a = current.get();
        if (a == null) return;
        a.updating = false;
        final String js = "window.updateError && window.updateError(" + JSONObject.quote(message == null ? "" : message) + ")";
        a.runOnUiThread(() -> a.web.evaluateJavascript(js, null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = new java.lang.ref.WeakReference<>(this);
        resumed = true;
        web.onResume();
        if (timetablePending && pageLoaded) {
            timetablePending = false;
            web.reload();
            return;
        }
        if (pageLoaded) checkForUpdate();
        // Fresh status every time the app is opened or brought back to the front.
        if (pageLoaded) {
            web.evaluateJavascript("window.appResume && window.appResume()", null);
        }
    }

    /** Back from a full-screen video: put the page back as it was. */
    private void leaveFullScreen() {
        if (fullView == null) return;
        ((FrameLayout) getWindow().getDecorView()).removeView(fullView);
        fullView = null;
        systemBars(true);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (fullDone != null) fullDone.onCustomViewHidden();
        fullDone = null;
    }

    // DEV:START
    /**
     * Train spotting: copies what's on screen inside the box (the Live cam's video, over the tracks) into a
     * small grey picture and hands it to the page, which looks for trains in it. Nothing is saved or sent.
     */
    private void camCapture() {
        if (Build.VERSION.SDK_INT < 26 || camBox == null || camCopying || fullView != null || !pageLoaded) return;
        float d = getResources().getDisplayMetrics().density;
        int[] at = new int[2];
        web.getLocationInWindow(at);
        android.graphics.Rect r = new android.graphics.Rect(Math.round(at[0] + camBox.left * d), Math.round(at[1] + camBox.top * d),
                Math.round(at[0] + camBox.right * d), Math.round(at[1] + camBox.bottom * d));
        View root = getWindow().getDecorView();
        if (!r.intersect(0, 0, root.getWidth(), root.getHeight()) || r.width() < 4 || r.height() < 4) return;
        final int bw = 64, bh = Math.max(6, Math.min(32, Math.round(64f * r.height() / r.width())));
        final android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
        camCopying = true;
        try {
            android.view.PixelCopy.request(getWindow(), r, bm, result -> {
                camCopying = false;
                if (result != android.view.PixelCopy.SUCCESS || camBox == null) {
                    bm.recycle();
                    return;
                }
                int[] px = new int[bw * bh];
                bm.getPixels(px, 0, bw, 0, 0, bw, bh);
                bm.recycle();
                byte[] g = new byte[px.length];
                for (int i = 0; i < px.length; i++) {
                    int c = px[i];
                    g[i] = (byte) ((((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8);
                }
                web.evaluateJavascript("window.camSpotFrame&&camSpotFrame('" + android.util.Base64.encodeToString(g, android.util.Base64.NO_WRAP)
                        + "'," + bw + "," + bh + ")", null);
            }, camHandler);
        } catch (Exception e) {
            camCopying = false;
        }
    }
    // DEV:END

    /** Hides the status and navigation bars while a video is full screen (a swipe brings them back briefly). */
    @SuppressWarnings("deprecation")
    private void systemBars(boolean show) {
        getWindow().getDecorView().setSystemUiVisibility(show ? View.SYSTEM_UI_FLAG_VISIBLE
                : View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** The answer to "Allow notifications?": tell the page so it can update its switches. */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (pageLoaded) web.evaluateJavascript("window.notifyPermChanged && window.notifyPermChanged()", null);
    }

    private android.net.ConnectivityManager.NetworkCallback netCallback = null;
    private volatile boolean online = true;

    private boolean onlineNow() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.Network n = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    /** Online: normal caching. Offline: use whatever the WebView has saved (map tiles, fonts), however old. */
    private void netChanged(boolean on) {
        online = on;
        web.getSettings().setCacheMode(on ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        if (pageLoaded) web.evaluateJavascript("window.netState && window.netState(" + on + ")", null);
    }

    private void watchNetwork() {
        netChanged(onlineNow());
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            if (cm == null) return;
            netCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network n) {
                    runOnUiThread(() -> netChanged(true));
                }

                @Override
                public void onLost(android.net.Network n) {
                    runOnUiThread(() -> netChanged(onlineNow()));
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception ignored) {
            netCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (netCallback != null) getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) {
        }
        netCallback = null;
        super.onDestroy();
    }

    /** Android's back button closes an open history card (or other panel) before leaving the app. */
    @Override
    public void onBackPressed() {
        if (fullView != null) {
            leaveFullScreen();
            return;
        }
        if (!pageLoaded) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.appBack && window.appBack()) ? 1 : 0", v -> {
            if (!"1".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onPause() {
        resumed = false;
        // The Live cam stops streaming while the app is in the background (it saves mobile data).
        leaveFullScreen();
        // DEV:START
        camBox = null;
        camHandler.removeCallbacks(camTick);
        // DEV:END
        if (pageLoaded) web.evaluateJavascript("window.appPause && window.appPause()", null);
        web.onPause();
        super.onPause();
    }

    /** Methods the tracker page can call as window.MrtApp.*. */
    private class Bridge {

        @JavascriptInterface
        public boolean hasKey() {
            return !ltaKey().isEmpty();
        }

        /** LTA's official train timetable: the newest one the app downloaded itself, else the one packed at build time ("" if none). */
        @JavascriptInterface
        public String getTimetable() {
            return Timetable.current(getApplicationContext());
        }

        /** The stations of the lines LTA's timetable covers, which the app needs to pack a new timetable. Checks for one if it's time. */
        @JavascriptInterface
        public void timetableStations(final String json) {
            Timetable.setStations(getApplicationContext(), json);
            if (Timetable.due(getApplicationContext())) checkTimetableNow(null);
        }

        /** Checks LTA for a new timetable now (the Settings button). Reply: {"status": "new" | "same" | "busy" | "error", "error"} */
        @JavascriptInterface
        public void checkTimetable(final String id) {
            checkTimetableNow(id);
        }

        /** When the app last checked LTA for a new timetable (milliseconds since 1970; 0 if never). */
        @JavascriptInterface
        public String timetableChecked() {
            return String.valueOf(Timetable.prefs(getApplicationContext()).getLong("checked", 0));
        }

        /** Building outlines and heights near stations (OpenStreetMap), packed at build time ("" if none). */
        @JavascriptInterface
        public String getBuildings() {
            try (InputStream in = getAssets().open("buildings.json")) {
                return readAll(in);
            } catch (Exception e) {
                return "";
            }
        }

        /** Station exits and the places near each one (OpenStreetMap), packed at build time ("" if none). */
        @JavascriptInterface
        public String getExits() {
            try (InputStream in = getAssets().open("exits.json")) {
                return readAll(in);
            } catch (Exception e) {
                return "";
            }
        }

        /** Real track shapes for the RTS Link and the Sentosa Express (OpenStreetMap), packed when GitHub builds the app. */
        @JavascriptInterface
        public String getTracks() {
            try (InputStream in = getAssets().open("tracks.json")) {
                return readAll(in);
            } catch (Exception e) {
                return "";
            }
        }

        /** Downloads and installs the new version. Progress and errors go back to the page. */
        @JavascriptInterface
        public void installUpdate() {
            if (updating || !Updater.enabled()) return;
            // Android asks once for permission to install updates from this app.
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                runOnUiThread(() -> {
                    web.evaluateJavascript("window.updateNeedsPermission && window.updateNeedsPermission()", null);
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) {
                    }
                });
                return;
            }
            updating = true;
            new Thread(() -> {
                try {
                    Updater.downloadAndInstall(getApplicationContext(), pct -> runOnUiThread(() ->
                            web.evaluateJavascript("window.updateProgress && window.updateProgress(" + pct + ")", null)));
                    runOnUiThread(() -> web.evaluateJavascript("window.updateProgress && window.updateProgress(100)", null));
                } catch (Exception e) {
                    reportUpdateError("The download didn't finish. Check your internet connection and try again.");
                } finally {
                    updating = false;
                }
            }).start();
        }

        /** True when an OpenAI API key was built into the app, so the Ask tab can use ChatGPT. */
        @JavascriptInterface
        public boolean hasAi() {
            return !aiKey().isEmpty();
        }

        /**
         * Sends one Ask-tab request to the OpenAI API (ChatGPT) and returns the reply to the page.
         * The key is added here and never given to the page. Whatever the page sends, the app
         * only uses its own model and caps the answer length, so a request can't run up costs.
         */
        @JavascriptInterface
        public void askAi(final String id, final String request) {
            new Thread(() -> {
                int code = 0;
                String body = "";
                HttpURLConnection conn = null;
                try {
                    String key = aiKey();
                    if (key.isEmpty()) throw new SecurityException("No OpenAI key in this build");
                    JSONObject req = new JSONObject(request);
                    req.put("model", AI_MODEL);
                    req.put("max_completion_tokens", Math.max(1, Math.min(req.optInt("max_completion_tokens", 2000), 2000)));
                    req.put("reasoning_effort", "low");
                    req.remove("max_tokens");
                    req.remove("stream");
                    req.remove("stream_options");
                    req.remove("n");
                    req.remove("service_tier");
                    byte[] out = req.toString().getBytes(StandardCharsets.UTF_8);
                    if (out.length > 200000) throw new java.io.IOException("Request too large");
                    conn = (HttpURLConnection) new URL(AI_URL).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    conn.setRequestProperty("content-type", "application/json");
                    conn.setRequestProperty("Authorization", "Bearer " + key);
                    conn.setFixedLengthStreamingMode(out.length);
                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        os.write(out);
                    }
                    code = conn.getResponseCode();
                    InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    body = in == null ? "" : readAll(in);
                } catch (Exception e) {
                    code = 0;
                    body = "";
                } finally {
                    if (conn != null) conn.disconnect();
                }
                final String js = "window.__native(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(body) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        /**
         * Finds a YouTube channel's live stream for the Live cam tab (RailCam Singapore, @railcamsg):
         * opens the channel's /live page, which is the live video's page while the channel is live.
         * When it isn't live, also lists the channel's latest videos. Reply (JSON):
         * {"video":"id","live":bool,"upcoming":bool,"title":"...","start":"...","channel":"UC...",
         *  "latest":[{"video":"id","title":"...","published":"..."}]}
         */
        @JavascriptInterface
        public void liveLookup(final String id, final String handle) {
            new Thread(() -> {
                int code = 200;
                JSONObject out = new JSONObject();
                try {
                    if (handle == null || !handle.matches("@[A-Za-z0-9._-]{3,30}")) throw new SecurityException("Bad channel");
                    String page = youtubeText("https://www.youtube.com/" + handle + "/live", 6_000_000);
                    String video = firstMatch(page, "<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})\"");
                    String channel = firstMatch(page, "\"(?:externalId|channelId)\":\"(UC[A-Za-z0-9_-]{22})\"");
                    boolean live = video != null && (page.contains("\"isLiveNow\":true") || page.contains("\"isLive\":true"));
                    out.put("video", video == null ? "" : video);
                    out.put("live", live);
                    out.put("upcoming", video != null && !live && page.contains("\"isUpcoming\":true"));
                    out.put("title", unescape(firstMatch(page, "<meta name=\"title\" content=\"([^\"]*)\"")));
                    out.put("start", nz(firstMatch(page, "\"startTimestamp\":\"([^\"]+)\"")));
                    out.put("channel", nz(channel));
                    if (!live && channel != null) {
                        String rss = youtubeText("https://www.youtube.com/feeds/videos.xml?channel_id=" + channel, 1_000_000);
                        org.json.JSONArray latest = new org.json.JSONArray();
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<entry>(.*?)</entry>", java.util.regex.Pattern.DOTALL).matcher(rss);
                        while (m.find() && latest.length() < 6) {
                            String e = m.group(1), v = firstMatch(e, "<yt:videoId>([A-Za-z0-9_-]{11})</yt:videoId>");
                            if (v == null) continue;
                            JSONObject item = new JSONObject();
                            item.put("video", v);
                            item.put("title", unescape(firstMatch(e, "<title>([^<]*)</title>")));
                            item.put("published", nz(firstMatch(e, "<published>([^<]+)</published>")));
                            latest.put(item);
                        }
                        out.put("latest", latest);
                    }
                } catch (Exception e) {
                    code = 0;
                }
                final String js = "window.__native(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(out.toString()) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        // DEV:START
        /**
         * Train spotting on the Live cam (MRT Tracker): {"x","y","w","h"} is the box over the tracks in the page's
         * CSS pixels, "every" how often to look (ms); {} stops. False if this phone can't (Android 8 or newer is needed).
         */
        @JavascriptInterface
        public boolean camWatch(final String json) {
            if (Build.VERSION.SDK_INT < 26) return false;
            android.graphics.RectF box = null;
            int every = 200;
            try {
                JSONObject j = new JSONObject(json == null ? "{}" : json);
                double x = j.optDouble("x", 0), y = j.optDouble("y", 0), w = j.optDouble("w", 0), h = j.optDouble("h", 0);
                every = Math.max(100, Math.min(1000, j.optInt("every", 200)));
                if (w > 2 && h > 2) box = new android.graphics.RectF((float) x, (float) y, (float) (x + w), (float) (y + h));
            } catch (Exception ignored) {
            }
            final android.graphics.RectF b = box;
            final int ev = every;
            runOnUiThread(() -> {
                camBox = b;
                camEvery = ev;
                camHandler.removeCallbacks(camTick);
                if (b != null) camHandler.post(camTick);
            });
            return true;
        }
        // DEV:END

        /** Checks for a new version now (the Settings button). Reply: {"available":bool,"version":"..."} */
        @JavascriptInterface
        public void checkUpdateNow(final String id) {
            new Thread(() -> {
                org.json.JSONObject j = Updater.newer(getApplicationContext());
                String body = "{\"available\":" + (j != null) + ",\"version\":" + JSONObject.quote(j == null ? "" : j.optString("versionName", ""))
                        + ",\"enabled\":" + Updater.enabled() + "}";
                final String js = "window.__native(" + JSONObject.quote(id) + ",200," + JSONObject.quote(body) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        /** True when the LTA key was built into the app, so nobody needs to enter one. */
        @JavascriptInterface
        public boolean builtInKey() {
            return !ltaKey().isEmpty();
        }


        /** Which app this is: "beta" (MRT Tracker) or "dev" (MRT Tracker, for trying new features). */
        @JavascriptInterface
        public String getChannel() {
            return BuildConfig.CHANNEL;
        }

        /** The installed app's version, e.g. 2026.11.05-1310 (the build date and time). */
        @JavascriptInterface
        public String getVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        /** The home screen widget's data: the first saved station's next trains (small JSON from Home). */
        @JavascriptInterface
        public void widgetData(String json) {
            if (json == null || json.length() > 64000) return;
            TrainWidget.save(getApplicationContext(), json);
        }

        /** What the widget needs to work out trains by itself: line colours and names, public holidays, slower trains. */
        @JavascriptInterface
        public void widgetMeta(String json) {
            if (json == null || json.length() > 64000) return;
            TrainWidget.saveMeta(getApplicationContext(), json);
        }

        /** "on", "ask" (Android may still ask the person) or "off" (blocked: only Settings can turn it on). */
        @JavascriptInterface
        public String notifyState() {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return !prefs.getBoolean("asked_notify", false) || shouldShowRequestPermissionRationale("android.permission.POST_NOTIFICATIONS") ? "ask" : "off";
            }
            return Alerts.allowed(getApplicationContext()) ? "on" : "off";
        }

        @JavascriptInterface
        public void askNotify() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT < 33) {
                    if (pageLoaded) web.evaluateJavascript("window.notifyPermChanged && window.notifyPermChanged()", null);
                    return;
                }
                prefs.edit().putBoolean("asked_notify", true).apply();
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
            });
        }

        @JavascriptInterface
        public void openNotifySettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = Build.VERSION.SDK_INT >= 26
                            ? new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())
                            : new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception ignored) {
                }
            });
        }

        /** A disruption on a saved line (sig names it, so each one notifies once); "" means all clear. */
        @JavascriptInterface
        public void disruption(String sig, String title, String text) {
            Alerts.disruption(getApplicationContext(), sig, title, text);
        }

        /** Which LTA lines to watch in the background, and whether disruption alerts are on. */
        @JavascriptInterface
        public void setAlertLines(String json) {
            if (json == null || json.length() > 2000) return;
            Alerts.setLines(getApplicationContext(), json);
        }

        /** Trip reminders: [{at: epoch ms, title, text}], from the planned timetable. */
        @JavascriptInterface
        public void tripRemind(String json) {
            if (json == null || json.length() > 8000) return;
            Alerts.tripSchedule(getApplicationContext(), json);
        }

        @JavascriptInterface
        public void tripCancel() {
            Alerts.tripCancel(getApplicationContext());
        }

        /** Android's share sheet for plain text (the Spotter's log export). */
        @JavascriptInterface
        public void share(final String text) {
            if (text == null || text.length() > 200000) return;
            runOnUiThread(() -> {
                try {
                    Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                            .putExtra(Intent.EXTRA_SUBJECT, "My MRT Spotter's log");
                    startActivity(Intent.createChooser(s, "Share Spotter's log"));
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public boolean isOnline() {
            return online;
        }

        /** LTA's live train feeds (GTFS Realtime), kind "trips" or "alerts"; the answer comes back through window.__native as base64. */
        @JavascriptInterface
        public void gtfsRealtime(final String id, final String kind) {
            gtfsRealtimeFetch(id, kind);
        }

        @JavascriptInterface
        public void fetch(final String id, final String url, final boolean withKey) {
            new Thread(() -> {
                int code = 0;
                String body = "";
                HttpURLConnection conn = null;
                try {
                    URL u = new URL(url);
                    // LTA, Google News and NEA weather only.
                    String host = u.getHost().toLowerCase(java.util.Locale.ROOT);
                    boolean allowed = ALLOWED_HOSTS.contains(host);
                    if (!"https".equals(u.getProtocol()) || !allowed) {
                        throw new SecurityException("Host not allowed");
                    }
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", "MRTTracker/1.0 (Android)");
                    conn.setRequestProperty("Accept", "application/json, application/rss+xml, text/xml");
                    // The LTA key is only ever sent to LTA.
                    if (withKey && host.equals("datamall2.mytransport.sg")) conn.setRequestProperty("AccountKey", ltaKey());
                    code = conn.getResponseCode();
                    InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    body = in == null ? "" : readAll(in);
                } catch (Exception e) {
                    code = 0;
                    body = "";
                } finally {
                    if (conn != null) conn.disconnect();
                }
                final String js = "window.__native(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(body) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        /**
         * Asks LTA for a fresh timetable link, downloads gtfs_schedule.zip, saves a copy to
         * Downloads, and returns a short summary of the files inside (as JSON) to the page.
         */
        @JavascriptInterface
        public void downloadTimetable(final String id) {
            new Thread(() -> {
                int code = 200;
                JSONObject out = new JSONObject();
                try {
                    // 1. Ask LTA for the temporary link.
                    HttpURLConnection c = open("https://datamall2.mytransport.sg/ltaodataservice/GTFSScheduleTrain", true);
                    int status = c.getResponseCode();
                    if (status != 200) {
                        code = status;
                        throw new java.io.IOException("LTA returned " + status);
                    }
                    String json = readAll(c.getInputStream());
                    c.disconnect();
                    JSONObject root = new JSONObject(json);
                    Object v = root.opt("value");
                    JSONObject item = v instanceof org.json.JSONArray ? ((org.json.JSONArray) v).getJSONObject(0)
                            : v instanceof JSONObject ? (JSONObject) v : root;
                    String link = item.optString("link", item.optString("Link", ""));
                    URL lu = new URL(link);
                    if (!"https".equals(lu.getProtocol()) || !LTA_FILES_HOST.equals(lu.getHost())) {
                        throw new java.io.IOException("Unexpected download link");
                    }

                    // 2. Download the zip to the app's cache.
                    java.io.File zip = new java.io.File(getCacheDir(), "gtfs_schedule.zip");
                    HttpURLConnection d = (HttpURLConnection) lu.openConnection();
                    d.setConnectTimeout(15000);
                    d.setReadTimeout(60000);
                    try (InputStream in = d.getInputStream();
                         java.io.OutputStream fo = new java.io.FileOutputStream(zip)) {
                        copy(in, fo);
                    }
                    d.disconnect();
                    out.put("size", zip.length());

                    // 3. Save a copy to Downloads (Android 10 and newer).
                    String name = "lta_gtfs_schedule_" + new java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(new java.util.Date()) + ".zip";
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        android.content.ContentValues cv = new android.content.ContentValues();
                        cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                        cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                        cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download");
                        Uri dest = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        try (InputStream in = new java.io.FileInputStream(zip);
                             java.io.OutputStream os = getContentResolver().openOutputStream(dest)) {
                            copy(in, os);
                        }
                        out.put("saved", "Download/" + name);
                    } else {
                        out.put("saved", "");
                    }

                    // 4. Summarise: each file, its size, line count and first lines.
                    org.json.JSONArray files = new org.json.JSONArray();
                    try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip)) {
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                        while (en.hasMoreElements()) {
                            java.util.zip.ZipEntry e = en.nextElement();
                            if (e.isDirectory()) continue;
                            JSONObject f = new JSONObject();
                            f.put("name", e.getName());
                            f.put("size", e.getSize());
                            org.json.JSONArray head = new org.json.JSONArray();
                            int lines = 0;
                            try (java.io.BufferedReader r = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(zf.getInputStream(e), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = r.readLine()) != null) {
                                    if (lines < 4) head.put(line.length() > 200 ? line.substring(0, 200) : line);
                                    lines++;
                                }
                            }
                            f.put("lines", lines);
                            f.put("head", head);
                            files.put(f);
                        }
                    }
                    out.put("files", files);
                } catch (Exception e) {
                    if (code == 200) code = 0;
                    try {
                        out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                    } catch (Exception ignored) {
                    }
                }
                final String js = "window.__native(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(out.toString()) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }
    }

    /** The LTA DataMall key, unscrambled in memory from the build setting LTA_KEY_X. */
    private synchronized String ltaKey() {
        if (cachedKey == null) cachedKey = unscramble(BuildConfig.LTA_KEY_X);
        return cachedKey;
    }

    /** The OpenAI API key for the Ask tab, from the build setting AI_KEY_X ("" if none was set). */
    private synchronized String aiKey() {
        if (cachedAiKey == null) cachedAiKey = unscramble(BuildConfig.AI_KEY_X);
        return cachedAiKey;
    }

    /** Keys are stored scrambled with a mask made from the app's original package name (the same for MRT Tracker). */
    static String unscramble(String hx) {
        try {
            if (hx == null || hx.isEmpty()) return "";
            byte[] mask = java.security.MessageDigest.getInstance("SHA-256").digest("sg.mrttracker.app".getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[hx.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (Integer.parseInt(hx.substring(i * 2, i * 2 + 2), 16) ^ mask[i % mask.length]);
            }
            return new String(out, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }



    /**
     * LTA's live train feeds (GTFS Realtime): "trips" (delays and cancellations) or "alerts" (service alerts).
     * Asks LTA for the temporary link, downloads the feed and hands it to the page as base64.
     */
    void gtfsRealtimeFetch(final String id, final String kind) {
        new Thread(() -> {
            int code = 0;
            String body = "";
            try {
                if (ltaKey().isEmpty()) throw new java.io.IOException("no key");
                String ep = "alerts".equals(kind) ? "GTFSRealTimeTrainServiceAlerts" : "GTFSRealtimeTrainTripUpdates";
                HttpURLConnection c = open("https://datamall2.mytransport.sg/ltaodataservice/" + ep, true);
                int status = c.getResponseCode();
                if (status != 200) { c.disconnect(); code = status; throw new java.io.IOException("LTA returned " + status); }
                String json = readAll(c.getInputStream());
                c.disconnect();
                JSONObject root = new JSONObject(json);
                Object v = root.opt("value");
                JSONObject item = v instanceof org.json.JSONArray ? ((org.json.JSONArray) v).getJSONObject(0)
                        : v instanceof JSONObject ? (JSONObject) v : root;
                URL lu = new URL(item.optString("link", item.optString("Link", "")));
                if (!"https".equals(lu.getProtocol()) || !LTA_FILES_HOST.equals(lu.getHost())) throw new java.io.IOException("Unexpected link");
                HttpURLConnection d = (HttpURLConnection) lu.openConnection();
                d.setConnectTimeout(10000);
                d.setReadTimeout(20000);
                code = d.getResponseCode();
                if (code == 200) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    try (InputStream in = d.getInputStream()) {
                        byte[] buf = new byte[16384];
                        int n, total = 0;
                        while ((n = in.read(buf)) > 0) {
                            total += n;
                            if (total > 8 * 1024 * 1024) throw new java.io.IOException("Feed too large");
                            bo.write(buf, 0, n);
                        }
                    }
                    body = android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
                }
                d.disconnect();
            } catch (Exception e) {
                if (code == 200) code = 0;
                body = "";
            }
            final String js = "window.__native(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(body) + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        }).start();
    }

    private HttpURLConnection open(String url, boolean withKey) throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "MRTTracker/1.0 (Android)");
        conn.setRequestProperty("Accept", "application/json");
        if (withKey) conn.setRequestProperty("AccountKey", ltaKey());
        return conn;
    }

    /** Reads a YouTube page (www.youtube.com only), up to `max` bytes, as a browser would ask for it. */
    private static String youtubeText(String url, int max) throws java.io.IOException {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol()) || !"www.youtube.com".equals(u.getHost())) throw new SecurityException("Host not allowed");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
            c.setRequestProperty("Accept-Language", "en-SG,en;q=0.9");
            c.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+");   // skips the cookie-consent page some countries get
            if (c.getResponseCode() != 200) throw new java.io.IOException("YouTube returned " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            try (InputStream in = c.getInputStream()) {
                while ((n = in.read(buf)) > 0 && out.size() < max) out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }

    private static String firstMatch(String text, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** "Trains &amp; more" → "Trains & more". */
    private static String unescape(String s) {
        if (s == null) return "";
        return android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
