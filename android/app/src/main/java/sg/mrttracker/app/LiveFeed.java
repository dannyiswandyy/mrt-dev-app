package sg.mrttracker.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * LTA's live train feed (GTFS Realtime trip updates), read in the background without the page: each trip's delay.
 * Used by trip reminders, which check it a few minutes before they're due and move if the train is late.
 */
final class LiveFeed {

    private LiveFeed() {
    }

    /** Cancelled, in the map that delays() returns. */
    static final long CANCELLED = Long.MIN_VALUE;

    /** trip id -> delay in seconds (the first stop LTA gives one for), or CANCELLED. Empty if it can't be read. */
    static Map<String, Long> delays() {
        Map<String, Long> out = new HashMap<>();
        try {
            String key = MainActivity.unscramble(BuildConfig.LTA_KEY_X);
            if (key.isEmpty()) return out;
            HttpURLConnection c = (HttpURLConnection) new URL("https://datamall2.mytransport.sg/ltaodataservice/GTFSRealtimeTrainTripUpdates").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("AccountKey", key);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return out;
            String json;
            try (InputStream in = c.getInputStream()) {
                json = new String(read(in, 1 << 20), StandardCharsets.UTF_8);
            }
            c.disconnect();
            String link = Timetable.findLink(json);
            if (link == null) {
                JSONObject root = new JSONObject(json);
                Object v = root.opt("value");
                JSONObject item = v instanceof org.json.JSONArray ? ((org.json.JSONArray) v).getJSONObject(0) : v instanceof JSONObject ? (JSONObject) v : root;
                link = item.optString("link", item.optString("Link", ""));
            }
            URL lu = new URL(link);
            if (!"https".equals(lu.getProtocol()) || !Timetable.LTA_FILES_HOST.equals(lu.getHost())) return out;
            HttpURLConnection d = (HttpURLConnection) lu.openConnection();
            d.setConnectTimeout(10000);
            d.setReadTimeout(20000);
            if (d.getResponseCode() != 200) return out;
            byte[] b;
            try (InputStream in = d.getInputStream()) {
                b = read(in, 8 << 20);
            }
            d.disconnect();
            parse(b, out);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static byte[] read(InputStream in, int max) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new java.io.IOException("Too large");
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    // ---------- a small protocol buffers reader: just the fields needed ----------

    private static final class Pb {
        final byte[] b;
        int i;
        final int end;

        Pb(byte[] b, int from, int to) {
            this.b = b;
            i = from;
            end = to;
        }

        long varint() {
            long x = 0;
            int s = 0;
            while (i < end) {
                int c = b[i++] & 0xff;
                x |= (long) (c & 0x7f) << s;
                if ((c & 0x80) == 0) break;
                s += 7;
            }
            return x;
        }
    }

    /** FeedMessage: entity (2) -> trip_update (3) -> trip (1).trip_id (1), schedule_relationship (4); delay (5); stop_time_update (2) -> arrival (2) / departure (3) -> delay (1). */
    static void parse(byte[] b, Map<String, Long> out) {
        Pb f = new Pb(b, 0, b.length);
        while (f.i < f.end) {
            long key = f.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (wire == 2) {
                int len = (int) f.varint(), s = f.i;
                if (field == 2) entity(b, s, s + len, out);
                f.i = s + len;
            } else if (!skip(f, wire)) return;
        }
    }

    private static boolean skip(Pb p, int wire) {
        if (wire == 0) p.varint();
        else if (wire == 1) p.i += 8;
        else if (wire == 5) p.i += 4;
        else if (wire == 2) {
            int len = (int) p.varint();
            p.i += len;
        } else return false;
        return true;
    }

    private static void entity(byte[] b, int from, int to, Map<String, Long> out) {
        Pb e = new Pb(b, from, to);
        while (e.i < e.end) {
            long key = e.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (wire == 2) {
                int len = (int) e.varint(), s = e.i;
                if (field == 3) tripUpdate(b, s, s + len, out);
                e.i = s + len;
            } else if (!skip(e, wire)) return;
        }
    }

    private static void tripUpdate(byte[] b, int from, int to, Map<String, Long> out) {
        Pb t = new Pb(b, from, to);
        String id = null;
        long rel = 0;
        Long delay = null, first = null;
        while (t.i < t.end) {
            long key = t.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (wire == 2) {
                int len = (int) t.varint(), s = t.i;
                if (field == 1) {                                     // TripDescriptor
                    Pb d = new Pb(b, s, s + len);
                    while (d.i < d.end) {
                        long k = d.varint();
                        int ff = (int) (k >>> 3), w = (int) (k & 7);
                        if (w == 2) {
                            int l2 = (int) d.varint();
                            if (ff == 1) id = new String(b, d.i, l2, StandardCharsets.UTF_8);
                            d.i += l2;
                        } else if (w == 0 && ff == 4) rel = d.varint();
                        else if (!skip(d, w)) break;
                    }
                } else if (field == 2 && first == null) {             // StopTimeUpdate: its arrival or departure delay
                    Pb u = new Pb(b, s, s + len);
                    while (u.i < u.end && first == null) {
                        long k = u.varint();
                        int ff = (int) (k >>> 3), w = (int) (k & 7);
                        if (w == 2) {
                            int l2 = (int) u.varint(), s2 = u.i;
                            if (ff == 2 || ff == 3) {
                                Pb ev = new Pb(b, s2, s2 + l2);
                                while (ev.i < ev.end) {
                                    long k3 = ev.varint();
                                    int f3 = (int) (k3 >>> 3), w3 = (int) (k3 & 7);
                                    if (w3 == 0 && f3 == 1) {
                                        first = (long) (int) ev.varint();     // int32
                                        break;
                                    } else if (!skip(ev, w3)) break;
                                }
                            }
                            u.i = s2 + l2;
                        } else if (!skip(u, w)) break;
                    }
                }
                t.i = s + len;
            } else if (wire == 0 && field == 5) delay = (long) (int) t.varint();
            else if (!skip(t, wire)) return;
        }
        if (id == null || id.isEmpty()) return;
        out.put(id, rel == 3 ? CANCELLED : delay != null ? delay : first != null ? first : 0L);
    }
}
