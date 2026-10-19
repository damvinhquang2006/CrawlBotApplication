package com.example.crawlbotapplication.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "weather_items")
public class WeatherItem {

    @PrimaryKey(autoGenerate = true)
    private int id;

    private String location;
    private String temperature;
    private String condition;
    private String humidity;
    private String wind;
    private String uvIndex;
    private String precipitationProb;
    private String sourceUrl;
    private String updatedAt;
    private String rawJson;

    public WeatherItem(String location, String temperature, String condition,
                       String humidity, String wind, String uvIndex,
                       String precipitationProb, String sourceUrl, String updatedAt, String rawJson) {
        this.location = location;
        this.temperature = temperature;
        this.condition = condition;
        this.humidity = humidity;
        this.wind = wind;
        this.uvIndex = uvIndex;
        this.precipitationProb = precipitationProb;
        this.sourceUrl = sourceUrl;
        this.updatedAt = updatedAt;
        this.rawJson = rawJson;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getLocation() { return location; }
    public String getTemperature() { return temperature; }
    public String getCondition() { return condition; }
    public String getHumidity() { return humidity; }
    public String getWind() { return wind; }
    public String getUvIndex() { return uvIndex; }
    public String getPrecipitationProb() { return precipitationProb; }
    public String getSourceUrl() { return sourceUrl; }
    public String getUpdatedAt() { return updatedAt; }
    public String getRawJson() { return rawJson; }

    public void setLocation(String location) { this.location = location; }
    public void setTemperature(String temperature) { this.temperature = temperature; }
    public void setCondition(String condition) { this.condition = condition; }
    public void setHumidity(String humidity) { this.humidity = humidity; }
    public void setWind(String wind) { this.wind = wind; }
    public void setUvIndex(String uvIndex) { this.uvIndex = uvIndex; }
    public void setPrecipitationProb(String precipitationProb) { this.precipitationProb = precipitationProb; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }
}
