package com.jigong.calendar;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class MonthlyBackupJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            MonthlyBackup.backupIfDue(getApplicationContext());
            jobFinished(params, false);
        }, "monthly-backup").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return true;
    }
}

