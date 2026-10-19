package com.example.crawlbotapplication.service;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.crawlbotapplication.network.ServerApiClient;

import java.util.concurrent.TimeUnit;

/**
 * Android WorkManager Worker that guarantees persistent, reliable background sync
 * even when the Activity is destroyed or the application process is terminated by the OS.
 */
public class SyncWorker extends Worker {

    public static final String UNIQUE_WORK_NAME = "CRAWL_BOT_PERIODIC_AUTO_SYNC";
    public static final String PREFS_NAME = "crawl_bot_prefs";
    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_API_TOKEN = "api_token";
    public static final String KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        boolean isEnabled = prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, true);
        if (!isEnabled) {
            return Result.success();
        }

        String serverUrl = prefs.getString(KEY_SERVER_URL, "http://10.0.2.2:8080/api/");
        String token = prefs.getString(KEY_API_TOKEN, "crawlbot_secret_token_2026");

        ServerApiClient.setBaseUrl(serverUrl);
        ServerApiClient.setAuthToken(token);

        try {
            SyncManager syncManager = new SyncManager(context);
            syncManager.syncWithServerBlocking();
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    /**
     * Enqueues persistent periodic sync with Android WorkManager.
     * Uses 15 minutes repeat interval (Android system minimum) with Network CONNECTED constraint.
     */
    public static void enqueuePeriodicSync(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest syncRequest = new PeriodicWorkRequest.Builder(
                SyncWorker.class,
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
        )
        .setConstraints(constraints)
        .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
        );
    }

    /**
     * Cancels the periodic background sync task in WorkManager.
     */
    public static void cancelPeriodicSync(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME);
    }
}
