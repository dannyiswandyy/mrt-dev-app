package sg.mrttracker.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * Trip reminders. A reminder that's due is shown. A live check (four minutes before a reminder) reads LTA's live
 * train feed and moves the reminders for that trip if its train is now running later (or earlier) than when they
 * were set, or says it's cancelled.
 */
public class TripAlarm extends BroadcastReceiver {
    static final String CHECK = "sg.mrttracker.app.TRIP_CHECK_";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction() == null ? "" : intent.getAction();
        if (action.startsWith(CHECK)) {
            final PendingResult done = goAsync();
            final Context c = context.getApplicationContext();
            new Thread(() -> {
                try {
                    recheck(c);
                } finally {
                    done.finish();
                }
            }).start();
            return;
        }
        String title = intent.getStringExtra("title");
        if (title == null || title.isEmpty()) return;
        Alerts.post(context, Alerts.CH_TRIP, intent.getIntExtra("id", 3000), title, intent.getStringExtra("text"));
    }

    private static void recheck(Context c) {
        try {
            String saved = Alerts.prefs(c).getString("trip", "");
            if (saved.isEmpty()) return;
            JSONArray a = new JSONArray(saved);
            Map<String, Long> live = LiveFeed.delays();
            if (live.isEmpty()) return;                          // couldn't read it: keep the reminders as they are
            boolean moved = false;
            long now = System.currentTimeMillis();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                String tid = o == null ? "" : o.optString("tid", "");
                if (tid.isEmpty() || !live.containsKey(tid)) continue;
                long d = live.get(tid), was = o.optLong("delay", 0), at = o.optLong("at", 0);
                if (at < now) continue;
                if (d == LiveFeed.CANCELLED) {
                    if (!o.optBoolean("cancelled", false)) {
                        o.put("cancelled", true);
                        o.put("text", "LTA says this train is cancelled. Take the next one. " + o.optString("text", ""));
                        moved = true;
                    }
                    continue;
                }
                if (Math.abs(d - was) < 30) continue;
                long nat = at + (d - was) * 1000;
                o.put("at", Math.max(now + 10_000, nat));
                o.put("delay", d);
                String base = o.optString("base", o.optString("text", ""));
                o.put("base", base);
                o.put("text", (d >= 60 ? "Running about " + Math.round(d / 60.0) + " min late (LTA). " : d <= -60 ? "Running about " + Math.round(-d / 60.0) + " min early (LTA). " : "") + base);
                moved = true;
            }
            if (moved) Alerts.tripSchedule(c, a.toString());
        } catch (Exception ignored) {
        }
    }
}
