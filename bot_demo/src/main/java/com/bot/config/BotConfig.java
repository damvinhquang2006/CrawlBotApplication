package com.bot.config;

import com.bot.model.TargetSite;
import com.bot.security.UrlSecurityValidator;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class BotConfig {
    // Mac dinh 12 tieng = 21600 giay de toi uu hieu nang
    private int pollIntervalSeconds = 21600;
    private int processTimeoutSeconds = 30;
    private int serverApiPort = 8080;
    private String databasePath = "data/crawler_server.db";
    private boolean allowLocalMock = false;
    private String pythonCommand = "py";
    private String accuweatherApiKey = "";
    private String accuweatherLocationKey = "353412"; // Default: Hanoi
    private String apiAuthToken = "crawlbot_secret_token_2026";
    private int crawlCooldownSeconds = 60;
    private final List<String> allowedDomains = new ArrayList<>();
    private final List<TargetSite> targetSites = new ArrayList<>();

    public static BotConfig load() {
        BotConfig config = new BotConfig();
        Properties props = new Properties();

        // Check external config.properties first, then classpath
        File externalFile = new File("config.properties");
        if (externalFile.exists()) {
            try (java.io.Reader reader = new java.io.InputStreamReader(new FileInputStream(externalFile), java.nio.charset.StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (Exception e) {
                System.err.println("[WARN] Khong the doc config.properties ben ngoai: " + e.getMessage());
            }
        } else {
            try (InputStream in = BotConfig.class.getClassLoader().getResourceAsStream("config.properties")) {
                if (in != null) {
                    try (java.io.Reader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                        props.load(reader);
                    }
                }
            } catch (Exception e) {
                System.err.println("[WARN] Khong the doc classpath config.properties: " + e.getMessage());
            }
        }

        // 1. Poll Interval (Mac dinh 12h = 21600s)
        String intervalStr = props.getProperty("bot.poll.interval.seconds");
        if (intervalStr != null && !intervalStr.isBlank()) {
            try {
                config.pollIntervalSeconds = Integer.parseInt(intervalStr.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        // 2. Process Timeout
        String timeoutStr = props.getProperty("bot.process.timeout.seconds");
        if (timeoutStr != null && !timeoutStr.isBlank()) {
            try {
                config.processTimeoutSeconds = Integer.parseInt(timeoutStr.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        // 3. Server REST API Port
        String portStr = props.getProperty("server.api.port");
        if (portStr != null && !portStr.isBlank()) {
            try {
                config.serverApiPort = Integer.parseInt(portStr.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        // 4. API Authentication Token & Rate limit cooldown
        String tokenStr = props.getProperty("server.api.auth.token");
        if (tokenStr != null) {
            config.apiAuthToken = tokenStr.trim();
        }

        String cooldownStr = props.getProperty("crawl.trigger.cooldown.seconds");
        if (cooldownStr != null && !cooldownStr.isBlank()) {
            try {
                config.crawlCooldownSeconds = Integer.parseInt(cooldownStr.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        // 5. Server Database Path
        String dbPathStr = props.getProperty("database.path");
        if (dbPathStr != null && !dbPathStr.isBlank()) {
            config.databasePath = dbPathStr.trim();
        }

        // 6. Local Mock Allowance
        String mockStr = props.getProperty("bot.allow.local.mock");
        if (mockStr != null && !mockStr.isBlank()) {
            config.allowLocalMock = Boolean.parseBoolean(mockStr.trim());
        }

        // 7. Python Command
        String pyCmd = props.getProperty("python.command");
        if (pyCmd != null && !pyCmd.isBlank()) {
            config.pythonCommand = pyCmd.trim();
        }

        // 8. AccuWeather Config
        String accKey = props.getProperty("accuweather.api.key");
        if (accKey != null && !accKey.isBlank()) {
            config.accuweatherApiKey = accKey.trim();
        }
        String accLoc = props.getProperty("accuweather.location.key");
        if (accLoc != null && !accLoc.isBlank()) {
            config.accuweatherLocationKey = accLoc.trim();
        }

        // 9. Allowed Domains
        String domainsStr = props.getProperty("bot.allowed.domains");
        if (domainsStr != null && !domainsStr.isBlank()) {
            String[] tokens = domainsStr.split(",");
            for (String t : tokens) {
                if (!t.trim().isEmpty()) {
                    config.allowedDomains.add(t.trim().toLowerCase());
                }
            }
        }

        // 10. Parse Target Sites with SSRF Validation & API Key check
        String targetsStr = props.getProperty("targets");
        if (targetsStr != null && !targetsStr.isBlank()) {
            String[] siteTokens = targetsStr.split(";");
            for (String token : siteTokens) {
                String clean = token.trim();
                if (clean.isEmpty()) continue;
                String[] parts = clean.split("\\|");
                if (parts.length >= 3) {
                    String id = parts[0].trim();
                    String name = parts[1].trim();
                    String url = parts[2].trim();
                    String type = (parts.length >= 4) ? parts[3].trim() : "auto";

                    // Kiem tra neu la site AccuWeather nhung khong co API key thi bo qua de tranh ghi de du lieu N/A
                    if ((type.equalsIgnoreCase("accuweather_api") || url.contains("dataservice.accuweather.com"))
                            && (config.accuweatherApiKey == null || config.accuweatherApiKey.isBlank())) {
                        System.out.println("[CONFIG-INFO] Bo qua target '" + id + "' do chua cau hinh accuweather.api.key.");
                        continue;
                    }

                    // Validate SSRF security for each target URL
                    UrlSecurityValidator.ValidationResult result = UrlSecurityValidator.validate(
                            url,
                            config.allowLocalMock,
                            config.allowedDomains
                    );

                    if (result.isValid()) {
                        config.targetSites.add(new TargetSite(id, name, url, type));
                    } else {
                        System.err.printf("[SSRF-BLOCK] Tu choi URL muc tieu '%s' (%s): %s%n",
                                id, url, result.getMessage());
                    }
                }
            }
        }

        // Default targets if none configured or all blocked
        if (config.targetSites.isEmpty()) {
            config.targetSites.add(new TargetSite(
                    "thoitiet_live",
                    "Dự báo thời tiết Hà Nội (Live)",
                    "https://thoitiet.vn/ha-noi",
                    "weather"
            ));
            config.targetSites.add(new TargetSite(
                    "giaxang_petrolimex",
                    "Giá xăng Petrolimex (Live)",
                    "https://webgia.com/gia-xang-dau/petrolimex/",
                    "gasoline"
            ));
            config.targetSites.add(new TargetSite(
                    "vnexpress_gas",
                    "Giá xăng dầu VnExpress (Chuyên đề & Bài mới)",
                    "https://vnexpress.net/chu-de/gia-xang-dau-3026",
                    "vnexpress_gasoline"
            ));
        }

        return config;
    }

    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    public int getProcessTimeoutSeconds() {
        return processTimeoutSeconds;
    }

    public int getServerApiPort() {
        return serverApiPort;
    }

    public String getDatabasePath() {
        return databasePath;
    }

    public boolean isAllowLocalMock() {
        return allowLocalMock;
    }

    public String getPythonCommand() {
        return pythonCommand;
    }

    public String getAccuweatherApiKey() {
        return accuweatherApiKey;
    }

    public String getAccuweatherLocationKey() {
        return accuweatherLocationKey;
    }

    public String getApiAuthToken() {
        return apiAuthToken;
    }

    public int getCrawlCooldownSeconds() {
        return crawlCooldownSeconds;
    }

    public List<String> getAllowedDomains() {
        return allowedDomains;
    }

    public List<TargetSite> getTargetSites() {
        return targetSites;
    }
}
