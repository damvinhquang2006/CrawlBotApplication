package com.example.crawlbotapplication;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.crawlbotapplication.data.AppDatabase;
import com.example.crawlbotapplication.data.ChangeEventItem;
import com.example.crawlbotapplication.data.GasolineItem;
import com.example.crawlbotapplication.data.WeatherItem;
import com.example.crawlbotapplication.network.ServerApiClient;
import com.example.crawlbotapplication.service.NotificationHelper;
import com.example.crawlbotapplication.service.SyncManager;
import com.example.crawlbotapplication.service.SyncWorker;
import com.example.crawlbotapplication.ui.ChangeEventAdapter;
import com.example.crawlbotapplication.ui.GasolineAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_CODE_NOTIF = 101;

    // Server controls
    private EditText edtServerUrl, edtApiToken;
    private Button btnSync, btnTriggerCrawl, btnAutoSyncToggle;
    private TextView tvStatus, tvEmpty;
    private ProgressBar progressBar;

    // 4 Navigation Tabs
    private Button tabGasoline, tabWeather, tabJson, tabEvents;

    // 4 Tab Content Views
    private FrameLayout layoutGasoline;
    private ScrollView layoutWeather;
    private LinearLayout layoutJson;
    private FrameLayout layoutEvents;

    // Weather Card Views
    private TextView tvWeatherLocation, tvWeatherTemp, tvWeatherCondition, tvWeatherUpdated;
    private TextView tvWeatherHumidity, tvWeatherWind, tvWeatherUv, tvWeatherPrecip, tvWeatherSource;

    // JSON Viewer Views
    private Button btnJsonSummary, btnJsonWeather, btnJsonGasoline, btnCopyJson;
    private TextView tvRawJson;
    private int currentJsonSubTab = 0; // 0: Summary, 1: Weather, 2: Gasoline

    // Lists & Adapters
    private RecyclerView recyclerViewGasoline, recyclerViewEvents;
    private GasolineAdapter gasolineAdapter;
    private ChangeEventAdapter changeEventAdapter;

    // Database & Background Sync
    private AppDatabase db;
    private SyncManager syncManager;
    private boolean isAutoSyncEnabled = true;

    // Foreground live updater (only runs while Activity is visible, pauses on background)
    private final Handler foregroundHandler = new Handler(Looper.getMainLooper());
    private final Runnable foregroundRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAutoSyncEnabled) {
                performSyncSilently();
            }
            foregroundHandler.postDelayed(this, 60000); // Check every 60s when viewing app
        }
    };

    private int activeTab = 0; // 0: Gas, 1: Weather, 2: JSON, 3: Events

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Initialize Notification Channel & Permissions
        NotificationHelper.createNotificationChannel(this);
        requestNotificationPermission();

        // 2. Initialize Database & Sync
        db = AppDatabase.getInstance(this);
        syncManager = new SyncManager(this);

        // 3. Bind UI Views
        initViews();

        // 4. Setup Lists & Adapters
        setupRecyclerViews();

        // 5. Setup Action Listeners
        setupListeners();

        // 6. Offline-first: Load local cached data immediately
        loadDataFromRoom();

        // 7. Initial sync with server
        performSync();

        // 8. Register persistent background auto-sync with Android WorkManager
        if (isAutoSyncEnabled) {
            SyncWorker.enqueuePeriodicSync(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isAutoSyncEnabled) {
            foregroundHandler.postDelayed(foregroundRunnable, 60000);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        foregroundHandler.removeCallbacks(foregroundRunnable);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_CODE_NOTIF
                );
            }
        }
    }

    private void initViews() {
        edtServerUrl = findViewById(R.id.edtServerUrl);
        edtApiToken = findViewById(R.id.edtApiToken);
        btnSync = findViewById(R.id.btnSync);
        btnTriggerCrawl = findViewById(R.id.btnTriggerCrawl);
        btnAutoSyncToggle = findViewById(R.id.btnAutoSyncToggle);
        tvStatus = findViewById(R.id.tvStatus);
        tvEmpty = findViewById(R.id.tvEmpty);
        progressBar = findViewById(R.id.progressBar);

        tabGasoline = findViewById(R.id.tabGasoline);
        tabWeather = findViewById(R.id.tabWeather);
        tabJson = findViewById(R.id.tabJson);
        tabEvents = findViewById(R.id.tabEvents);

        layoutGasoline = findViewById(R.id.layoutGasoline);
        layoutWeather = findViewById(R.id.layoutWeather);
        layoutJson = findViewById(R.id.layoutJson);
        layoutEvents = findViewById(R.id.layoutEvents);

        tvWeatherLocation = findViewById(R.id.tvWeatherLocation);
        tvWeatherTemp = findViewById(R.id.tvWeatherTemp);
        tvWeatherCondition = findViewById(R.id.tvWeatherCondition);
        tvWeatherUpdated = findViewById(R.id.tvWeatherUpdated);
        tvWeatherHumidity = findViewById(R.id.tvWeatherHumidity);
        tvWeatherWind = findViewById(R.id.tvWeatherWind);
        tvWeatherUv = findViewById(R.id.tvWeatherUv);
        tvWeatherPrecip = findViewById(R.id.tvWeatherPrecip);
        tvWeatherSource = findViewById(R.id.tvWeatherSource);

        btnJsonSummary = findViewById(R.id.btnJsonSummary);
        btnJsonWeather = findViewById(R.id.btnJsonWeather);
        btnJsonGasoline = findViewById(R.id.btnJsonGasoline);
        btnCopyJson = findViewById(R.id.btnCopyJson);
        tvRawJson = findViewById(R.id.tvRawJson);

        recyclerViewGasoline = findViewById(R.id.recyclerViewGasoline);
        recyclerViewEvents = findViewById(R.id.recyclerViewEvents);

        // Load saved settings from SharedPreferences
        SharedPreferences prefs = getSharedPreferences(SyncWorker.PREFS_NAME, MODE_PRIVATE);
        String savedUrl = prefs.getString(SyncWorker.KEY_SERVER_URL, "http://10.0.2.2:8080/api/");
        String savedToken = prefs.getString(SyncWorker.KEY_API_TOKEN, "crawlbot_secret_token_2026");
        isAutoSyncEnabled = prefs.getBoolean(SyncWorker.KEY_AUTO_SYNC_ENABLED, true);

        edtServerUrl.setText(savedUrl);
        edtApiToken.setText(savedToken);
        ServerApiClient.setBaseUrl(savedUrl);
        ServerApiClient.setAuthToken(savedToken);

        updateAutoSyncButtonStyle();
    }

    private void updateAutoSyncButtonStyle() {
        if (isAutoSyncEnabled) {
            btnAutoSyncToggle.setText("Phát Tự Động: BẬT");
            btnAutoSyncToggle.setBackgroundColor(Color.parseColor("#2E7D32"));
        } else {
            btnAutoSyncToggle.setText("Phát Tự Động: TẮT");
            btnAutoSyncToggle.setBackgroundColor(Color.parseColor("#757575"));
        }
    }

    private void setupRecyclerViews() {
        recyclerViewGasoline.setLayoutManager(new LinearLayoutManager(this));
        gasolineAdapter = new GasolineAdapter();
        recyclerViewGasoline.setAdapter(gasolineAdapter);

        recyclerViewEvents.setLayoutManager(new LinearLayoutManager(this));
        changeEventAdapter = new ChangeEventAdapter();
        recyclerViewEvents.setAdapter(changeEventAdapter);
    }

    private void setupListeners() {
        btnSync.setOnClickListener(v -> performSync());
        btnTriggerCrawl.setOnClickListener(v -> triggerCrawlOnServer());

        btnAutoSyncToggle.setOnClickListener(v -> {
            isAutoSyncEnabled = !isAutoSyncEnabled;
            saveSettings();
            updateAutoSyncButtonStyle();

            if (isAutoSyncEnabled) {
                // Persistent background sync via Android WorkManager
                SyncWorker.enqueuePeriodicSync(this);
                foregroundHandler.postDelayed(foregroundRunnable, 60000);
                Toast.makeText(this, "Đã bật chế độ tự động đồng bộ (WorkManager chạy ngầm)", Toast.LENGTH_SHORT).show();
            } else {
                // Cancel WorkManager task
                SyncWorker.cancelPeriodicSync(this);
                foregroundHandler.removeCallbacks(foregroundRunnable);
                Toast.makeText(this, "Đã tắt chế độ tự động đồng bộ", Toast.LENGTH_SHORT).show();
            }
        });

        tabGasoline.setOnClickListener(v -> switchTab(0));
        tabWeather.setOnClickListener(v -> switchTab(1));
        tabJson.setOnClickListener(v -> switchTab(2));
        tabEvents.setOnClickListener(v -> switchTab(3));

        btnJsonSummary.setOnClickListener(v -> {
            currentJsonSubTab = 0;
            updateJsonSubTabStyle();
            renderJsonView();
        });

        btnJsonWeather.setOnClickListener(v -> {
            currentJsonSubTab = 1;
            updateJsonSubTabStyle();
            renderJsonView();
        });

        btnJsonGasoline.setOnClickListener(v -> {
            currentJsonSubTab = 2;
            updateJsonSubTabStyle();
            renderJsonView();
        });

        btnCopyJson.setOnClickListener(v -> {
            String text = tvRawJson.getText().toString();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("CrawlBot_JSON", text));
                Toast.makeText(this, "Đã sao chép JSON vào bộ nhớ tạm!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveSettings() {
        String url = edtServerUrl.getText().toString().trim();
        String token = edtApiToken.getText().toString().trim();
        ServerApiClient.setBaseUrl(url);
        ServerApiClient.setAuthToken(token);

        SharedPreferences prefs = getSharedPreferences(SyncWorker.PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(SyncWorker.KEY_SERVER_URL, url)
                .putString(SyncWorker.KEY_API_TOKEN, token)
                .putBoolean(SyncWorker.KEY_AUTO_SYNC_ENABLED, isAutoSyncEnabled)
                .apply();
    }

    private void switchTab(int tabIndex) {
        activeTab = tabIndex;
        int activeColor = Color.parseColor("#1976D2");
        int inactiveColor = Color.parseColor("#9E9E9E");

        tabGasoline.setBackgroundColor(tabIndex == 0 ? activeColor : inactiveColor);
        tabWeather.setBackgroundColor(tabIndex == 1 ? activeColor : inactiveColor);
        tabJson.setBackgroundColor(tabIndex == 2 ? activeColor : inactiveColor);
        tabEvents.setBackgroundColor(tabIndex == 3 ? activeColor : inactiveColor);

        layoutGasoline.setVisibility(tabIndex == 0 ? View.VISIBLE : View.GONE);
        layoutWeather.setVisibility(tabIndex == 1 ? View.VISIBLE : View.GONE);
        layoutJson.setVisibility(tabIndex == 2 ? View.VISIBLE : View.GONE);
        layoutEvents.setVisibility(tabIndex == 3 ? View.VISIBLE : View.GONE);

        loadDataFromRoom();
    }

    private void updateJsonSubTabStyle() {
        int activeSub = Color.parseColor("#00695C");
        int inactiveSub = Color.parseColor("#78909C");

        btnJsonSummary.setBackgroundColor(currentJsonSubTab == 0 ? activeSub : inactiveSub);
        btnJsonWeather.setBackgroundColor(currentJsonSubTab == 1 ? activeSub : inactiveSub);
        btnJsonGasoline.setBackgroundColor(currentJsonSubTab == 2 ? activeSub : inactiveSub);
    }

    private void performSync() {
        saveSettings();
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Đang kết nối Server Crawler để nhận tin tức...");

        syncManager.syncWithServer(new SyncManager.SyncCallback() {
            @Override
            public void onSuccess(String statusMsg) {
                progressBar.setVisibility(View.GONE);
                String timeStr = java.time.LocalTime.now().toString().substring(0, 8);
                tvStatus.setText("Đồng bộ & phát thông tin thành công lúc " + timeStr);
                loadDataFromRoom();
            }

            @Override
            public void onError(String errorMsg) {
                progressBar.setVisibility(View.GONE);
                tvStatus.setText(errorMsg);
                Toast.makeText(MainActivity.this, errorMsg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void performSyncSilently() {
        syncManager.syncWithServer(new SyncManager.SyncCallback() {
            @Override
            public void onSuccess(String statusMsg) {
                String timeStr = java.time.LocalTime.now().toString().substring(0, 8);
                tvStatus.setText("Tự động đồng bộ thành công lúc: " + timeStr);
                loadDataFromRoom();
            }

            @Override
            public void onError(String errorMsg) {
                // Silent in background, no toast
            }
        });
    }

    private void triggerCrawlOnServer() {
        saveSettings();
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Yêu cầu Bot trên Server quét trang web ngay lập tức...");

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject res = ServerApiClient.triggerServerCrawl();
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, res.optString("message", "Server đã kích hoạt Bot Crawler!"), Toast.LENGTH_SHORT).show();
                    // Wait 3.5 seconds for python crawler to complete scrape, then sync
                    layoutGasoline.postDelayed(MainActivity.this::performSync, 3500);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    String err = e.getMessage() != null ? e.getMessage() : "Lỗi kết nối";
                    tvStatus.setText("Lỗi kích hoạt crawl: " + err);
                    Toast.makeText(MainActivity.this, err, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadDataFromRoom() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<GasolineItem> gasItems = db.gasolineDao().getAllGasoline();
            WeatherItem weather = db.weatherDao().getLatestWeather();
            List<ChangeEventItem> events = db.changeEventDao().getAllEvents();

            runOnUiThread(() -> {
                // 1. Update Gasoline
                gasolineAdapter.updateData(gasItems);

                // 2. Update Weather
                if (weather != null && !"N/A".equalsIgnoreCase(weather.getTemperature())) {
                    tvWeatherLocation.setText("🌤️ " + weather.getLocation());
                    tvWeatherTemp.setText(weather.getTemperature());
                    tvWeatherCondition.setText(weather.getCondition());
                    tvWeatherHumidity.setText(weather.getHumidity());
                    tvWeatherWind.setText(weather.getWind());
                    tvWeatherUv.setText(weather.getUvIndex());
                    tvWeatherPrecip.setText(weather.getPrecipitationProb());
                    tvWeatherSource.setText(weather.getSourceUrl());
                    tvWeatherUpdated.setText("Cập nhật bởi Bot lúc: " + weather.getUpdatedAt());
                } else {
                    tvWeatherLocation.setText("🌤️ Chưa có dữ liệu thời tiết");
                    tvWeatherTemp.setText("--°C");
                    tvWeatherCondition.setText("Chưa có");
                    tvWeatherHumidity.setText("--");
                    tvWeatherWind.setText("--");
                    tvWeatherUv.setText("--");
                    tvWeatherPrecip.setText("--");
                    tvWeatherSource.setText("--");
                    tvWeatherUpdated.setText("Chưa có dữ liệu crawl");
                }

                // 3. Update Events
                changeEventAdapter.updateData(events);

                // 4. Update JSON Viewer
                renderJsonView();

                // 5. Update empty text based on active tab
                if (activeTab == 0) {
                    tvEmpty.setVisibility(gasItems.isEmpty() ? View.VISIBLE : View.GONE);
                    tvEmpty.setText("Chưa có dữ liệu giá xăng trong SQLite. Bấm 'Crawl Bot' hoặc 'Đồng bộ'.");
                } else if (activeTab == 1) {
                    tvEmpty.setVisibility(weather == null ? View.VISIBLE : View.GONE);
                    tvEmpty.setText("Chưa có dự báo thời tiết. Bấm 'Crawl Bot' hoặc 'Đồng bộ'.");
                } else if (activeTab == 3) {
                    tvEmpty.setVisibility(events.isEmpty() ? View.VISIBLE : View.GONE);
                    tvEmpty.setText("Chưa có biến động giá xăng nào được ghi nhận.");
                } else {
                    tvEmpty.setVisibility(View.GONE);
                }
            });
        });
    }

    private void renderJsonView() {
        try {
            String jsonToDisplay = "";
            if (currentJsonSubTab == 0) {
                // Summary JSON
                String raw = ServerApiClient.getLastRawDailySummaryJson();
                if (raw != null && !raw.isBlank()) {
                    jsonToDisplay = new JSONObject(raw).toString(2);
                } else {
                    jsonToDisplay = buildFallbackSummaryJson();
                }
            } else if (currentJsonSubTab == 1) {
                // Weather JSON
                String raw = ServerApiClient.getLastRawWeatherJson();
                if (raw != null && !raw.isBlank()) {
                    jsonToDisplay = new JSONObject(raw).toString(2);
                } else {
                    WeatherItem w = db.weatherDao().getLatestWeather();
                    if (w != null && !"N/A".equalsIgnoreCase(w.getTemperature())) {
                        JSONObject obj = new JSONObject();
                        obj.put("location", w.getLocation());
                        obj.put("temperature", w.getTemperature());
                        obj.put("condition", w.getCondition());
                        obj.put("humidity", w.getHumidity());
                        obj.put("wind", w.getWind());
                        obj.put("uv_index", w.getUvIndex());
                        obj.put("precipitation_prob", w.getPrecipitationProb());
                        obj.put("updated_at", w.getUpdatedAt());
                        jsonToDisplay = obj.toString(2);
                    } else {
                        jsonToDisplay = "{\n  \"status\": \"NO_DATA\",\n  \"message\": \"Chưa có dữ liệu thời tiết thực tế từ Bot. Bấm Crawl Bot hoặc Đồng bộ.\"\n}";
                    }
                }
            } else {
                // Gasoline JSON
                String raw = ServerApiClient.getLastRawGasolineJson();
                if (raw != null && !raw.isBlank()) {
                    jsonToDisplay = new JSONObject(raw).toString(2);
                } else {
                    List<GasolineItem> items = db.gasolineDao().getAllGasoline();
                    if (!items.isEmpty()) {
                        JSONArray arr = new JSONArray();
                        for (GasolineItem it : items) {
                            JSONObject o = new JSONObject();
                            o.put("product", it.getProduct());
                            o.put("price", it.getPrice());
                            o.put("zone_1_price", it.getZone1Price());
                            o.put("zone_2_price", it.getZone2Price());
                            o.put("diff", it.getDiff());
                            o.put("updated_at", it.getUpdatedAt());
                            arr.put(o);
                        }
                        JSONObject root = new JSONObject();
                        root.put("success", true);
                        root.put("status", "AVAILABLE");
                        root.put("count", items.size());
                        root.put("data", arr);
                        jsonToDisplay = root.toString(2);
                    } else {
                        jsonToDisplay = "{\n  \"status\": \"NO_DATA\",\n  \"message\": \"Chưa có dữ liệu giá xăng thực tế từ Bot. Bấm Crawl Bot hoặc Đồng bộ.\"\n}";
                    }
                }
            }
            tvRawJson.setText(jsonToDisplay);
        } catch (Exception e) {
            tvRawJson.setText("{\n  \"error\": \"" + e.getMessage() + "\"\n}");
        }
    }

    private String buildFallbackSummaryJson() {
        try {
            WeatherItem w = db.weatherDao().getLatestWeather();
            List<GasolineItem> items = db.gasolineDao().getAllGasoline();

            if (w == null && items.isEmpty()) {
                return "{\n  \"status\": \"NO_DATA\",\n  \"message\": \"Chưa có dữ liệu tổng hợp. Bấm Đồng bộ hoặc Crawl Bot.\"\n}";
            }

            JSONObject root = new JSONObject();
            root.put("success", true);
            root.put("title", "Bản Tin Tổng Hợp Hằng Ngày: Thời Tiết & Giá Xăng");
            root.put("source", "Crawler Bot Client SQLite DB");

            if (w != null && !"N/A".equalsIgnoreCase(w.getTemperature())) {
                JSONObject wObj = new JSONObject();
                wObj.put("location", w.getLocation());
                wObj.put("temperature", w.getTemperature());
                wObj.put("condition", w.getCondition());
                wObj.put("humidity", w.getHumidity());
                wObj.put("wind", w.getWind());
                wObj.put("uv_index", w.getUvIndex());
                root.put("weather", wObj);
            } else {
                root.put("weather", JSONObject.NULL);
            }

            if (!items.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (GasolineItem it : items) {
                    JSONObject o = new JSONObject();
                    o.put("product", it.getProduct());
                    o.put("price", it.getPrice());
                    o.put("zone_1", it.getZone1Price());
                    o.put("zone_2", it.getZone2Price());
                    arr.put(o);
                }
                root.put("gasoline", arr);
            } else {
                root.put("gasoline", new JSONArray());
            }

            return root.toString(2);
        } catch (Exception ignored) {
            return "{\n  \"status\": \"NO_DATA\"\n}";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        foregroundHandler.removeCallbacks(foregroundRunnable);
    }
}