package com.bot.api;

import com.bot.db.DatabaseManager;
import com.bot.monitor.WebMonitorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Secure, Authenticated, and Rate-Limited Embedded REST API Server.
 * Connects Android Clients with the Server Crawler & SQLite Database.
 */
public class RestApiServer {

    private final int port;
    private final String apiAuthToken;
    private final int crawlCooldownSeconds;
    private final DatabaseManager dbManager;
    private final WebMonitorService monitorService;
    private final ObjectMapper objectMapper;
    private HttpServer server;

    // Rate Limiting & Concurrency Controls
    private final AtomicBoolean isCrawlRunning = new AtomicBoolean(false);
    private final AtomicLong lastCrawlTime = new AtomicLong(0);

    public RestApiServer(int port, String apiAuthToken, int crawlCooldownSeconds,
                         DatabaseManager dbManager, WebMonitorService monitorService) {
        this.port = port;
        this.apiAuthToken = apiAuthToken != null ? apiAuthToken.trim() : "";
        this.crawlCooldownSeconds = crawlCooldownSeconds > 0 ? crawlCooldownSeconds : 60;
        this.dbManager = dbManager;
        this.monitorService = monitorService;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public RestApiServer(int port, DatabaseManager dbManager, WebMonitorService monitorService) {
        this(port, "", 60, dbManager, monitorService);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "RestApi-Worker");
            t.setDaemon(true);
            return t;
        }));

        // Register endpoints
        server.createContext("/api/gasoline/latest", new LatestGasolineHandler());
        server.createContext("/api/gasoline/history", new GasolineHistoryHandler());
        server.createContext("/api/change-events", new ChangeEventsHandler());
        server.createContext("/api/weather/latest", new LatestWeatherHandler());
        server.createContext("/api/daily-summary", new DailySummaryHandler());
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/crawl/trigger", new TriggerCrawlHandler());

        server.start();
        System.out.printf(">>> [REST API] Server REST API da khoi dong tai: http://localhost:%d/api/%n", port);
        if (!apiAuthToken.isEmpty()) {
            System.out.println(">>> [REST API - SECURITY] Xac thuc API Token duoc BAT (Authorization: Bearer <token> hoac X-API-Key)");
        }
        System.out.printf(">>> [REST API - RATE LIMIT] Cooldown kich hoat crawl thu cong: %d giay%n", crawlCooldownSeconds);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            System.out.println(">>> [REST API] Da dung Server REST API.");
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        if (apiAuthToken.isEmpty()) {
            return true; // No auth required if token is empty
        }

        // 1. Check Authorization header (Bearer or ApiKey)
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null) {
            if (authHeader.startsWith("Bearer ") && authHeader.substring(7).trim().equals(apiAuthToken)) {
                return true;
            }
            if (authHeader.startsWith("ApiKey ") && authHeader.substring(7).trim().equals(apiAuthToken)) {
                return true;
            }
            if (authHeader.trim().equals(apiAuthToken)) {
                return true;
            }
        }

        // 2. Check X-API-Key header
        String apiKeyHeader = exchange.getRequestHeaders().getFirst("X-API-Key");
        if (apiKeyHeader != null && apiKeyHeader.trim().equals(apiAuthToken)) {
            return true;
        }

        // 3. Check query param: ?token=...
        Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
        String qToken = params.get("token");
        return qToken != null && qToken.trim().equals(apiAuthToken);
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, Object data) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) return map;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(key, val);
            }
        }
        return map;
    }

    // =========================================================================
    // ENDPOINT HANDLERS
    // =========================================================================

    private class LatestGasolineHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            List<Map<String, Object>> latest = dbManager.getLatestGasolinePrices();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);

            if (latest.isEmpty()) {
                resp.put("status", "NO_DATA");
                resp.put("message", "Chưa có dữ liệu giá xăng trong CSDL Server. Hãy kích hoạt crawl hoặc chờ chu kỳ quét.");
                resp.put("count", 0);
                resp.put("data", Collections.emptyList());
            } else {
                resp.put("status", "AVAILABLE");
                resp.put("count", latest.size());
                resp.put("data", latest);
            }
            sendJsonResponse(exchange, 200, resp);
        }
    }

    private class GasolineHistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
            String product = params.get("product");
            int limit = 50;
            if (params.containsKey("limit")) {
                try {
                    limit = Integer.parseInt(params.get("limit"));
                } catch (NumberFormatException ignored) {}
            }
            List<Map<String, Object>> history = dbManager.getGasolineHistory(product, limit);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("product_filter", product != null ? product : "ALL");
            resp.put("count", history.size());
            resp.put("data", history);
            sendJsonResponse(exchange, 200, resp);
        }
    }

    private class ChangeEventsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
            int limit = 50;
            if (params.containsKey("limit")) {
                try {
                    limit = Integer.parseInt(params.get("limit"));
                } catch (NumberFormatException ignored) {}
            }
            List<Map<String, Object>> events = dbManager.getChangeEvents(limit);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("count", events.size());
            resp.put("data", events);
            sendJsonResponse(exchange, 200, resp);
        }
    }

    private class LatestWeatherHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> weather = dbManager.getLatestWeather();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);

            if (weather == null) {
                resp.put("status", "NO_DATA");
                resp.put("message", "Chưa có dữ liệu thời tiết trong CSDL Server. Hãy kích hoạt crawl hoặc chờ chu kỳ quét.");
                resp.put("data", null);
            } else {
                resp.put("status", "AVAILABLE");
                resp.put("data", weather);
            }
            sendJsonResponse(exchange, 200, resp);
        }
    }

    private class DailySummaryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> weather = dbManager.getLatestWeather();
            List<Map<String, Object>> gasoline = dbManager.getLatestGasolinePrices();
            List<Map<String, Object>> events = dbManager.getChangeEvents(10);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("title", "Bản Tin Tổng Hợp Hằng Ngày: Dự Báo Thời Tiết & Giá Xăng Dầu");
            resp.put("generated_at", LocalDateTime.now().toString());

            if (weather == null && gasoline.isEmpty()) {
                resp.put("status", "NO_DATA");
                resp.put("message", "Chưa có dữ liệu thời tiết và giá xăng. Hãy kích hoạt crawl để thu thập dữ liệu.");
            } else if (weather == null || gasoline.isEmpty()) {
                resp.put("status", "PARTIAL_DATA");
            } else {
                resp.put("status", "AVAILABLE");
            }

            resp.put("weather", weather);
            resp.put("gasoline", gasoline);
            resp.put("gasoline_count", gasoline.size());
            resp.put("recent_events", events);
            sendJsonResponse(exchange, 200, resp);
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> status = new HashMap<>();
            status.put("status", "RUNNING");
            status.put("server_time", LocalDateTime.now().toString());
            status.put("database", "SQLite (data/crawler_server.db)");
            status.put("scheduler_running", monitorService != null && monitorService.isRunning());
            status.put("target_sites_count", monitorService != null ? monitorService.getSites().size() : 0);
            status.put("auth_required", !apiAuthToken.isEmpty());
            status.put("crawl_running", isCrawlRunning.get());
            status.put("crawl_cooldown_seconds", crawlCooldownSeconds);
            sendJsonResponse(exchange, 200, status);
        }
    }

    private class TriggerCrawlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 1. Chi cho phep POST de phong ngua CSRF / Crawl vo tinh qua GET
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, Map.of(
                        "success", false,
                        "error", "Method Not Allowed. Only POST is accepted for triggering crawl."
                ));
                return;
            }

            // 2. Kiem tra xac thuc Token bao mat
            if (!isAuthorized(exchange)) {
                sendJsonResponse(exchange, 401, Map.of(
                        "success", false,
                        "error", "Unauthorized. Missing or invalid API authentication token."
                ));
                return;
            }

            // 3. Kiem tra khoa tien trinh dong thoi (Concurrency Guard)
            if (isCrawlRunning.get()) {
                sendJsonResponse(exchange, 429, Map.of(
                        "success", false,
                        "error", "A crawl job is already in progress on the server. Please wait for completion."
                ));
                return;
            }

            // 4. Kiem tra gioi han tan suat goi (Rate Limiting / Cooldown)
            long now = System.currentTimeMillis();
            long elapsedSeconds = (now - lastCrawlTime.get()) / 1000;
            if (lastCrawlTime.get() > 0 && elapsedSeconds < crawlCooldownSeconds) {
                long remaining = crawlCooldownSeconds - elapsedSeconds;
                sendJsonResponse(exchange, 429, Map.of(
                        "success", false,
                        "error", String.format("Rate limit exceeded. Please wait %d seconds before triggering another crawl.", remaining)
                ));
                return;
            }

            // 5. Khoa va khoi chay tien trinh crawl bat dong bo
            lastCrawlTime.set(now);
            isCrawlRunning.set(true);

            new Thread(() -> {
                try {
                    if (monitorService != null) {
                        monitorService.checkAllSites();
                    }
                } finally {
                    isCrawlRunning.set(false);
                }
            }, "Manual-Crawl-Worker").start();

            sendJsonResponse(exchange, 200, Map.of(
                    "success", true,
                    "message", "Immediate crawl scan triggered asynchronously on server."
            ));
        }
    }

    public int getPort() {
        return port;
    }
}
