package sg.mrttracker.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Home screen widget: a station's next trains each way, with the platform, and how late LTA's live feed says
 * they are. Each widget shows the station picked when it was added (WidgetSetup), or the first station saved on
 * Home. It works the times out itself from LTA's timetable (the same one the app uses), so it keeps going with
 * the app closed. While the app is open, it also sends LTA's live delays and any disruption, used for 15 and
 * 30 minutes. Tapping it opens that station in the app.
 */
public class TrainWidget extends AppWidgetProvider {
    static final String PREFS = "widget";
    private static final String TICK = "sg.mrttracker.app.WIDGET_TICK";
    static final String EXTRA_STATION = "open_station";
    private static final int[] ROW = {R.id.w_r0, R.id.w_r1, R.id.w_r2, R.id.w_r3, R.id.w_r4, R.id.w_r5};
    private static final int[] BAR = {R.id.w_b0, R.id.w_b1, R.id.w_b2, R.id.w_b3, R.id.w_b4, R.id.w_b5};
    private static final int[] DST = {R.id.w_d0, R.id.w_d1, R.id.w_d2, R.id.w_d3, R.id.w_d4, R.id.w_d5};
    private static final int[] TIM = {R.id.w_t0, R.id.w_t1, R.id.w_t2, R.id.w_t3, R.id.w_t4, R.id.w_t5};
    private static final long LIVE_MS = 15 * 60_000, DIS_MS = 30 * 60_000;
    private static final int MUTED = 0xFFAEB8C3, LATE = 0xFFFFC46B;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** From the page: {at, st: first saved station, rows (its next trains, a fallback), dis, disLines, live: {tid: delay s}, gone: [tid]}. */
    static void save(Context c, String json) {
        prefs(c).edit().putString("data", json).apply();
        updateAll(c);
    }

    /** From the page, once in a while: {ph: [public holidays], lines: {NSL: {c: colour, l: label, dest: [two ends], slow: [s per stop, …]}}}. */
    static void saveMeta(Context c, String json) {
        if (!json.equals(prefs(c).getString("meta", ""))) {
            prefs(c).edit().putString("meta", json).apply();
            clearIndex(c);
            updateAll(c);
        }
    }

    /** The station a widget shows ("" = the first one saved on Home). */
    static void setStation(Context c, int id, String name) {
        prefs(c).edit().putString("w_" + id, name == null ? "" : name).apply();
    }

    static void updateAll(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, TrainWidget.class));
            if (ids == null || ids.length == 0) return;
            for (int id : ids) m.updateAppWidget(id, build(c, id));
            tick(c, true);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, build(c, id));
        tick(c, true);
    }

    @Override
    public void onDeleted(Context c, int[] ids) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int id : ids) e.remove("w_" + id);
        e.apply();
    }

    @Override
    public void onEnabled(Context c) {
        tick(c, true);
    }

    @Override
    public void onDisabled(Context c) {
        tick(c, false);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (TICK.equals(intent.getAction())) updateAll(c);
        else super.onReceive(c, intent);
    }

    /** Counts the minutes down every few minutes while the screen is on (inexact and not waking the phone). */
    private static void tick(Context c, boolean on) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent it = new Intent(c, TrainWidget.class).setAction(TICK);
        PendingIntent pi = PendingIntent.getBroadcast(c, 4100, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (on) am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 60_000, 5 * 60_000, pi);
        else am.cancel(pi);
    }

    private static JSONObject json(String s) {
        try {
            return new JSONObject(s == null || s.isEmpty() ? "{}" : s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static RemoteViews build(Context c, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_trains);
        JSONObject data = json(prefs(c).getString("data", "")), meta = json(prefs(c).getString("meta", ""));
        String chosen = prefs(c).getString("w_" + id, ""), st = chosen.isEmpty() ? data.optString("st", "") : chosen;
        long now = System.currentTimeMillis(), at = data.optLong("at", 0);

        // tap: open this station in the app
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!st.isEmpty()) open.putExtra(EXTRA_STATION, st);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 4000 + id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        if (st.isEmpty()) {
            v.setTextViewText(R.id.w_title, c.getString(R.string.app_name));
            v.setTextViewText(R.id.w_upd, "");
            v.setTextViewText(R.id.w_msg, "Open the app and save a station on Home, or hold this widget to pick a station.");
            v.setViewVisibility(R.id.w_msg, View.VISIBLE);
            for (int r : ROW) v.setViewVisibility(r, View.GONE);
            v.setViewVisibility(R.id.w_dis, View.GONE);
            return v;
        }
        v.setViewVisibility(R.id.w_msg, View.GONE);
        v.setTextViewText(R.id.w_title, st);

        JSONObject live = now - at < LIVE_MS ? data.optJSONObject("live") : null;
        Set<String> gone = new HashSet<>();
        JSONArray g = now - at < LIVE_MS ? data.optJSONArray("gone") : null;
        if (g != null) for (int i = 0; i < g.length(); i++) gone.add(g.optString(i));

        List<Row> rows = rowsFor(c, st, meta, now, live, gone);
        boolean fromTimetable = rows != null;
        if (rows == null && st.equals(data.optString("st", ""))) rows = pageRows(data, now);   // no timetable: what the app sent
        if (rows == null) rows = new ArrayList<>();

        boolean any = false;
        for (int i = 0; i < ROW.length; i++) {
            Row r = i < rows.size() ? rows.get(i) : null;
            if (r == null) {
                v.setViewVisibility(ROW[i], View.GONE);
                continue;
            }
            v.setViewVisibility(ROW[i], View.VISIBLE);
            v.setInt(BAR[i], "setBackgroundColor", r.color);
            SpannableStringBuilder d = new SpannableStringBuilder(r.to);
            if (!r.plat.isEmpty()) {
                int s0 = d.length();
                d.append("  Plat ").append(r.plat);
                d.setSpan(new ForegroundColorSpan(MUTED), s0, d.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            v.setTextViewText(DST[i], d);
            SpannableStringBuilder t = new SpannableStringBuilder();
            for (int k = 0; k < r.times.size() && k < 3; k++) {
                double m = (r.times.get(k) - now) / 60000.0;
                if (k == 0) t.append(m < 0.75 ? "Now" : Math.round(m) + " min");
                else t.append(" · ").append(String.valueOf(Math.round(m)));
            }
            if (r.late >= 60) {
                int s0 = t.length();
                t.append(" +").append(String.valueOf(Math.round(r.late / 60.0)));
                t.setSpan(new ForegroundColorSpan(LATE), s0, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (!r.times.isEmpty()) any = true;
            v.setTextViewText(TIM[i], r.times.isEmpty() ? "–" : t);
        }

        // a disruption on one of this station's lines, if the app saw one in the last half hour
        String dis = "";
        if (now - at < DIS_MS) {
            JSONArray dl = data.optJSONArray("disLines");
            if (dl != null) for (Row r : rows) for (int i = 0; i < dl.length(); i++) {
                if (dl.optString(i).equals(r.line) && dis.isEmpty()) dis = "Disruption on the " + r.label + ". Tap for details.";
            }
            if (dis.isEmpty() && st.equals(data.optString("st", ""))) dis = data.optString("dis", "");
        }
        v.setViewVisibility(R.id.w_dis, dis.isEmpty() ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.w_dis, dis);

        String hm = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(new java.util.Date(now));
        v.setTextViewText(R.id.w_upd, !any ? "No trains now" : live != null ? "live · " + hm : fromTimetable ? "timetable · " + hm : "updated " + hm);
        return v;
    }

    static final class Row {
        String line = "", label = "", to = "", plat = "";
        int color = Color.GRAY;
        List<Long> times = new ArrayList<>();
        double late = 0;
    }

    /** The old way: next trains the app sent (when there's no timetable to work them out from). */
    private static List<Row> pageRows(JSONObject data, long now) {
        JSONArray rows = data.optJSONArray("rows");
        if (rows == null) return null;
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            Row w = new Row();
            try {
                w.color = Color.parseColor(r.optString("c", "#888888"));
            } catch (Exception ignored) {
            }
            w.to = r.optString("to", "");
            w.plat = r.optString("plat", "");
            JSONArray t = r.optJSONArray("t");
            if (t != null) for (int k = 0; k < t.length(); k++) if (t.optLong(k, 0) >= now - 30_000) w.times.add(t.optLong(k, 0));
            out.add(w);
        }
        return out;
    }

    // ---------- next trains from the timetable ----------

    /** Midnight in Singapore at the start of `day` (yyyy-MM-dd), in ms since 1970. */
    private static long dayStart(String day) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Singapore"));
            return f.parse(day).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String sgDay(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Singapore"));
        return f.format(new java.util.Date(ms));
    }

    /**
     * The station's next trains, each line and direction a row; null if there's no timetable (or no station list).
     * Uses an index of the station's departures, built from the timetable once and kept in a file.
     */
    private static List<Row> rowsFor(Context c, String st, JSONObject meta, long now, JSONObject live, Set<String> gone) {
        JSONObject idx = index(c, st, meta);
        if (idx == null) return null;
        JSONArray services = idx.optJSONArray("services");
        JSONObject except = idx.optJSONObject("except");
        Set<String> ph = new HashSet<>();
        JSONArray pa = meta.optJSONArray("ph");
        if (pa != null) for (int i = 0; i < pa.length(); i++) ph.add(pa.optString(i));
        // today's service day and yesterday's (its trains after midnight)
        String today = sgDay(now), yday = sgDay(now - 86_400_000L);
        boolean[] onT = active(services, except, ph, today), onY = active(services, except, ph, yday);
        long t0 = dayStart(today), y0 = dayStart(yday);

        List<Row> out = new ArrayList<>();
        JSONArray rows = idx.optJSONArray("rows");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            Row w = new Row();
            w.line = r.optString("line");
            w.label = r.optString("label", w.line);
            w.plat = r.optString("plat", "");
            try {
                w.color = Color.parseColor(r.optString("c", "#888888"));
            } catch (Exception ignored) {
            }
            String dflt = r.optString("dest", "");
            JSONArray deps = r.optJSONArray("deps");             // [service, seconds, destination ("" = the usual end), trip id]
            List<long[]> found = new ArrayList<>();
            List<String> toOf = new ArrayList<>();
            List<Double> lateOf = new ArrayList<>();
            for (int k = 0; deps != null && k < deps.length(); k++) {
                JSONArray d = deps.optJSONArray(k);
                int svc = d.optInt(0);
                long sec = d.optLong(1);
                String tid = d.optString(3, "");
                if (gone.contains(tid)) continue;                  // LTA says it's cancelled
                double late = live != null ? live.optDouble(tid, 0) : 0;
                for (int day = 0; day < 2; day++) {
                    boolean[] on = day == 0 ? onT : onY;
                    if (svc < 0 || svc >= on.length || !on[svc]) continue;
                    long t = (day == 0 ? t0 : y0) + sec * 1000 + Math.round(late * 1000);
                    if (t < now - 30_000 || t > now + 3 * 3600_000L) continue;
                    found.add(new long[]{t});
                    toOf.add(d.optString(2, ""));
                    lateOf.add(late);
                }
            }
            // the soonest three
            Integer[] order = new Integer[found.size()];
            for (int k = 0; k < order.length; k++) order[k] = k;
            java.util.Arrays.sort(order, (a, b) -> Long.compare(found.get(a)[0], found.get(b)[0]));
            for (int k = 0; k < order.length && k < 3; k++) w.times.add(found.get(order[k])[0]);
            String to0 = order.length > 0 ? toOf.get(order[0]) : "";
            w.to = to0.isEmpty() ? dflt : to0 + " (ends there)";
            w.late = order.length > 0 ? lateOf.get(order[0]) : 0;
            out.add(w);
        }
        return out;
    }

    /** Which services run on `day` (yyyy-MM-dd): the weekday, with public holidays as Sundays, then LTA's own date changes. */
    private static boolean[] active(JSONArray services, JSONObject except, Set<String> ph, String day) {
        int n = services == null ? 0 : services.length();
        boolean[] on = new boolean[n];
        String ymd = day.replace("-", "");
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(dayStart(day) + 12 * 3600_000L);
        int dow = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7;       // Monday = 0
        if (ph.contains(day)) dow = 6;
        for (int i = 0; i < n; i++) {
            JSONArray s = services.optJSONArray(i);
            String days = s.optString(1, "0000000");
            on[i] = days.length() > dow && days.charAt(dow) == '1' && ymd.compareTo(s.optString(2, "")) >= 0 && ymd.compareTo(s.optString(3, "")) <= 0;
        }
        JSONArray ex = except == null ? null : except.optJSONArray(ymd);
        for (int i = 0; ex != null && i < ex.length(); i++) {
            JSONArray e = ex.optJSONArray(i);
            int k = e.optInt(0, -1);
            if (k >= 0 && k < n) on[k] = e.optInt(1) == 1;
        }
        return on;
    }

    private static void clearIndex(Context c) {
        File[] fs = c.getFilesDir().listFiles();
        if (fs != null) for (File f : fs) if (f.getName().startsWith("widget-idx-")) f.delete();
    }

    /**
     * The station's departures from the timetable: {ver, services, except, rows: [{line, label, c, plat, dest, deps}]}.
     * Built once per timetable and kept in a file, so the widget doesn't read the whole timetable every few minutes.
     */
    private static JSONObject index(Context c, String st, JSONObject meta) {
        String ver = Timetable.version(c) + "|" + meta.toString().hashCode();
        File f = new File(c.getFilesDir(), "widget-idx-" + Integer.toHexString(st.hashCode()) + ".json");
        try {
            if (f.exists()) {
                JSONObject j;
                try (InputStream in = new FileInputStream(f)) {
                    j = new JSONObject(Timetable.readAll(in));
                }
                if (ver.equals(j.optString("ver")) && st.equals(j.optString("st"))) return j;
            }
            String raw = Timetable.current(c);
            JSONObject stations = json(Timetable.prefs(c).getString("stations", ""));
            if (raw.isEmpty() || stations.length() == 0) return null;
            JSONObject tt = new JSONObject(raw), lines = tt.optJSONObject("lines"), ml = meta.optJSONObject("lines");
            if (lines == null) return null;
            JSONArray rows = new JSONArray();
            for (java.util.Iterator<String> it = stations.keys(); it.hasNext(); ) {
                String line = it.next();
                JSONObject L = lines.optJSONObject(line);
                JSONArray sl = stations.optJSONObject(line).optJSONArray("stations");
                if (L == null || sl == null) continue;
                JSONObject m = ml == null ? null : ml.optJSONObject(line);
                List<Integer> at = new ArrayList<>();
                for (int i = 0; i < sl.length(); i++) if (st.equals(sl.optJSONArray(i).optString(1))) at.add(i);
                if (at.isEmpty()) continue;
                JSONArray pats = L.optJSONArray("pats"), trips = L.optJSONArray("trips");
                JSONObject plat = L.optJSONObject("plat");
                JSONArray slow = m == null ? null : m.optJSONArray("slow"), dest = m == null ? null : m.optJSONArray("dest");
                double[] late = lateStarts(trips, pats, sl, slow);
                for (int dir = 0; dir < 2; dir++) {
                    JSONArray deps = new JSONArray();
                    String pl = "";
                    for (int i : at) {
                        JSONArray pp = plat == null ? null : plat.optJSONArray(String.valueOf(i));
                        if (pl.isEmpty() && pp != null) pl = pp.optString(dir, "");
                    }
                    double x = slow == null ? 0 : slow.optDouble(dir, 0);
                    for (int k = 0; trips != null && k < trips.length(); k++) {
                        JSONArray tr = trips.optJSONArray(k);
                        JSONArray p = pats.optJSONArray(tr.optInt(1));
                        int f0 = p.optInt(0), d = p.optInt(1), h = p.optInt(4, -1);
                        if ((d > 0 ? 0 : 1) != dir) continue;
                        JSONArray arr = p.optJSONArray(2);
                        for (int i : at) {
                            int q = (i - f0) * d;
                            if (q < 0 || q >= arr.length() - 1) continue;          // not on this run, or where it ends
                            // like the app's ttAt(): a run that waits for its train leaves late and catches up by its end
                            double runEnd = arr.optLong(arr.length() - 1) + (arr.length() - 1) * x, tl = late[k];
                            double here = arr.optLong(q) + q * x;
                            long sec = tr.optLong(0) + Math.round(tl > 0 && runEnd > tl ? tl + here * (runEnd - tl) / runEnd : here);
                            String to = "";
                            if (h >= 0 && h < sl.length() && h != (dir == 0 ? sl.length() - 1 : 0)) {
                                String hn = sl.optJSONArray(h).optString(1);
                                if (dest == null || !hn.equals(dest.optString(dir))) to = hn;   // special-bound: ends partway
                            }
                            deps.put(new JSONArray().put(tr.optInt(2)).put(sec).put(to).put(tr.optString(3)));
                            break;
                        }
                    }
                    if (deps.length() == 0) continue;
                    JSONObject r = new JSONObject();
                    r.put("line", line);
                    r.put("label", m == null ? line : m.optString("l", line));
                    r.put("c", m == null ? "#888888" : m.optString("c", "#888888"));
                    r.put("plat", pl);
                    r.put("dest", dest == null ? (dir == 0 ? sl.optJSONArray(sl.length() - 1).optString(1) : sl.optJSONArray(0).optString(1)) : dest.optString(dir));
                    r.put("deps", deps);
                    rows.put(r);
                }
            }
            JSONObject out = new JSONObject();
            out.put("ver", ver);
            out.put("st", st);
            out.put("services", tt.optJSONArray("services"));
            out.put("except", tt.optJSONObject("except"));
            out.put("rows", rows);
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * How late each trip leaves (seconds), as the app's ttLinks() works it out: a train can't start its next trip
     * (the same train slot's next one, from the station where this one ends) before it has got in and turned round.
     * With the slower North-South Line trains (SLOW) that's a few minutes on some trips.
     */
    private static double[] lateStarts(JSONArray trips, JSONArray pats, JSONArray sl, JSONArray slow) {
        int n = trips == null ? 0 : trips.length();
        double[] late = new double[n];
        java.util.Map<String, List<Integer>> by = new java.util.HashMap<>();
        for (int k = 0; k < n; k++) {
            JSONArray tr = trips.optJSONArray(k);
            String[] q = tr.optString(3, "").split("_");
            int m = q.length;
            if (m < 4 || !q[m - 1].matches("\\d+") || !q[m - 2].matches("\\d+")) continue;
            String key = tr.optInt(2) + "|" + Integer.parseInt(q[m - 2]);   // service and train slot
            by.computeIfAbsent(key, z -> new ArrayList<>()).add(k);
        }
        for (List<Integer> runs : by.values()) {
            runs.sort((a, b) -> Long.compare(trips.optJSONArray(a).optLong(0), trips.optJSONArray(b).optLong(0)));
            for (int i = 0; i + 1 < runs.size(); i++) {
                JSONArray a = trips.optJSONArray(runs.get(i)), b = trips.optJSONArray(runs.get(i + 1));
                JSONArray pa = pats.optJSONArray(a.optInt(1)), pb = pats.optJSONArray(b.optInt(1));
                JSONArray aa = pa.optJSONArray(2), ba = pb.optJSONArray(2);
                int endIdx = pa.optInt(0) + pa.optInt(1) * (aa.length() - 1), startIdx = pb.optInt(0);
                if (endIdx < 0 || endIdx >= sl.length() || startIdx < 0 || startIdx >= sl.length()) continue;
                if (!sl.optJSONArray(endIdx).optString(1).equals(sl.optJSONArray(startIdx).optString(1))) continue;
                double xa = slow == null ? 0 : slow.optDouble(pa.optInt(1) > 0 ? 0 : 1, 0), xb = slow == null ? 0 : slow.optDouble(pb.optInt(1) > 0 ? 0 : 1, 0);
                double gets = a.optLong(0) + aa.optLong(aa.length() - 1) + (aa.length() - 1) * xa;
                double endB = ba.optLong(ba.length() - 1) + (ba.length() - 1) * xb, w = b.optLong(0) - gets;
                if (w < -6 * 60 || w > 15 * 60) continue;                      // TT_LAYOVER_EARLY / TT_LAYOVER_MAX in the app
                late[runs.get(i + 1)] = Math.max(0, Math.min(6 * 60 + 46, Math.min(gets + 46 - b.optLong(0), endB / 2)));
            }
        }
        return late;
    }

    /** Every station name the timetable covers, A to Z (for picking a widget's station). */
    static List<String> stationNames(Context c) {
        JSONObject stations = json(Timetable.prefs(c).getString("stations", ""));
        java.util.TreeSet<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (java.util.Iterator<String> it = stations.keys(); it.hasNext(); ) {
            JSONArray sl = stations.optJSONObject(it.next()).optJSONArray("stations");
            for (int i = 0; sl != null && i < sl.length(); i++) names.add(sl.optJSONArray(i).optString(1));
        }
        return new ArrayList<>(names);
    }
}
