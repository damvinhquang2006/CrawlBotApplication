package com.example.crawlbotapplication.network;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ServerApiClient {

    // Default: 10.0.2.2 connects from Android Emulator to the host computer's localhost
    private static String baseUrl = "http://10.0.2.2:8080/api/";

    // Security API Authentication Token
    private static String authToken = "crawlbot_secret_token_2026";

    // Network timeouts (in milliseconds)
    public static final int CONNECT_TIMEOUT_MS = 10000;
    public static final int READ_TIMEOUT_MS = 10000;

    // Store raw JSON responses from the Crawler Bot server for UI inspection
    private static volatile String lastRawGasolineJson = "";
    private static volatile String lastRawWeatherJson = "";
    private static volatile String lastRawDailySummaryJson = "";

    public static synchronized void setBaseUrl(String url) {
        if (url != null && !url.isBlank()) {
            if (!url.endsWith("/")) {
                url = url + "/";
            }
            if (!url.endsWith("api/")) {
                url = url + "api/";
            }
            baseUrl = url;
        }
    }

    public static synchronized String getBaseUrl() {
        return baseUrl;
    }

    public static synchronized void setAuthToken(String token) {
        if (token != null) {
            authToken = token.trim();
        }
    }

    public static synchronized String getAuthToken() {
        return authToken;
    }

    public static String getLastRawGasolineJson() {
        return lastRawGasolineJson;
    }

    public static String getLastRawWeatherJson() {
        return lastRawWeatherJson;
    }

    public static String getLastRawDailySummaryJson() {
        return lastRawDailySummaryJson;
    }

    private static String executeGet(String endpoint) throws Exception {
        URL url = new URL(baseUrl + endpoint);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");

            if (authToken != null && !authToken.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + authToken);
                conn.setRequestProperty("X-API-Key", authToken);
            }

            int code = conn.getResponseCode();
            try (InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                String respStr = sb.toString();

                if (code == 401) {
                    throw new RuntimeException("401 Unauthorized: Sai hoặc thiếu token xác thực bảo mật API.");
                } else if (code >= 200 && code < 300) {
                    return respStr;
                } else {
                    throw new RuntimeException("Server API returned HTTP " + code);
                }
            }
        } catch (java.net.SocketTimeoutException | java.net.ConnectException ce) {
            throw new Exception("Không thể kết nối tới Server API (" + url + "). Hãy đảm bảo Server Crawler (run.bat) đang chạy trên máy tính! [Chi tiết: " + ce.getMessage() + "]", ce);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static JSONObject fetchLatestGasoline() throws Exception {
        String json = executeGet("gasoline/latest");
        lastRawGasolineJson = json;
        return new JSONObject(json);
    }

    public static JSONObject fetchChangeEvents(int limit) throws Exception {
        String json = executeGet("change-events?limit=" + limit);
        return new JSONObject(json);
    }

    public static JSONObject fetchLatestWeather() throws Exception {
        String json = executeGet("weather/latest");
        lastRawWeatherJson = json;
        return new JSONObject(json);
    }

    public static JSONObject fetchDailySummary() throws Exception {
        String json = executeGet("daily-summary");
        lastRawDailySummaryJson = json;
        return new JSONObject(json);
    }

    public static JSONObject triggerServerCrawl() throws Exception {
        URL url = new URL(baseUrl + "crawl/trigger");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");

            if (authToken != null && !authToken.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + authToken);
                conn.setRequestProperty("X-API-Key", authToken);
            }

            int code = conn.getResponseCode();
            try (InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                String respStr = sb.toString();
                JSONObject res = new JSONObject(respStr);

                if (code == 401) {
                    throw new RuntimeException("401 Unauthorized: Sai hoặc thiếu Token xác thực API Server.");
                } else if (code == 429) {
                    String errMsg = res.optString("error", "Rate limit: Vui lòng chờ cooldown giữa các lần quét.");
                    throw new RuntimeException(errMsg);
                } else if (code >= 200 && code < 300) {
                    return res;
                } else {
                    String errMsg = res.optString("error", "HTTP " + code);
                    throw new RuntimeException("Server báo lỗi (" + code + "): " + errMsg);
                }
            }
        } catch (java.net.SocketTimeoutException | java.net.ConnectException ce) {
            throw new Exception("Không thể kết nối tới Server API (" + url + "). Hãy đảm bảo Server Crawler (run.bat) đang chạy trên máy tính! [Chi tiết: " + ce.getMessage() + "]", ce);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
