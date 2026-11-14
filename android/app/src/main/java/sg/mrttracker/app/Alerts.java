package sg.mrttracker.app;

import android.app.AlarmManager;
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
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MRT Dev notifications: disruptions on the lines at your saved stations, and trip reminders
 * from the planned timetable. Nothing here uses your location.
 */
final class Alerts {
    static final String CH_DISRUPT = "disruptions";
    static final String CH_TRIP = "trip";
    private static final int DISRUPT_ID = 2001;
    private static final int TRIP_BASE = 3000;
    private static final int TRIP_MAX = 8;
    private static final int JOB_ID = 7301;
    private static final String PREFS = "alerts";
    private static final String DATAMALL = "https://datamall2.mytransport.sg/ltaodataservice/TrainServiceAlerts";

    private Alerts() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel d = new NotificationChannel(CH_DISRUPT, "Line disruptions", NotificationManager.IMPORTANCE_HIGH);
        d.setDescription("LTA disruption notices for the lines at the stations saved on Home");
        nm.createNotificationChannel(d);
        NotificationChannel t = new NotificationChannel(CH_TRIP, "Trip reminders", NotificationManager.IMPORTANCE_HIGH);
        t.setDescription("When to board and when to get off, from the planned timetable");
        nm.createNotificationChannel(t);
    }

    /** True when this app may show notifications (Android 13+ asks the person first). */
    static boolean allowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    static void post(Context c, String channel, int id, String title, String text) {
        try {
            channels(c);
            if (!allowed(c)) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_stat_train)
                    .setContentTitle(clip(title, 120))
                    .setContentText(clip(text, 400))
                    .setStyle(new Notification.BigTextStyle().bigText(clip(text, 400)))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setShowWhen(true);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
            nm.notify(id, b.build());
        } catch (Exception ignored) {
        }
    }

    private static String clip(String s, int n) {
        s = s == null ? "" : s.trim();
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    /* ---------- disruptions ---------- */

    /** Posts once per new disruption (sig names the affected stretches); an empty sig means all clear. */
    static synchronized void disruption(Context c, String sig, String title, String text) {
        SharedPreferences p = prefs(c);
        if (sig == null || sig.isEmpty()) {
            p.edit().putString("dis_sig", "").apply();
            return;
        }
        if (sig.equals(p.getString("dis_sig", ""))) return;
        p.edit().putString("dis_sig", sig).apply();
        if (!p.getBoolean("on", true)) return;
        post(c, CH_DISRUPT, DISRUPT_ID, title, text);
    }

    /** The page says which LTA lines to watch ({"on":true,"lines":["NSL","CCL"]}); background checks follow. */
    static void setLines(Context c, String json) {
        try {
            JSONObject j = new JSONObject(json);
            JSONArray ls = j.optJSONArray("lines");
            StringBuilder sb = new StringBuilder();
            if (ls != null) for (int i = 0; i < ls.length() && i < 30; i++) {
                String l = ls.optString(i, "").replaceAll("[^A-Z]", "");
                if (!l.isEmpty()) sb.append(sb.length() > 0 ? "," : "").append(l);
            }
            prefs(c).edit().putBoolean("on", j.optBoolean("on", true)).putString("lines", sb.toString()).apply();
        } catch (Exception ignored) {
        }
        schedule(c);
    }

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            SharedPreferences p = prefs(c);
            boolean want = p.getBoolean("on", true) && !p.getString("lines", "").isEmpty() && !BuildConfig.LTA_KEY_X.isEmpty();
            if (!want) {
                js.cancel(JOB_ID);
                return;
            }
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, AlertJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(30L * 60 * 1000)       // about every half hour, when Android allows
                    .setPersisted(true)
                    .build());
        } catch (Exception ignored) {
        }
    }

    /** Background check (the app closed): LTA's train service alerts for the saved lines. */
    static void check(Context c) {
        SharedPreferences p = prefs(c);
        String saved = p.getString("lines", "");
        if (!p.getBoolean("on", true) || saved.isEmpty()) return;
        List<String> lines = java.util.Arrays.asList(saved.split(","));
        HttpURLConnection conn = null;
        try {
            String key = MainActivity.unscramble(BuildConfig.LTA_KEY_X);
            if (key.isEmpty()) return;
            conn = (HttpURLConnection) new URL(DATAMALL).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "MRTTracker/1.0 (Android)");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("AccountKey", key);
            if (conn.getResponseCode() != 200) return;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 1_000_000) out.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            Object v = root.opt("value");
            JSONObject val = v instanceof JSONArray ? ((JSONArray) v).optJSONObject(0) : v instanceof JSONObject ? (JSONObject) v : root;
            if (val == null) return;
            List<String> sigs = new ArrayList<>();
            List<String> names = new ArrayList<>();
            if (val.optInt("Status", 1) == 2) {
                JSONArray segs = val.optJSONArray("AffectedSegments");
                if (segs != null) for (int i = 0; i < segs.length(); i++) {
                    JSONObject sg = segs.optJSONObject(i);
                    if (sg == null) continue;
                    String line = sg.optString("Line", "").toUpperCase(java.util.Locale.ROOT);
                    if (!lines.contains(line)) continue;
                    List<String> codes = new ArrayList<>();
                    for (String s : sg.optString("Stations", "").split(",")) {
                        s = s.trim();
                        if (s.isEmpty()) continue;
                        codes.add(s.equals("CE1") ? "CC34" : s.equals("CE2") ? "CC33" : s);
                    }
                    if (codes.isEmpty()) continue;
                    sigs.add(line + ":" + codes.get(0) + "-" + codes.get(codes.size() - 1));
                    String nm = lineName(line);
                    if (!names.contains(nm)) names.add(nm);
                }
            }
            Collections.sort(sigs);
            String sig = android.text.TextUtils.join("|", sigs);
            String msg = "";
            JSONArray ms = val.optJSONArray("Message");
            if (ms != null && ms.length() > 0) {
                JSONObject m = ms.optJSONObject(ms.length() - 1);
                if (m != null) msg = m.optString("Content", "");
            }
            disruption(c, sig, "Disruption: " + android.text.TextUtils.join(", ", names),
                    msg.isEmpty() ? "Trains are affected on a line at your saved stations. Open the app for details." : msg);
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String lineName(String l) {
        switch (l) {
            case "NSL": return "North-South Line";
            case "EWL": return "East-West Line";
            case "NEL": return "North East Line";
            case "CCL": return "Circle Line";
            case "DTL": return "Downtown Line";
            case "TEL": return "Thomson-East Coast Line";
            case "BPL": return "Bukit Panjang LRT";
            case "STL": return "Sengkang LRT";
            case "PTL": return "Punggol LRT";
            case "CGL": return "Changi Airport Branch";
            case "CEL": return "Circle Line Extension";
            default: return l;
        }
    }

    /* ---------- trip reminders (AlarmManager, inexact, no special permission) ---------- */

    static void tripSchedule(Context c, String json) {
        tripCancel(c);
        try {
            JSONArray a = new JSONArray(json);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long now = System.currentTimeMillis();
            for (int i = 0, k = 0; i < a.length() && k < TRIP_MAX; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at", 0);
                if (at < now + 5000 || at > now + 6L * 3600 * 1000) continue;     // only the next few hours
                Intent it = new Intent(c, TripAlarm.class).setAction("sg.mrttracker.app.TRIP_" + k)
                        .putExtra("title", clip(o.optString("title", ""), 120))
                        .putExtra("text", clip(o.optString("text", ""), 300))
                        .putExtra("id", TRIP_BASE + k);
                PendingIntent pi = PendingIntent.getBroadcast(c, TRIP_BASE + k, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                k++;
            }
        } catch (Exception ignored) {
        }
    }

    static void tripCancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        for (int k = 0; k < TRIP_MAX; k++) {
            Intent it = new Intent(c, TripAlarm.class).setAction("sg.mrttracker.app.TRIP_" + k);
            PendingIntent pi = PendingIntent.getBroadcast(c, TRIP_BASE + k, it, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) {
                if (am != null) am.cancel(pi);
                pi.cancel();
            }
        }
    }
}
