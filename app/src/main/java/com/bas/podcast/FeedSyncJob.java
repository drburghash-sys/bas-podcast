package com.bas.podcast;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import java.util.List;

/** Scheduled refresh every ~6h. Android may defer jobs to save battery. */
public class FeedSyncJob extends JobService {
    private static final int JOB_ID = 362210;
    private volatile boolean stopped;
    private Thread thread;
    public static void schedule(Context c) {
        JobScheduler scheduler = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (!Library.setting(c, "auto_refresh", true)) { scheduler.cancel(JOB_ID); return; }
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, FeedSyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6L * 60 * 60 * 1000)
                .setPersisted(true).build();
        scheduler.schedule(info);
    }
    @Override public boolean onStartJob(JobParameters params) {
        stopped = false;
        thread = new Thread(() -> {
            try {
                for (Library.Feed f : Library.feeds(this)) {
                    if (stopped) break;
                    try {
                        Library.ParsedFeed data = RssReader.fetch(f.url);
                        List<Library.Episode> fresh = Library.merge(this, data);
                        DownloadHelper.autoDownload(this, fresh);
                    } catch (Exception error) { Log.w("BASPodcast", "Feed refresh failed: " + f.url, error); }
                }
                DownloadHelper.refreshStatus(this);
            } finally { if (!stopped) jobFinished(params, false); }
        }, "BASPodcastFeedSync");
        thread.start(); return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        stopped = true; if (thread != null) thread.interrupt(); return true;
    }
}
