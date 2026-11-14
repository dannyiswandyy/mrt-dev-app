package sg.mrttracker.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LTA's train timetable, kept up to date inside the app.
 *
 * The build packs LTA's timetable (GTFS Schedule (Train), LTA DataMall) into the app as assets/timetable.json.
 * LTA changes it from time to time, so every few hours the app downloads it again, packs it the same way
 * (this is a copy of tools/build_timetable.py, giving the same timetable.json), and keeps it in the app's own
 * files. The page then uses the newest one, so a new timetable doesn't need a new version of the app.
 *
 * Nothing from LTA is saved anywhere but inside the app, and the raw download is deleted once it's packed.
 */
final class Timetable {

    private Timetable() {
    }

    static final String API = "https://datamall2.mytransport.sg/ltaodataservice/GTFSScheduleTrain";
    /** Where LTA's temporary timetable download links point. */
    static final String LTA_FILES_HOST = "dmprod-datasets.s3.ap-southeast-1.amazonaws.com";
    /** How often to look for a new timetable. */
    static final long EVERY_MS = 6L * 60 * 60 * 1000;
    private static final int JOB_ID = 7301;
    private static final String FILE = "timetable-live.json";
    private static final Object LOCK = new Object();
    private static volatile boolean running = false;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("timetable", Context.MODE_PRIVATE);
    }

    // ---------- which timetable the page uses ----------

    /** The newest timetable: the one downloaded in the app, if it's newer than this version of the app, else the packed one. */
    static String current(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        try {
            if (f.exists() && f.length() > 0 && f.lastModified() > installedAt(c)) {
                try (InputStream in = new FileInputStream(f)) {
                    return readAll(in);
                }
            }
        } catch (Exception ignored) {
        }
        try (InputStream in = c.getAssets().open("timetable.json")) {
            return readAll(in);
        } catch (Exception e) {
            return "";
        }
    }

    /** When this version of the app was installed (a newer app version packs a newer timetable). */
    private static long installedAt(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- when to check ----------

    /** The page's station list for the lines the timetable covers (the same as tools/lines.json), kept for background checks. */
    static void setStations(Context c, String json) {
        try {
            JSONObject j = new JSONObject(json);
            if (j.length() == 0) return;
            if (!json.equals(prefs(c).getString("stations", ""))) prefs(c).edit().putString("stations", json).apply();
        } catch (Exception ignored) {
        }
    }

    /** Time for another look: about 6 hours since the last good one (a little less, so the background job isn't skipped). */
    static boolean due(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong("checked", 0) >= EVERY_MS - 30L * 60 * 1000;
    }

    /** A background check every 6 hours or so, when there's a connection, even while the app is closed. */
    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || BuildConfig.LTA_KEY_X.isEmpty()) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, TimetableJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(EVERY_MS)
                    .setPersisted(true)
                    .build());
        } catch (Exception ignored) {
        }
    }

    // ---------- the check ----------

    /**
     * Downloads LTA's timetable and packs it. Returns {status: "new" | "same" | "busy" | "error", built, error}.
     * "new": a different timetable from the one the app was using, now saved; the page should load it.
     */
    static JSONObject refresh(Context c) {
        JSONObject out = new JSONObject();
        synchronized (LOCK) {
            if (running) return put(out, "status", "busy");
            running = true;
        }
        File zip = new File(c.getCacheDir(), "gtfs_schedule_live.zip");
        try {
            String stations = prefs(c).getString("stations", "");
            if (stations.isEmpty()) throw new Exception("The app hasn't been opened yet");
            String key = MainActivity.unscramble(BuildConfig.LTA_KEY_X);
            if (key.isEmpty()) throw new Exception("This version of the app has no LTA key");
            download(key, zip);
            String built = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US) {{
                setTimeZone(java.util.TimeZone.getTimeZone("Asia/Singapore"));
            }}.format(new java.util.Date());
            String packed;
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip)) {
                packed = new Packer(new JSONObject(stations)).pack(z, built);
            }
            prefs(c).edit().putLong("checked", System.currentTimeMillis()).apply();
            put(out, "built", built);
            if (packed == null) throw new Exception("LTA's timetable didn't match any line");
            if (body(packed).equals(body(current(c)))) return put(out, "status", "same");
            File f = new File(c.getFilesDir(), FILE), tmp = new File(c.getFilesDir(), FILE + ".tmp");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(packed.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) throw new Exception("Couldn't save the timetable");
            return put(out, "status", "new");
        } catch (Throwable e) {
            put(out, "status", "error");
            return put(out, "error", e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            zip.delete();
            running = false;
        }
    }

    /** The timetable without its "feed" part (when and where it was downloaded), to tell whether it changed. */
    private static String body(String json) {
        int i = json.indexOf(",\"services\":");
        return i < 0 ? json : json.substring(i);
    }

    private static JSONObject put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** LTA gives a temporary (signed) download link; if it doesn't work, ask for a fresh one, up to three times. */
    private static void download(String key, File zip) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API + "?_=" + System.nanoTime()).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("AccountKey", key);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("Cache-Control", "no-cache");
                int status = c.getResponseCode();
                if (status != 200) throw new java.io.IOException("LTA returned " + status);
                String meta = readAll(c.getInputStream());
                c.disconnect();
                String link = findLink(meta);
                if (link == null) throw new java.io.IOException("LTA returned no timetable link");
                URL lu = new URL(link);
                if (!"https".equals(lu.getProtocol()) || !LTA_FILES_HOST.equals(lu.getHost())) {
                    throw new java.io.IOException("Unexpected download link");
                }
                HttpURLConnection d = (HttpURLConnection) lu.openConnection();   // the link exactly as LTA gave it
                d.setConnectTimeout(15000);
                d.setReadTimeout(60000);
                int ds = d.getResponseCode();
                if (ds != 200) throw new java.io.IOException("The timetable download returned " + ds);
                try (InputStream in = d.getInputStream(); OutputStream fo = new FileOutputStream(zip)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                }
                d.disconnect();
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(3000L * (attempt + 1));
            }
        }
        throw last;
    }

    /** The download link in LTA's answer: JSON {"value": [{"link": ...}]}, or any https link with "gtfs" in it. */
    static String findLink(String raw) {
        try {
            String l = findLink(new org.json.JSONTokener(raw).nextValue());
            if (l != null) return l;
        } catch (Exception ignored) {
        }
        String t = raw.replace("&amp;", "&").replace("\\u0026", "&").replace("\\/", "/");
        Matcher m = Pattern.compile("https://[^\\s<>\"']*gtfs[^\\s<>\"']*", Pattern.CASE_INSENSITIVE).matcher(t);
        return m.find() ? m.group() : null;
    }

    private static String findLink(Object o) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = j.opt(k);
                String kl = k.toLowerCase(java.util.Locale.ROOT);
                if ((kl.equals("link") || kl.equals("url") || kl.equals("href")) && v instanceof String && ((String) v).startsWith("https://")) {
                    return (String) v;
                }
            }
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                String r = findLink(j.opt(it.next()));
                if (r != null) return r;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                String r = findLink(a.opt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    static String readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------- packing: the same as tools/build_timetable.py ----------

    /** Turns LTA's GTFS files into the app's timetable.json. Keep in step with tools/build_timetable.py. */
    static final class Packer {
        static final int MAX_STEP = 2;
        static final Pattern CODE_RE = Pattern.compile(
                "\\b(NS|EW|CG|NE|CC|CE|DT|TE|BP|SE|SW|PE|PW)\\s?(\\d{1,2})\\b|\\b(STC|PTC)\\b", Pattern.CASE_INSENSITIVE);
        static final Map<String, String> CODE_ALIASES = new HashMap<>();

        static {
            CODE_ALIASES.put("CE1", "CC34");
            CODE_ALIASES.put("CE2", "CC33");
        }

        final List<String> lineOrder = new ArrayList<>();
        final Map<String, List<String[]>> stations = new HashMap<>();
        final Map<String, List<Object[]>> nameIdx = new HashMap<>();   // normalised name -> [(line, index)]
        final Map<String, List<Object[]>> codeIdx = new HashMap<>();   // code -> [(line, index)]

        Packer(JSONObject lines) throws Exception {
            for (java.util.Iterator<String> it = lines.keys(); it.hasNext(); ) {
                String line = it.next();
                lineOrder.add(line);
                JSONArray st = lines.getJSONObject(line).getJSONArray("stations");
                List<String[]> l = new ArrayList<>();
                for (int i = 0; i < st.length(); i++) {
                    JSONArray s = st.getJSONArray(i);
                    String code = s.getString(0), name = s.getString(1);
                    l.add(new String[]{code, name});
                    nameIdx.computeIfAbsent(norm(name), k -> new ArrayList<>()).add(new Object[]{line, i});
                    codeIdx.computeIfAbsent(code.toUpperCase(java.util.Locale.ROOT), k -> new ArrayList<>()).add(new Object[]{line, i});
                }
                stations.put(line, l);
            }
        }

        static final Pattern P1 = Pattern.compile("\\([^)]*\\)");
        static final Pattern P2 = Pattern.compile("\\b(mrt|lrt)\\b|\\bstation\\b|\\binterchange\\b|\\bplatform\\b.*$");
        static final Pattern P3 = Pattern.compile("[^a-z0-9]");

        static String norm(String s) {
            s = s.toLowerCase(java.util.Locale.ROOT).replace("&", "and");
            s = P1.matcher(s).replaceAll(" ");
            s = P2.matcher(s).replaceAll(" ");
            return P3.matcher(s).replaceAll("");
        }

        private List<Object[]> codesIn(String t) {
            List<Object[]> found = new ArrayList<>();
            Matcher m = CODE_RE.matcher(t == null ? "" : t);
            while (m.find()) {
                String code = m.group(1) != null ? (m.group(1) + m.group(2)).toUpperCase(java.util.Locale.ROOT)
                        : m.group(3).toUpperCase(java.util.Locale.ROOT);
                code = CODE_ALIASES.containsKey(code) ? CODE_ALIASES.get(code) : code;
                List<Object[]> l = codeIdx.get(code);
                if (l != null) found.addAll(l);
            }
            return found;
        }

        /** All (line, index) a GTFS stop could be, sorted by line then index, from station codes first, then names. */
        List<Object[]> stopCandidates(Map<String, String> stop, Map<String, Map<String, String>> stops) {
            List<String> texts = new ArrayList<>();
            texts.add(get(stop, "stop_code"));
            texts.add(get(stop, "stop_id"));
            texts.add(get(stop, "stop_name"));
            String ps = stop.get("parent_station");
            Map<String, String> parent = stops.get(ps == null ? "" : ps);
            if (ps == null || ps.isEmpty()) parent = stops.get("");
            if (parent != null) {
                texts.add(get(parent, "stop_code"));
                texts.add(get(parent, "stop_id"));
                texts.add(get(parent, "stop_name"));
            }
            List<Object[]> found = new ArrayList<>();
            for (String t : texts) found.addAll(codesIn(t));
            List<String> names = new ArrayList<>();
            if (parent != null) names.add(get(parent, "stop_name"));
            names.add(get(stop, "stop_name"));
            for (String n : names) {
                List<Object[]> l = nameIdx.get(norm(n == null ? "" : n));
                if (l != null) found.addAll(l);
            }
            Map<String, Object[]> uniq = new HashMap<>();
            for (Object[] f : found) uniq.put(f[0] + "\u0000" + f[1], f);
            List<Object[]> out = new ArrayList<>(uniq.values());
            out.sort((a, b) -> {
                int c = ((String) a[0]).compareTo((String) b[0]);
                return c != 0 ? c : Integer.compare((Integer) a[1], (Integer) b[1]);
            });
            return out;
        }

        static String get(Map<String, String> m, String k) {
            String v = m.get(k);
            return v == null ? "" : v;
        }

        /** "HH:MM:SS" to seconds; null if it isn't a time (Python's secs() raising). */
        static Integer secs(String t) {
            if (t == null) return null;
            String[] p = t.trim().split(":", -1);
            if (p.length != 3) return null;
            try {
                return Integer.parseInt(p[0].trim()) * 3600 + Integer.parseInt(p[1].trim()) * 60 + Integer.parseInt(p[2].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        static String or(String a, String b) {
            return a != null && !a.isEmpty() ? a : b;
        }

        // one stop of a trip on its line: the station indexes it could be, and its times
        static final class Row {
            final int[] cands;
            final int arr, dep;

            Row(int[] c, int a, int d) {
                cands = c;
                arr = a;
                dep = d;
            }
        }

        static final class Leg {
            List<Row> rows = new ArrayList<>();
            List<Integer> idx = new ArrayList<>();
            int dir = 0;
        }

        /** Python's min() over (abs(a - b), a, b) for a in x, b in y, a != b; null if there are none. */
        static int[] closest(int[] x, int[] y) {
            int[] best = null;
            for (int a : x)
                for (int b : y) {
                    if (a == b) continue;
                    int[] t = {Math.abs(a - b), a, b};
                    if (best == null || t[0] < best[0] || (t[0] == best[0] && (t[1] < best[1] || (t[1] == best[1] && t[2] < best[2])))) best = t;
                }
            return best;
        }

        static List<Leg> legsFor(List<Row> seq) {
            List<Leg> legs = new ArrayList<>();
            Leg cur = null;
            for (Row row : seq) {
                if (cur == null) {
                    cur = new Leg();
                    cur.rows.add(row);
                    continue;
                }
                if (cur.idx.isEmpty()) {
                    Row prev = cur.rows.get(cur.rows.size() - 1);
                    int[] m = closest(prev.cands, row.cands);
                    if (m == null || m[0] > MAX_STEP) {
                        cur = new Leg();
                        cur.rows.add(row);
                        continue;
                    }
                    cur.rows = new ArrayList<>(java.util.Arrays.asList(prev, row));
                    cur.idx = new ArrayList<>(java.util.Arrays.asList(m[1], m[2]));
                    cur.dir = m[2] > m[1] ? 1 : -1;
                    continue;
                }
                int last = cur.idx.get(cur.idx.size() - 1), d = cur.dir;
                Integer nxt = null;
                for (int x : row.cands) {
                    if ((x - last) * d > 0 && (nxt == null || Math.abs(x - last) < Math.abs(nxt - last))) nxt = x;
                }
                if (nxt != null && Math.abs(nxt - last) <= MAX_STEP) {
                    cur.rows.add(row);
                    cur.idx.add(nxt);
                } else {                                   // the train turns (or jumps): start a new run from the last stop
                    legs.add(cur);
                    Row prow = cur.rows.get(cur.rows.size() - 1);
                    cur = new Leg();
                    cur.rows.add(prow);
                    int[] m = closest(prow.cands, row.cands);
                    if (m != null && m[0] <= MAX_STEP) {
                        cur.rows.add(row);
                        cur.idx = new ArrayList<>(java.util.Arrays.asList(m[1], m[2]));
                        cur.dir = m[2] > m[1] ? 1 : -1;
                    } else {
                        cur = new Leg();
                        cur.rows.add(row);
                    }
                }
            }
            if (cur != null && !cur.idx.isEmpty()) legs.add(cur);
            return legs;
        }

        static final Pattern TO_RE = Pattern.compile("^\\s*(to|towards|for)\\s+", Pattern.CASE_INSENSITIVE);

        int headsignIdx(Map<String, String> trip, String line, List<Leg> legs) {
            int h = headsignStation(trip, line, legs);
            int first = legs.get(0).idx.get(0);
            List<String[]> st = stations.get(line);
            if (legs.size() > 1 && h >= 0 && (h == first || st.get(h)[0].equals(st.get(first)[0]))) return -2;
            return h;
        }

        int headsignStation(Map<String, String> trip, String line, List<Leg> legs) {
            String hs = get(trip, "trip_headsign").trim();
            List<Integer> lastIdx = legs.get(legs.size() - 1).idx;
            int last = lastIdx.get(lastIdx.size() - 1);
            if (!hs.isEmpty()) {
                hs = TO_RE.matcher(hs).replaceFirst("");
                List<Integer> found = new ArrayList<>();
                for (Object[] f : codesIn(hs)) if (f[0].equals(line)) found.add((Integer) f[1]);
                if (found.isEmpty()) {
                    List<Object[]> l = nameIdx.get(norm(CODE_RE.matcher(hs).replaceAll(" ")));
                    if (l != null) for (Object[] f : l) if (f[0].equals(line)) found.add((Integer) f[1]);
                }
                if (!found.isEmpty()) {
                    int best = found.get(0);
                    for (int i : found) if (Math.abs(i - last) < Math.abs(best - last)) best = i;
                    return best;
                }
            }
            return legs.size() > 1 ? last : -1;
        }

        /** A GTFS table (by file name, in any folder of the zip) as rows of column -> value, like csv.DictReader. */
        static List<Map<String, String>> table(java.util.zip.ZipFile z, String name) throws Exception {
            List<Map<String, String>> out = new ArrayList<>();
            readTable(z, name, row -> out.add(row));
            return out;
        }

        interface RowSink {
            void row(Map<String, String> row) throws Exception;
        }

        static void readTable(java.util.zip.ZipFile z, String name, RowSink sink) throws Exception {
            java.util.zip.ZipEntry e = null;
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                java.util.zip.ZipEntry x = en.nextElement();
                String n = x.getName(), base = n.substring(n.lastIndexOf('/') + 1);
                if (base.toLowerCase(java.util.Locale.ROOT).equals(name)) {
                    e = x;
                    break;
                }
            }
            if (e == null) return;
            try (Reader r = new BufferedReader(new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8), 1 << 16)) {
                Csv csv = new Csv(r);
                List<String> head = csv.next();
                if (head == null) return;
                if (!head.isEmpty() && head.get(0).startsWith("﻿")) head.set(0, head.get(0).substring(1));
                Map<String, String> intern = new HashMap<>();
                for (List<String> f; (f = csv.next()) != null; ) {
                    if (f.isEmpty()) continue;                     // blank line
                    Map<String, String> row = new HashMap<>();
                    for (int i = 0; i < head.size(); i++) {
                        if (i >= f.size()) break;                  // missing fields: like DictReader's None
                        String v = f.get(i);
                        if (v.length() < 24) {                     // repeated short values (ids, times) share one copy
                            String s = intern.get(v);
                            if (s == null) {
                                if (intern.size() < 200000) intern.put(v, v);
                                s = v;
                            }
                            v = s;
                        }
                        row.put(head.get(i), v);
                    }
                    sink.row(row);
                }
            }
        }

        /** A CSV reader like Python's csv module (excel dialect): quoted fields, "" for a quote, any line ending. */
        static final class Csv {
            final Reader r;
            int peek = -2;

            Csv(Reader r) {
                this.r = r;
            }

            int read() throws java.io.IOException {
                if (peek != -2) {
                    int c = peek;
                    peek = -2;
                    return c;
                }
                return r.read();
            }

            /** The next record's fields; an empty list for a blank line; null at the end. */
            List<String> next() throws java.io.IOException {
                int c = read();
                if (c == -1) return null;
                List<String> f = new ArrayList<>();
                StringBuilder b = new StringBuilder();
                if (c == '\r' || c == '\n') {
                    if (c == '\r') {
                        int d = read();
                        if (d != '\n') peek = d;
                    }
                    return f;
                }
                boolean quoted = false, any = false;
                while (true) {
                    if (c == -1) {
                        f.add(b.toString());
                        return f;
                    }
                    if (quoted) {
                        if (c == '"') {
                            int d = read();
                            if (d == '"') b.append('"');
                            else {
                                quoted = false;
                                c = d;
                                continue;
                            }
                        } else b.append((char) c);
                    } else if (c == '"' && b.length() == 0 && !any) {
                        quoted = true;
                        any = true;
                    } else if (c == ',') {
                        f.add(b.toString());
                        b.setLength(0);
                        any = false;
                    } else if (c == '\r' || c == '\n') {
                        if (c == '\r') {
                            int d = read();
                            if (d != '\n') peek = d;
                        }
                        f.add(b.toString());
                        return f;
                    } else {
                        b.append((char) c);
                        any = true;
                    }
                    c = read();
                }
            }
        }

        static final class StopTime {
            final int seq;
            final String stopId, arr, dep;

            StopTime(int seq, String stopId, String arr, String dep) {
                this.seq = seq;
                this.stopId = stopId;
                this.arr = arr;
                this.dep = dep;
            }
        }

        /** Packs the timetable, as JSON text; null if no line matched. `built` goes in feed.built. */
        String pack(java.util.zip.ZipFile z, String built) throws Exception {
            final Map<String, Map<String, String>> stops = new LinkedHashMap<>();
            for (Map<String, String> s : table(z, "stops.txt")) stops.put(s.get("stop_id"), s);
            final Map<String, Map<String, String>> trips = new HashMap<>();
            for (Map<String, String> t : table(z, "trips.txt")) trips.put(t.get("trip_id"), t);
            final Map<String, List<Map<String, String>>> freqs = new HashMap<>();
            for (Map<String, String> f : table(z, "frequencies.txt")) freqs.computeIfAbsent(f.get("trip_id"), k -> new ArrayList<>()).add(f);
            final Map<String, List<StopTime>> byTrip = new LinkedHashMap<>();
            readTable(z, "stop_times.txt", r -> {
                String seq = r.get("stop_sequence");
                byTrip.computeIfAbsent(r.get("trip_id"), k -> new ArrayList<>()).add(new StopTime(
                        Integer.parseInt(seq == null ? "" : seq.trim()), r.get("stop_id"), r.get("arrival_time"), r.get("departure_time")));
            });
            List<Map<String, String>> cal = table(z, "calendar.txt");
            List<Map<String, String>> cdates = table(z, "calendar_dates.txt");

            final List<String[]> services = new ArrayList<>();
            final Map<String, Integer> svcIndex = new HashMap<>();
            java.util.function.Function<String, Integer> svc = sid -> {
                Integer i = svcIndex.get(sid);
                if (i == null) {
                    i = services.size();
                    svcIndex.put(sid, i);
                    services.add(new String[]{sid, "0000000", "19000101", "29991231"});
                }
                return i;
            };
            String[] days = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
            for (Map<String, String> c : cal) {
                int i = svc.apply(c.get("service_id"));
                StringBuilder d = new StringBuilder();
                for (String day : days) d.append(c.containsKey(day) && c.get(day).trim().equals("1") ? "1" : "0");
                services.set(i, new String[]{services.get(i)[0], d.toString(),
                        (c.containsKey("start_date") ? c.get("start_date") : "19000101").trim(),
                        (c.containsKey("end_date") ? c.get("end_date") : "29991231").trim()});
            }
            final Map<String, List<int[]>> exc = new LinkedHashMap<>();
            for (Map<String, String> c : cdates) {
                String t = c.get("exception_type");
                int type = t == null || t.isEmpty() ? 1 : Integer.parseInt(t.trim());
                exc.computeIfAbsent(c.get("date").trim(), k -> new ArrayList<>()).add(new int[]{svc.apply(c.get("service_id")), type});
            }

            Map<String, List<Object[]>> stopCands = new HashMap<>();
            for (Map.Entry<String, Map<String, String>> e : stops.entrySet()) stopCands.put(e.getKey(), stopCandidates(e.getValue(), stops));

            final Map<String, List<int[][]>> patsArr = new HashMap<>();   // line -> [arrs, deps] per pattern
            final Map<String, List<int[]>> patsHead = new HashMap<>();    // line -> [first, step, destination]
            final Map<String, Map<String, Integer>> patIdx = new HashMap<>();
            final Map<String, List<Object[]>> outTrips = new HashMap<>();
            final Map<String, LinkedHashMap<String, String>> meta = new HashMap<>();
            for (String ln : lineOrder) {
                patsArr.put(ln, new ArrayList<>());
                patsHead.put(ln, new ArrayList<>());
                patIdx.put(ln, new HashMap<>());
                outTrips.put(ln, new ArrayList<>());
                meta.put(ln, new LinkedHashMap<>());
            }

            for (Map.Entry<String, List<StopTime>> te : byTrip.entrySet()) {
                String tid = te.getKey();
                Map<String, String> trip = trips.get(tid);
                if (trip == null) continue;
                List<StopTime> rows = te.getValue();
                rows.sort((a, b) -> Integer.compare(a.seq, b.seq));
                List<List<Object[]>> cands = new ArrayList<>();
                for (StopTime r : rows) {
                    List<Object[]> c = stopCands.get(r.stopId);
                    cands.add(c == null ? Collections.emptyList() : c);
                }
                // which line covers most of this trip's stops?
                LinkedHashMap<String, Integer> score = new LinkedHashMap<>();
                for (List<Object[]> cs : cands) {
                    LinkedHashSet<String> ls = new LinkedHashSet<>();
                    for (Object[] c : cs) ls.add((String) c[0]);
                    for (String l : ls) score.put(l, score.getOrDefault(l, 0) + 1);
                }
                if (score.isEmpty()) continue;
                String line = null;
                for (Map.Entry<String, Integer> s : score.entrySet()) {
                    if (line == null) {
                        line = s.getKey();
                        continue;
                    }
                    int a = s.getValue(), b = score.get(line);
                    int la = stations.get(s.getKey()).size(), lb = stations.get(line).size();
                    if (a > b || (a == b && -la > -lb)) line = s.getKey();
                }
                int sc = score.get(line);
                if (sc < 2 || sc < 0.9 * rows.size()) continue;
                List<Row> seq = new ArrayList<>();
                for (int i = 0; i < rows.size(); i++) {
                    List<Integer> on = new ArrayList<>();
                    for (Object[] c : cands.get(i)) if (c[0].equals(line)) on.add((Integer) c[1]);
                    if (on.isEmpty()) continue;
                    StopTime r = rows.get(i);
                    Integer arr = secs(or(r.arr, r.dep)), dep = secs(or(r.dep, r.arr));
                    if (arr == null || dep == null) continue;
                    int[] o = new int[on.size()];
                    for (int k = 0; k < o.length; k++) o[k] = on.get(k);
                    seq.add(new Row(o, arr, dep));
                }
                int sid = svc.apply(trip.get("service_id"));
                int tripT0 = seq.isEmpty() ? 0 : seq.get(0).dep;
                List<Integer> startsExtra = new ArrayList<>();
                List<Map<String, String>> fl = freqs.get(tid);
                if (fl != null) for (Map<String, String> f : fl) {
                    Integer a = secs(f.get("start_time")), b = secs(f.get("end_time"));
                    if (a == null || b == null) throw new Exception("Bad time in frequencies.txt");
                    int h = Math.max(30, Integer.parseInt(f.get("headway_secs").trim()));
                    for (int t = a; t < b; t += h) startsExtra.add(t);
                }
                List<Leg> legs = legsFor(seq);
                int head = legs.isEmpty() ? -1 : headsignIdx(trip, line, legs);
                for (Leg leg : legs) {
                    List<Integer> idx = leg.idx;
                    int d = leg.dir, t0 = leg.rows.get(0).dep;
                    Map<Integer, int[]> times = new HashMap<>();
                    for (int k = 0; k < Math.min(leg.rows.size(), idx.size()); k++) {
                        Row r = leg.rows.get(k);
                        times.put(idx.get(k), new int[]{r.arr - t0, r.dep - t0});
                    }
                    times.put(idx.get(0), new int[]{0, 0});
                    int from = idx.get(0), to = idx.get(idx.size() - 1);
                    List<Integer> span = new ArrayList<>();
                    for (int i = from; d > 0 ? i < to + d : i > to + d; i += d) span.add(i);
                    List<Integer> known = new ArrayList<>();
                    for (int i : span) if (times.containsKey(i)) known.add(i);
                    int[] arrs = new int[span.size()], deps = new int[span.size()];
                    for (int k = 0; k < span.size(); k++) {
                        int i = span.get(k), a, b;
                        if (times.containsKey(i)) {
                            a = times.get(i)[0];
                            b = times.get(i)[1];
                        } else {                               // not listed (shouldn't happen): interpolate
                            Integer lo = null, hi = null;
                            for (int q : known) {
                                if ((q - i) * d < 0 && (lo == null || q > lo)) lo = q;
                                if ((q - i) * d > 0 && (hi == null || q < hi)) hi = q;
                            }
                            double frac = Math.abs(i - lo) / (double) Math.abs(hi - lo);
                            a = b = (int) Math.rint(times.get(lo)[1] + frac * (times.get(hi)[0] - times.get(lo)[1]));
                        }
                        arrs[k] = a;
                        deps[k] = Math.max(a, b);
                    }
                    String key = from + "|" + d + "|" + java.util.Arrays.toString(arrs) + "|" + java.util.Arrays.toString(deps) + "|" + head;
                    Integer p = patIdx.get(line).get(key);
                    if (p == null) {
                        p = patsArr.get(line).size();
                        patIdx.get(line).put(key, p);
                        patsArr.get(line).add(new int[][]{arrs, deps});
                        patsHead.get(line).add(new int[]{from, d, head});
                    }
                    StopTime fr = rows.get(0), lr = rows.get(rows.size() - 1);
                    Integer end = secs(or(lr.dep, lr.arr));
                    if (end == null) throw new Exception("Bad time in stop_times.txt");
                    StringBuilder m = new StringBuilder();
                    m.append("{\"block\":");
                    str(m, get(trip, "block_id").trim());
                    m.append(",\"headsign\":");
                    str(m, get(trip, "trip_headsign").trim());
                    m.append(",\"route\":");
                    str(m, get(trip, "route_id").trim());
                    m.append(",\"first\":");
                    str(m, (fr.stopId == null ? "" : fr.stopId).trim());
                    m.append(",\"last\":");
                    str(m, (lr.stopId == null ? "" : lr.stopId).trim());
                    m.append(",\"firstName\":");
                    str(m, stopName(stops, fr.stopId));
                    m.append(",\"lastName\":");
                    str(m, stopName(stops, lr.stopId));
                    m.append(",\"start\":").append(t0).append(",\"end\":").append(end).append(",\"line\":");
                    str(m, line);
                    m.append('}');
                    meta.get(line).put(tid, m.toString());
                    if (!startsExtra.isEmpty()) {
                        for (int s0 : startsExtra) outTrips.get(line).add(new Object[]{s0 + (t0 - tripT0), p, sid, tid});
                    } else {
                        outTrips.get(line).add(new Object[]{t0, p, sid, tid});
                    }
                }
            }

            List<String> linesOk = new ArrayList<>();
            for (String ln : lineOrder) {
                outTrips.get(ln).sort((a, b) -> {
                    for (int k = 0; k < 3; k++) {
                        int c = Integer.compare((Integer) a[k], (Integer) b[k]);
                        if (c != 0) return c;
                    }
                    return pyCompare((String) a[3], (String) b[3]);
                });
                if (!outTrips.get(ln).isEmpty()) linesOk.add(ln);
            }
            if (linesOk.isEmpty()) return null;

            // written exactly as Python's json.dump(separators=(",", ":")) writes it, so the two can be compared
            StringBuilder o = new StringBuilder(8 << 20);
            o.append("{\"feed\":{\"source\":");
            str(o, "LTA DataMall, GTFS Schedule (Train)");
            o.append(",\"built\":");
            str(o, built);
            o.append(",\"lines\":[");
            for (int i = 0; i < linesOk.size(); i++) {
                if (i > 0) o.append(',');
                str(o, linesOk.get(i));
            }
            o.append("],\"depotDetection\":");
            str(o, "block_id + scheduled continuity heuristic; candidates are not guaranteed non-revenue movements");
            o.append(",\"app\":true},\"services\":[");
            for (int i = 0; i < services.size(); i++) {
                if (i > 0) o.append(',');
                String[] s = services.get(i);
                o.append('[');
                for (int k = 0; k < 4; k++) {
                    if (k > 0) o.append(',');
                    str(o, s[k]);
                }
                o.append(']');
            }
            o.append("],\"except\":{");
            boolean firstE = true;
            for (Map.Entry<String, List<int[]>> e : exc.entrySet()) {
                if (!firstE) o.append(',');
                firstE = false;
                str(o, e.getKey());
                o.append(":[");
                for (int i = 0; i < e.getValue().size(); i++) {
                    if (i > 0) o.append(',');
                    int[] v = e.getValue().get(i);
                    o.append('[').append(v[0]).append(',').append(v[1]).append(']');
                }
                o.append(']');
            }
            o.append("},\"lines\":{");
            for (int li = 0; li < linesOk.size(); li++) {
                String ln = linesOk.get(li);
                if (li > 0) o.append(',');
                str(o, ln);
                o.append(":{\"pats\":[");
                List<int[][]> pa = patsArr.get(ln);
                for (int i = 0; i < pa.size(); i++) {
                    if (i > 0) o.append(',');
                    int[] hd = patsHead.get(ln).get(i);
                    o.append('[').append(hd[0]).append(',').append(hd[1]).append(',');
                    ints(o, pa.get(i)[0]);
                    o.append(',');
                    ints(o, pa.get(i)[1]);
                    o.append(',').append(hd[2]).append(']');
                }
                o.append("],\"trips\":[");
                List<Object[]> tl = outTrips.get(ln);
                for (int i = 0; i < tl.size(); i++) {
                    if (i > 0) o.append(',');
                    Object[] t = tl.get(i);
                    o.append('[').append(t[0]).append(',').append(t[1]).append(',').append(t[2]).append(',');
                    str(o, (String) t[3]);
                    o.append(']');
                }
                o.append("],\"tripMeta\":{");
                boolean firstM = true;
                for (Map.Entry<String, String> e : meta.get(ln).entrySet()) {
                    if (!firstM) o.append(',');
                    firstM = false;
                    str(o, e.getKey());
                    o.append(':').append(e.getValue());
                }
                o.append("}}");
            }
            o.append("}}");
            return o.toString();
        }

        static String stopName(Map<String, Map<String, String>> stops, String id) {
            Map<String, String> s = stops.get(id == null ? "" : id);
            String n = s == null ? null : s.get("stop_name");
            return n == null ? "" : n.trim();
        }

        /** Python's string order (by code point). */
        static int pyCompare(String a, String b) {
            int i = 0, j = 0;
            while (i < a.length() && j < b.length()) {
                int x = a.codePointAt(i), y = b.codePointAt(j);
                if (x != y) return Integer.compare(x, y);
                i += Character.charCount(x);
                j += Character.charCount(y);
            }
            return Integer.compare(a.length() - i, b.length() - j);
        }

        static void ints(StringBuilder o, int[] a) {
            o.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) o.append(',');
                o.append(a[i]);
            }
            o.append(']');
        }

        /** A JSON string as Python's json writes it (ensure_ascii). */
        static void str(StringBuilder o, String s) {
            o.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': o.append("\\\""); break;
                    case '\\': o.append("\\\\"); break;
                    case '\n': o.append("\\n"); break;
                    case '\r': o.append("\\r"); break;
                    case '\t': o.append("\\t"); break;
                    case '\b': o.append("\\b"); break;
                    case '\f': o.append("\\f"); break;
                    default:
                        if (c < 0x20 || c > 0x7e) o.append(String.format("\\u%04x", (int) c));
                        else o.append(c);
                }
            }
            o.append('"');
        }
    }
}
