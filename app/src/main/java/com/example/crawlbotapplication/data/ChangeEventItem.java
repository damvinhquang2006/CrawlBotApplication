package com.example.crawlbotapplication.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "client_change_events")
public class ChangeEventItem {

    @PrimaryKey
    private int eventId;

    private String eventType;
    private String product;
    private String oldPrice;
    private String newPrice;
    private String diff;
    private String message;
    private String createdAt;
    private boolean isNotified;

    public ChangeEventItem(int eventId, String eventType, String product, String oldPrice,
                           String newPrice, String diff, String message, String createdAt, boolean isNotified) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.product = product;
        this.oldPrice = oldPrice;
        this.newPrice = newPrice;
        this.diff = diff;
        this.message = message;
        this.createdAt = createdAt;
        this.isNotified = isNotified;
    }

    public int getEventId() {
        return eventId;
    }

    public void setEventId(int eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public String getOldPrice() {
        return oldPrice;
    }

    public void setOldPrice(String oldPrice) {
        this.oldPrice = oldPrice;
    }

    public String getNewPrice() {
        return newPrice;
    }

    public void setNewPrice(String newPrice) {
        this.newPrice = newPrice;
    }

    public String getDiff() {
        return diff;
    }

    public void setDiff(String diff) {
        this.diff = diff;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isNotified() {
        return isNotified;
    }

    public void setNotified(boolean notified) {
        isNotified = notified;
    }
}
