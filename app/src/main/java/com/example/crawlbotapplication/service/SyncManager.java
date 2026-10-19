package com.example.crawlbotapplication.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.crawlbotapplication.data.AppDatabase;
import com.example.crawlbotapplication.data.ChangeEventItem;
import com.example.crawlbotapplication.data.GasolineItem;
import com.example.crawlbotapplication.data.WeatherItem;
import com.example.crawlbotapplication.network.ServerApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SyncManager {

    public interface SyncCallback {
        void onSuccess(String statusMsg);
        void onError(String errorMsg);
    }

    private final Context context;
    private final AppDatabase db;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public SyncManager(Context context) {
        this.context = context.getApplicationContext();
        this.db = AppDatabase.getInstance(this.context);
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Synchronous blocking sync method used by Android WorkManager SyncWorker
     * running on a dedicated background worker thread.
     */
    public boolean syncWithServerBlocking() throws Exception {
        boolean syncedViaSummary = false;

        // 1. Try unified Daily Summary endpoint first
        try {
            JSONObject summaryJson = ServerApiClient.fetchDailySummary();
            if (summaryJson.optBoolean("success", false)) {
                String status = summaryJson.optString("status", "AVAILABLE");

                if (!"NO_DATA".equalsIgnoreCase(status)) {
                    // A. Weather parsing
                    JSONObject wObj = summaryJson.optJSONObject("weather");
                    if (wObj != null && !wObj.isNull("location")) {
                        saveWeatherToDb(wObj, wObj.toString());
                    }

                    // B. Gasoline parsing
                    JSONArray gasArr = summaryJson.optJSONArray("gasoline");
                    if (gasArr != null && gasArr.length() > 0) {
                        saveGasolineToDb(gasArr);
                    }

                    // C. Recent events parsing
                    JSONArray eventsArr = summaryJson.optJSONArray("recent_events");
                    if (eventsArr != null) {
                        saveEventsToDb(eventsArr);
                    }
                }

                syncedViaSummary = true;
            }
        } catch (Exception ignored) {
            // Fall back to individual endpoints below
        }

        if (!syncedViaSummary) {
            // Fallback 1: Fetch latest gasoline prices from server
            JSONObject gasJson = ServerApiClient.fetchLatestGasoline();
            if (gasJson.optBoolean("success", false) && !"NO_DATA".equalsIgnoreCase(gasJson.optString("status"))) {
                JSONArray dataArr = gasJson.optJSONArray("data");
                if (dataArr != null && dataArr.length() > 0) {
                    saveGasolineToDb(dataArr);
                }
            }

            // Fallback 2: Fetch latest weather from server
            JSONObject weatherJson = ServerApiClient.fetchLatestWeather();
            if (weatherJson.optBoolean("success", false) && !"NO_DATA".equalsIgnoreCase(weatherJson.optString("status"))) {
                JSONObject dataObj = weatherJson.optJSONObject("data");
                if (dataObj != null && !dataObj.isNull("location")) {
                    saveWeatherToDb(dataObj, weatherJson.toString());
                }
            }

            // Fallback 3: Fetch change events from server
            JSONObject eventsJson = ServerApiClient.fetchChangeEvents(50);
            if (eventsJson.optBoolean("success", false)) {
                JSONArray eventsArr = eventsJson.optJSONArray("data");
                if (eventsArr != null) {
                    saveEventsToDb(eventsArr);
                }
            }
        }

        // Trigger Android Notification for unnotified change events
        List<ChangeEventItem> unnotified = db.changeEventDao().getUnnotifiedEvents();
        for (ChangeEventItem ev : unnotified) {
            NotificationHelper.showPriceChangeNotification(
                    context,
                    "Cập nhật giá xăng dầu",
                    ev.getMessage(),
                    ev.getEventId()
            );
            db.changeEventDao().markNotified(ev.getEventId());
        }

        return true;
    }

    /**
     * Asynchronous sync method for Activity UI calls with callback.
     */
    public void syncWithServer(SyncCallback callback) {
        executor.execute(() -> {
            try {
                syncWithServerBlocking();
                if (callback != null) {
                    mainHandler.post(() -> callback.onSuccess("Đã đồng bộ thành công dữ liệu từ Bot Crawler."));
                }
            } catch (Exception e) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onError("Lỗi kết nối Server: " + e.getMessage()));
                }
            }
        });
    }

    private void saveWeatherToDb(JSONObject wObj, String rawJson) {
        String loc = wObj.optString("location", "").trim();
        String temp = wObj.optString("temperature", "").trim();
        String cond = wObj.optString("condition", "").trim();

        // Reject incomplete records
        if (loc.isEmpty() || temp.isEmpty() || "N/A".equalsIgnoreCase(temp)) {
            return;
        }

        String hum = wObj.optString("humidity", "N/A");
        String wind = wObj.optString("wind", "N/A");
        String uv = wObj.optString("uv_index", "N/A");
        String precip = wObj.optString("precipitation_prob", "N/A");
        String src = wObj.optString("source_url", "");
        String updated = wObj.optString("updated_at", java.time.LocalDateTime.now().toString());

        WeatherItem item = new WeatherItem(loc, temp, cond, hum, wind, uv, precip, src, updated, rawJson);
        db.weatherDao().deleteAll();
        db.weatherDao().insert(item);
    }

    private void saveGasolineToDb(JSONArray dataArr) throws Exception {
        List<GasolineItem> items = new ArrayList<>();
        for (int i = 0; i < dataArr.length(); i++) {
            JSONObject obj = dataArr.getJSONObject(i);
            String prod = obj.optString("product");
            String price = obj.optString("price");
            String z1 = obj.optString("zone_1_price");
            String z2 = obj.optString("zone_2_price");
            String diff = obj.optString("diff");
            String unit = obj.optString("unit", "VND/Lít");
            String updated = obj.optString("updated_at");

            items.add(new GasolineItem(prod, price, z1, z2, diff, unit, updated));
        }
        if (!items.isEmpty()) {
            db.gasolineDao().deleteAll();
            db.gasolineDao().insertAll(items);
        }
    }

    private void saveEventsToDb(JSONArray eventsArr) throws Exception {
        List<ChangeEventItem> events = new ArrayList<>();
        for (int i = 0; i < eventsArr.length(); i++) {
            JSONObject ev = eventsArr.getJSONObject(i);
            int id = ev.optInt("id");
            String type = ev.optString("event_type");
            String prod = ev.optString("product");
            String oldP = ev.optString("old_price");
            String newP = ev.optString("new_price");
            String diff = ev.optString("diff");
            String msg = ev.optString("message");
            String created = ev.optString("created_at");

            events.add(new ChangeEventItem(id, type, prod, oldP, newP, diff, msg, created, false));
        }
        if (!events.isEmpty()) {
            db.changeEventDao().insertAll(events);
        }
    }
}
