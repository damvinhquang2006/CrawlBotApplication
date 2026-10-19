package com.example.crawlbotapplication.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "gasoline_items")
public class GasolineItem {

    @PrimaryKey(autoGenerate = true)
    private int id;

    private String product;
    private String price;
    private String zone1Price;
    private String zone2Price;
    private String diff;
    private String unit;
    private String updatedAt;

    public GasolineItem(String product, String price, String zone1Price, String zone2Price,
                        String diff, String unit, String updatedAt) {
        this.product = product;
        this.price = price;
        this.zone1Price = zone1Price;
        this.zone2Price = zone2Price;
        this.diff = diff;
        this.unit = unit != null ? unit : "VND/Lít";
        this.updatedAt = updatedAt;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public String getPrice() {
        return price;
    }

    public void setPrice(String price) {
        this.price = price;
    }

    public String getZone1Price() {
        return zone1Price;
    }

    public void setZone1Price(String zone1Price) {
        this.zone1Price = zone1Price;
    }

    public String getZone2Price() {
        return zone2Price;
    }

    public void setZone2Price(String zone2Price) {
        this.zone2Price = zone2Price;
    }

    public String getDiff() {
        return diff;
    }

    public void setDiff(String diff) {
        this.diff = diff;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
