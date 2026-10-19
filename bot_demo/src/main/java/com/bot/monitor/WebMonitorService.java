package com.bot.monitor;

import com.bot.bridge.PythonBridge;
import com.bot.db.DatabaseManager;
import com.bot.model.CrawlResponse;
import com.bot.model.TargetSite;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebMonitorService {
    private final PythonBridge pythonBridge;
    private final DatabaseManager databaseManager;
    private final List<TargetSite> sites;
    private final int pollIntervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public WebMonitorService(PythonBridge pythonBridge, List<TargetSite> sites, int pollIntervalSeconds) {
        this(pythonBridge, null, sites, pollIntervalSeconds);
    }

    public WebMonitorService(PythonBridge pythonBridge, DatabaseManager databaseManager,
                             List<TargetSite> sites, int pollIntervalSeconds) {
        this.pythonBridge = pythonBridge;
        this.databaseManager = databaseManager;
        this.sites = sites;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WebMonitor-Thread");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (isRunning.compareAndSet(false, true)) {
            System.out.println(">>> [KHOI DONG] Bot bat dau giam sat dinh ky moi " + pollIntervalSeconds + " giay...");
            scheduler.scheduleWithFixedDelay(this::checkAllSites, 0, pollIntervalSeconds, TimeUnit.SECONDS);
        }
    }

    public synchronized void stop() {
        if (isRunning.compareAndSet(true, false)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            System.out.println(">>> [DUNG] Da dung dich vu giam sat.");
        }
    }

    public void checkAllSites() {
        for (TargetSite site : sites) {
            if (!isRunning.get()) {
                break;
            }
            try {
                checkSingleSite(site);
            } catch (Exception e) {
                System.err.println("[LOI] Khong the kiem tra site " + site.getName() + ": " + e.getMessage());
            }
        }
    }

    public void checkSingleSite(TargetSite site) {
        LocalDateTime checkTime = LocalDateTime.now();
        site.setLastChecked(checkTime);

        CrawlResponse response = pythonBridge.crawl(site.getUrl(), site.getType(), site.getLastHash());

        if (!response.isSuccess()) {
            System.err.printf("[%s] [CANH BAO] Khong the cao du lieu tu '%s': %s%n",
                    checkTime.format(timeFormatter), site.getName(), response.getError());
            return;
        }

        if (response.isChanged()) {
            site.setLastHash(response.getHash());
            site.setLastUpdated(checkTime);

            // ATOMIC DATABASE TRANSACTION & SYNC
            if (databaseManager != null && response.getData() != null) {
                String cat = response.getCategory() != null ? response.getCategory().toUpperCase() : "";
                if (cat.contains("GASOLINE")) {
                    JsonNode items = response.getData().path("items");
                    List<String> events = databaseManager.processGasolineUpdateTransaction(items, site.getUrl());
                    if (!events.isEmpty()) {
                        System.out.println(">>> [DB TRANSACTION] Phat hien " + events.size() + " bien dong gia xăng!");
                        for (String ev : events) {
                            System.out.println("    * " + ev);
                        }
                    }
                } else if (cat.contains("WEATHER")) {
                    JsonNode d = response.getData();
                    String loc = d.path("location").asText(site.getName()).trim();
                    String temp = d.path("temperature").asText("N/A").trim();
                    String cond = d.path("condition").asText("N/A").trim();
                    String hum = d.path("details").path("humidity").asText("N/A").trim();
                    String wind = d.path("details").path("wind").asText("N/A").trim();
                    String uv = d.path("details").path("uv_index").asText("N/A").trim();
                    String precip = d.path("precipitation_probability").asText("N/A").trim();

                    // Kiem tra tinh hop le truoc khi chap nhan du lieu vao bang du lieu chinh
                    boolean isTempValid = !temp.isEmpty() && !"N/A".equalsIgnoreCase(temp);
                    boolean isCondValid = !cond.isEmpty() && !"N/A".equalsIgnoreCase(cond);
                    boolean isLocValid = !loc.isEmpty() && !"N/A".equalsIgnoreCase(loc);

                    if (isTempValid && isCondValid && isLocValid) {
                        databaseManager.saveWeatherLatest(loc, temp, cond, hum, wind, uv, precip, site.getUrl());
                    } else {
                        System.err.printf("[%s] [TU CHOI DU LIEU THOI TIET] Nguon '%s' tra ve du lieu thieu thong tin quan trong (temp='%s', cond='%s', loc='%s'). Bo qua luu vao CSDL chinh!%n",
                                checkTime.format(timeFormatter), site.getName(), temp, cond, loc);
                    }
                }
            }

            // Print alert header and JSON structure to terminal
            printUpdateAlert(site, response, checkTime);
        } else {
            // Unchanged: print a brief status heartbeat
            String shortHash = (response.getHash() != null && response.getHash().length() >= 8)
                    ? response.getHash().substring(0, 8) : "N/A";
            System.out.printf("[%s] [GIAM SAT] Khong co thay doi tren '%s' (Hash: %s)%n",
                    checkTime.format(timeFormatter), site.getName(), shortHash);
        }
    }

    private void printUpdateAlert(TargetSite site, CrawlResponse response, LocalDateTime checkTime) {
        String jsonPretty = pythonBridge.formatPrettyJson(response);

        System.out.println();
        System.out.println("==========================================================================================");
        System.out.println(">>> [THONG BAO CAP NHAT] TRANG WEB CO NOI DUNG HTML/JSON MOI DUOC CAP NHAT!");
        System.out.println("------------------------------------------------------------------------------------------");
        System.out.printf("  Nguon theo doi : [%s] %s%n", site.getId(), site.getName());
        System.out.printf("  Dia chi URL    : %s%n", site.getUrl());
        System.out.printf("  Phan loai      : %s%n", response.getCategory());
        System.out.printf("  Ma SHA-256     : %s%n", response.getHash());
        System.out.printf("  Thoi gian phat : %s%n", checkTime.format(timeFormatter));
        System.out.println("------------------------------------------------------------------------------------------");
        System.out.println("DU LIEU CAO DUOC (DINH DANG JSON):");
        System.out.println(jsonPretty);
        System.out.println("==========================================================================================");
        System.out.println();
    }

    public List<TargetSite> getSites() {
        return sites;
    }

    public boolean isRunning() {
        return isRunning.get();
    }
}
