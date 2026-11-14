package sg.mrttracker.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** A planned trip reminder is due: show it. */
public class TripAlarm extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String title = intent.getStringExtra("title");
        if (title == null || title.isEmpty()) return;
        Alerts.post(context, Alerts.CH_TRIP, intent.getIntExtra("id", 3000), title, intent.getStringExtra("text"));
    }
}
