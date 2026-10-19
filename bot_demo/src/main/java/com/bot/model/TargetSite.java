package com.bot.model;

import java.time.LocalDateTime;

public class TargetSite {
    private String id;
    private String name;
    private String url;
    private String type; // "weather", "gasoline", "auto"
    private String lastHash = "";
    private LocalDateTime lastChecked;
    private LocalDateTime lastUpdated;

    public TargetSite(String id, String name, String url, String type) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getType() {
        return type;
    }

    public String getLastHash() {
        return lastHash;
    }

    public void setLastHash(String lastHash) {
        this.lastHash = lastHash;
    }

    public LocalDateTime getLastChecked() {
        return lastChecked;
    }

    public void setLastChecked(LocalDateTime lastChecked) {
        this.lastChecked = lastChecked;
    }

    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(LocalDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s (%s) - Hash: %s", id, name, url, 
                (lastHash == null || lastHash.isEmpty()) ? "None" : lastHash.substring(0, Math.min(8, lastHash.length())) + "...");
    }
}
