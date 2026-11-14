package sg.mrttracker.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs every 6 hours or so in the background: downloads LTA's train timetable and keeps it if it changed. */
public class TimetableJob extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            if (Timetable.due(getApplicationContext())
                    && "new".equals(Timetable.refresh(getApplicationContext()).optString("status"))) {
                MainActivity.timetableUpdated();
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;   // try again later
    }
}
