package sg.mrttracker.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** About every half hour while the app is closed: checks LTA for disruptions on the saved lines. */
public class AlertJob extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            Alerts.check(getApplicationContext());
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;   // try again later
    }
}
