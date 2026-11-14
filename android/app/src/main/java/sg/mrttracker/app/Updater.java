package sg.mrttracker.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * In-app updates.
 *
 * Every build publishes the app and a small version.json to a public "releases" repo on GitHub
 * (BuildConfig.UPDATE_REPO, e.g. yourname/mrt-tracker-releases). The app checks that file when it
 * opens and every few hours in the background, shows a notification when a newer version exists,
 * and installs it in one tap.
 *
 * Safety: downloads only come from GitHub over HTTPS, and Android itself refuses to install an
 * update that isn't signed with this app's own signing key, so a tampered file can't be installed.
 */
final class Updater {

    static final String CHANNEL = "updates";
    static final int NOTIFY_ID = 1001;
    static final int JOB_ID = 2001;
    private static final long MAX_APK = 60L * 1024 * 1024;

    private Updater() {}

    static boolean enabled() {
        return !BuildConfig.UPDATE_REPO.isEmpty();
    }

    /** MRT Tracker (beta) takes the latest release; MRT Dev takes the "dev" pre-release. */
    private static boolean dev() {
        return "dev".equals(BuildConfig.CHANNEL);
    }

    private static String base() {
        return "https://github.com/" + BuildConfig.UPDATE_REPO + (dev() ? "/releases/download/dev/" : "/releases/latest/download/");
    }

    /** The version waiting on GitHub, or null if none can be read. */
    static JSONObject latest() {
        if (!enabled()) return null;
        try {
            byte[] b = get(base() + "version.json", 64 * 1024, null);
            JSONObject j = new JSONObject(new String(b, StandardCharsets.UTF_8));
            return j.has("versionCode") ? j : null;
        } catch (Exception e) {
            return null;
        }
    }

    static long installedCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** Newer version info if one is available, otherwise null. */
    static JSONObject newer(Context c) {
        JSONObject j = latest();
        if (j == null) return null;
        return j.optLong("versionCode", 0) > installedCode(c) ? j : null;
    }

    /* ---------- background checks and notifications ---------- */

    static void schedule(Context c) {
        if (!enabled()) return;
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;   // already scheduled
            JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(6L * 60 * 60 * 1000)          // about every 6 hours
                    .setPersisted(true)                         // survives restarts
                    .build();
            js.schedule(info);
        } catch (Exception ignored) {
        }
    }

    /** Posts "New version available" once per new version. */
    static void notifyIfNew(Context c) {
        JSONObject j = newer(c);
        if (j == null) return;
        long code = j.optLong("versionCode", 0);
        SharedPreferences p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (p.getLong("notified_code", 0) == code) return;
        if (showNotification(c, j.optString("versionName", ""))) p.edit().putLong("notified_code", code).apply();
    }

    static boolean showNotification(Context c, String versionName) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent open = new Intent(c, MainActivity.class).putExtra("show_update", true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_stat_train)
                    .setContentTitle("New MRT Tracker version")
                    .setContentText(versionName.isEmpty() ? "Tap to update" : "Version " + versionName + " is ready. Tap to update.")
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(NOTIFY_ID, b.build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------- download and install ---------- */

    interface Progress { void on(int percent); }

    /**
     * Downloads the new app into Android's installer, then asks Android to install it.
     * Android shows its usual "Do you want to update this app?" screen to confirm.
     */
    static void downloadAndInstall(Context c, Progress progress) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            try (OutputStream out = s.openWrite("update.apk", 0, -1)) {
                get(base() + (dev() ? "mrt-dev.apk" : "mrt-tracker.apk"), MAX_APK, new Sink(out, progress));
                s.fsync(out);
            }
            Intent i = new Intent(c, InstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent done = PendingIntent.getBroadcast(c, id, i, flags);
            s.commit(done.getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
    }

    /* ---------- HTTPS, GitHub only ---------- */

    private static final class Sink {
        final OutputStream out; final Progress progress;
        Sink(OutputStream out, Progress progress) { this.out = out; this.progress = progress; }
    }

    private static boolean allowedHost(String h) {
        h = h.toLowerCase(Locale.ROOT);
        return h.equals("github.com") || h.endsWith(".githubusercontent.com");
    }

    /** GET with redirects followed by hand, so every hop is checked against GitHub's hosts. */
    private static byte[] get(String url, long max, Sink sink) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(url);
            if (!"https".equals(u.getProtocol()) || !allowedHost(u.getHost())) throw new IOException("Host not allowed");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "MRTTracker-Updater");
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Bad redirect");
                url = new URL(u, loc).toString();
                continue;
            }
            if (code != 200) { c.disconnect(); throw new IOException("HTTP " + code); }
            long total = c.getContentLengthLong();
            if (total > max) { c.disconnect(); throw new IOException("Too large"); }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream mem = sink == null ? new ByteArrayOutputStream() : null;
                OutputStream out = sink == null ? mem : sink.out;
                byte[] buf = new byte[32768];
                long got = 0; int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    got += n;
                    if (got > max) throw new IOException("Too large");
                    out.write(buf, 0, n);
                    if (sink != null && sink.progress != null && total > 0) {
                        int pct = (int) (got * 100 / total);
                        if (pct != last) { last = pct; sink.progress.on(pct); }
                    }
                }
                return mem == null ? null : mem.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }
}
