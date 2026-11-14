package sg.mrttracker.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs every few hours in the background: checks GitHub and notifies if there's a new version. */
public class UpdateJob extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            Updater.notifyIfNew(getApplicationContext());
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;   // try again later
    }
}
