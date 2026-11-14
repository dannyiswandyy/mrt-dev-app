package sg.mrttracker.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Home screen widget: the first station saved on Home, its next trains each way, any disruption,
 * and when the app last updated it. The app pushes the times; the widget counts them down.
 */
public class TrainWidget extends AppWidgetProvider {
    private static final String PREFS = "widget";
    private static final String TICK = "sg.mrttracker.app.WIDGET_TICK";
    private static final int[] ROW = {R.id.w_r0, R.id.w_r1, R.id.w_r2, R.id.w_r3};
    private static final int[] BAR = {R.id.w_b0, R.id.w_b1, R.id.w_b2, R.id.w_b3};
    private static final int[] DST = {R.id.w_d0, R.id.w_d1, R.id.w_d2, R.id.w_d3};
    private static final int[] TIM = {R.id.w_t0, R.id.w_t1, R.id.w_t2, R.id.w_t3};

    static void save(Context c, String json) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("data", json).apply();
        updateAll(c);
    }

    static void updateAll(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, TrainWidget.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews v = build(c);
            for (int id : ids) m.updateAppWidget(id, v);
            tick(c, true);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews v = build(c);
        for (int id : ids) m.updateAppWidget(id, v);
        tick(c, true);
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

    static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_trains);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 4000, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        JSONObject j = null;
        try {
            j = new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("data", "{}"));
        } catch (Exception ignored) {
        }
        String st = j == null ? "" : j.optString("st", "");
        JSONArray rows = j == null ? null : j.optJSONArray("rows");
        long at = j == null ? 0 : j.optLong("at", 0), now = System.currentTimeMillis();
        if (st.isEmpty() || rows == null) {
            v.setTextViewText(R.id.w_title, c.getString(R.string.app_name));
            v.setTextViewText(R.id.w_upd, "");
            v.setTextViewText(R.id.w_msg, "Open the app and save a station on Home to see its next trains here.");
            v.setViewVisibility(R.id.w_msg, View.VISIBLE);
            for (int r : ROW) v.setViewVisibility(r, View.GONE);
            v.setViewVisibility(R.id.w_dis, View.GONE);
            return v;
        }
        v.setViewVisibility(R.id.w_msg, View.GONE);
        v.setTextViewText(R.id.w_title, st);
        boolean any = false;
        for (int i = 0; i < ROW.length; i++) {
            JSONObject r = i < rows.length() ? rows.optJSONObject(i) : null;
            if (r == null) {
                v.setViewVisibility(ROW[i], View.GONE);
                continue;
            }
            v.setViewVisibility(ROW[i], View.VISIBLE);
            try {
                v.setInt(BAR[i], "setBackgroundColor", Color.parseColor(r.optString("c", "#888888")));
            } catch (Exception e) {
                v.setInt(BAR[i], "setBackgroundColor", Color.GRAY);
            }
            v.setTextViewText(DST[i], r.optString("to", ""));
            JSONArray t = r.optJSONArray("t");
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            if (t != null) for (int k = 0; k < t.length() && shown < 3; k++) {
                double m = (t.optLong(k, 0) - now) / 60000.0;
                if (m < -0.5) continue;
                if (shown == 0) sb.append(m < 0.75 ? "Now" : Math.round(m) + " min");
                else sb.append(" · ").append(Math.round(m));
                shown++;
            }
            if (shown > 0) any = true;
            v.setTextViewText(TIM[i], shown > 0 ? sb.toString() : (t != null && t.length() > 0 ? "–" : ""));
        }
        String dis = j.optString("dis", "");
        v.setViewVisibility(R.id.w_dis, dis.isEmpty() ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.w_dis, dis);
        String hm = at > 0 ? new java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(new java.util.Date(at)) : "";
        v.setTextViewText(R.id.w_upd, !any && now - at > 20 * 60_000 ? "Tap to refresh" : hm.isEmpty() ? "" : "updated " + hm);
        return v;
    }
}
