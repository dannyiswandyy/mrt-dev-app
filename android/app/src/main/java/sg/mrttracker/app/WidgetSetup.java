package sg.mrttracker.app;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the station a home screen widget shows: when the widget is added, and later by holding the widget and
 * choosing to change it. "First saved station" follows Home in the app, as the widget always did.
 */
public class WidgetSetup extends Activity {
    private int id = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setResult(RESULT_CANCELED);
        Bundle ex = getIntent().getExtras();
        if (ex != null) id = ex.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        final String home = "First saved station (follows Home)";
        final List<String> all = TrainWidget.stationNames(this);
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * dp);
        box.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Which station should this widget show?");
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(title);
        if (all.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Open MRT Dev once first, so the widget knows the stations. For now it shows your first saved station.");
            t.setPadding(0, pad / 2, 0, pad / 2);
            box.addView(t);
        }
        final EditText search = new EditText(this);
        search.setHint("Search stations");
        search.setSingleLine(true);
        box.addView(search);
        final ListView list = new ListView(this);
        final List<String> shown = new ArrayList<>();
        final ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, shown);
        list.setAdapter(ad);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        Runnable fill = () -> {
            String q = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            shown.clear();
            if (q.isEmpty()) shown.add(home);
            for (String s : all) if (q.isEmpty() || s.toLowerCase(java.util.Locale.ROOT).contains(q)) shown.add(s);
            ad.notifyDataSetChanged();
        };
        fill.run();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable e) {
                fill.run();
            }
        });
        list.setOnItemClickListener((p, v, pos, rid) -> {
            String pick = shown.get(pos);
            TrainWidget.setStation(this, id, pick.equals(home) ? "" : pick);
            TrainWidget.updateAll(this);
            setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id));
            finish();
        });
        setContentView(box);
    }
}
