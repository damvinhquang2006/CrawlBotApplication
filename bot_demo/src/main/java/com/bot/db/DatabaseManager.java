package com.bot.db;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages Server SQLite Database.
 * Handles table migrations, schema creation, query retrieval,
 * and atomic ACID transactions when recording price updates and change events.
 */
public class DatabaseManager {

    private final String dbUrl;
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public DatabaseManager() {
        this("data/crawler_server.db");
    }

    public DatabaseManager(String dbFilePath) {
        // Ensure data directory exists
        File dbFile = new File(dbFilePath);
        File parentDir = dbFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        this.dbUrl = "jdbc:sqlite:" + dbFilePath;
        initSchema();
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    /**
     * Initializes database tables if they do not already exist.
     */
    private void initSchema() {
        String sqlGasolineLatest = """
            CREATE TABLE IF NOT EXISTS gasoline_latest (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                product TEXT UNIQUE NOT NULL,
                price TEXT,
                zone_1_price TEXT,
                zone_2_price TEXT,
                diff TEXT,
                unit TEXT DEFAULT 'VND/Lít',
                source_url TEXT,
                updated_at TEXT NOT NULL
            );
        """;

        String sqlGasolineHistory = """
            CREATE TABLE IF NOT EXISTS gasoline_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                product TEXT NOT NULL,
                price TEXT,
                zone_1_price TEXT,
                zone_2_price TEXT,
                diff TEXT,
                unit TEXT DEFAULT 'VND/Lít',
                source_url TEXT,
                recorded_at TEXT NOT NULL
            );
        """;

        String sqlChangeEvents = """
            CREATE TABLE IF NOT EXISTS change_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                product TEXT NOT NULL,
                old_price TEXT,
                new_price TEXT,
                diff TEXT,
                message TEXT,
                source TEXT,
                created_at TEXT NOT NULL
            );
        """;

        String sqlWeatherLatest = """
            CREATE TABLE IF NOT EXISTS weather_latest (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                location TEXT NOT NULL,
                temperature TEXT,
                condition TEXT,
                humidity TEXT,
                wind TEXT,
                uv_index TEXT,
                precipitation_prob TEXT,
                source_url TEXT,
                updated_at TEXT NOT NULL
            );
        """;

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sqlGasolineLatest);
            stmt.execute(sqlGasolineHistory);
            stmt.execute(sqlChangeEvents);
            stmt.execute(sqlWeatherLatest);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_gas_history_prod ON gasoline_history(product);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_change_events_date ON change_events(created_at);");
            // Clean up any historical incomplete or N/A records
            stmt.execute("DELETE FROM weather_latest WHERE temperature IS NULL OR temperature = 'N/A' OR condition = 'N/A';");
        } catch (SQLException e) {
            System.err.println("[DB ERROR] Failed to initialize database schema: " + e.getMessage());
        }
    }

    /**
     * ATOMIC TRANSACTION:
     * When new gasoline data is fetched by the crawler:
     * 1. Checks current prices in `gasoline_latest`.
     * 2. If price really changed (or is a new product):
     *    - Updates/Inserts `gasoline_latest`.
     *    - Inserts historical record in `gasoline_history`.
     *    - Inserts notification record in `change_events`.
     * All changes are committed atomically in ONE transaction.
     *
     * @return List of change messages generated during the transaction.
     */
    public List<String> processGasolineUpdateTransaction(JsonNode itemsNode, String sourceUrl) {
        List<String> eventMessages = new ArrayList<>();
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            return eventMessages;
        }

        String nowStr = LocalDateTime.now().format(ISO_FORMATTER);

        Connection conn = null;
        try {
            conn = getConnection();
            // 1. Begin atomic transaction
            conn.setAutoCommit(false);

            // Fetch existing latest prices for comparison
            Map<String, String> existingPrices = new HashMap<>();
            String selectSql = "SELECT product, price, zone_1_price FROM gasoline_latest";
            try (Statement selectStmt = conn.createStatement();
                 ResultSet rs = selectStmt.executeQuery(selectSql)) {
                while (rs.next()) {
                    String prod = rs.getString("product").trim().toLowerCase();
                    String price = rs.getString("price");
                    if (price == null || price.isBlank()) {
                        price = rs.getString("zone_1_price");
                    }
                    existingPrices.put(prod, price != null ? price.trim() : "");
                }
            }

            // Prepared statements for insertion/updates inside transaction
            String upsertLatestSql = """
                INSERT INTO gasoline_latest (product, price, zone_1_price, zone_2_price, diff, unit, source_url, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(product) DO UPDATE SET
                    price = excluded.price,
                    zone_1_price = excluded.zone_1_price,
                    zone_2_price = excluded.zone_2_price,
                    diff = excluded.diff,
                    source_url = excluded.source_url,
                    updated_at = excluded.updated_at;
            """;

            String insertHistorySql = """
                INSERT INTO gasoline_history (product, price, zone_1_price, zone_2_price, diff, unit, source_url, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?);
            """;

            String insertEventSql = """
                INSERT INTO change_events (event_type, product, old_price, new_price, diff, message, source, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?);
            """;

            try (PreparedStatement psLatest = conn.prepareStatement(upsertLatestSql);
                 PreparedStatement psHistory = conn.prepareStatement(insertHistorySql);
                 PreparedStatement psEvent = conn.prepareStatement(insertEventSql)) {

                for (JsonNode item : itemsNode) {
                    String product = item.path("product").asText("").trim();
                    if (product.isEmpty()) continue;

                    String price = item.path("price").isMissingNode() || item.path("price").isNull() ? null : item.path("price").asText().trim();
                    String z1 = item.path("zone_1_price").isMissingNode() || item.path("zone_1_price").isNull() ? null : item.path("zone_1_price").asText().trim();
                    String z2 = item.path("zone_2_price").isMissingNode() || item.path("zone_2_price").isNull() ? null : item.path("zone_2_price").asText().trim();
                    String diff = item.path("diff").isMissingNode() || item.path("diff").isNull() ? null : item.path("diff").asText().trim();

                    if (price == null && z1 != null) {
                        price = z1;
                    }
                    if (price == null) continue;

                    String prodKey = product.toLowerCase();
                    boolean hasOld = existingPrices.containsKey(prodKey);
                    String oldPrice = hasOld ? existingPrices.get(prodKey) : null;

                    // Check if price actually changed
                    boolean isPriceDifferent = !hasOld || (!price.equals(oldPrice));

                    if (isPriceDifferent) {
                        // 1. Update/Upsert gasoline_latest
                        psLatest.setString(1, product);
                        psLatest.setString(2, price);
                        psLatest.setString(3, z1 != null ? z1 : price);
                        psLatest.setString(4, z2);
                        psLatest.setString(5, diff);
                        psLatest.setString(6, "VND/Lít");
                        psLatest.setString(7, sourceUrl);
                        psLatest.setString(8, nowStr);
                        psLatest.executeUpdate();

                        // 2. Insert into gasoline_history
                        psHistory.setString(1, product);
                        psHistory.setString(2, price);
                        psHistory.setString(3, z1 != null ? z1 : price);
                        psHistory.setString(4, z2);
                        psHistory.setString(5, diff);
                        psHistory.setString(6, "VND/Lít");
                        psHistory.setString(7, sourceUrl);
                        psHistory.setString(8, nowStr);
                        psHistory.executeUpdate();

                        // 3. Format message and insert into change_events
                        String eventType = hasOld ? "PRICE_CHANGED" : "NEW_PRODUCT";
                        String msg;
                        if (hasOld) {
                            String diffInfo = (diff != null && !diff.isBlank()) ? " (" + diff + "đ)" : "";
                            msg = String.format("Biến động giá: %s thay đổi từ %s sang %s%s",
                                    product, oldPrice, price, diffInfo);
                        } else {
                            msg = String.format("Mặt hàng mới: %s niêm yết giá %s VND/Lít",
                                    product, price);
                        }

                        psEvent.setString(1, eventType);
                        psEvent.setString(2, product);
                        psEvent.setString(3, oldPrice != null ? oldPrice : "N/A");
                        psEvent.setString(4, price);
                        psEvent.setString(5, diff != null ? diff : "N/A");
                        psEvent.setString(6, msg);
                        psEvent.setString(7, sourceUrl);
                        psEvent.setString(8, nowStr);
                        psEvent.executeUpdate();

                        eventMessages.add(msg);
                    }
                }
            }

            // Commit atomic transaction
            conn.commit();

        } catch (Exception e) {
            System.err.println("[DB TRANSACTION ERROR] Rolling back transaction: " + e.getMessage());
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    System.err.println("[DB ROLLBACK FAILED] " + ex.getMessage());
                }
            }
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        }

        return eventMessages;
    }

    /**
     * Saves or updates latest weather data.
     */
    public void saveWeatherLatest(String location, String temp, String condition,
                                  String humidity, String wind, String uv,
                                  String precipProb, String sourceUrl) {
        String nowStr = LocalDateTime.now().format(ISO_FORMATTER);
        String sql = """
            INSERT INTO weather_latest (location, temperature, condition, humidity, wind, uv_index, precipitation_prob, source_url, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
        """;

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, location != null ? location : "Hà Nội");
            ps.setString(2, temp != null ? temp : "N/A");
            ps.setString(3, condition != null ? condition : "N/A");
            ps.setString(4, humidity != null ? humidity : "N/A");
            ps.setString(5, wind != null ? wind : "N/A");
            ps.setString(6, uv != null ? uv : "N/A");
            ps.setString(7, precipProb != null ? precipProb : "N/A");
            ps.setString(8, sourceUrl != null ? sourceUrl : "");
            ps.setString(9, nowStr);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB ERROR] Failed to save weather record: " + e.getMessage());
        }
    }

    // =========================================================================
    // QUERY METHODS FOR REST API
    // =========================================================================

    public List<Map<String, Object>> getLatestGasolinePrices() {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT * FROM gasoline_latest ORDER BY id ASC;";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", rs.getInt("id"));
                map.put("product", rs.getString("product"));
                map.put("price", rs.getString("price"));
                map.put("zone_1_price", rs.getString("zone_1_price"));
                map.put("zone_2_price", rs.getString("zone_2_price"));
                map.put("diff", rs.getString("diff"));
                map.put("unit", rs.getString("unit"));
                map.put("source_url", rs.getString("source_url"));
                map.put("updated_at", rs.getString("updated_at"));
                list.add(map);
            }
        } catch (SQLException e) {
            System.err.println("[DB ERROR] getLatestGasolinePrices: " + e.getMessage());
        }
        return list;
    }

    public List<Map<String, Object>> getGasolineHistory(String productFilter, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT * FROM gasoline_history " +
                (productFilter != null && !productFilter.isBlank() ? "WHERE product LIKE ? " : "") +
                "ORDER BY id DESC LIMIT ?;";

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            int pIndex = 1;
            if (productFilter != null && !productFilter.isBlank()) {
                ps.setString(pIndex++, "%" + productFilter.trim() + "%");
            }
            ps.setInt(pIndex, limit > 0 ? limit : 50);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", rs.getInt("id"));
                    map.put("product", rs.getString("product"));
                    map.put("price", rs.getString("price"));
                    map.put("zone_1_price", rs.getString("zone_1_price"));
                    map.put("zone_2_price", rs.getString("zone_2_price"));
                    map.put("diff", rs.getString("diff"));
                    map.put("unit", rs.getString("unit"));
                    map.put("source_url", rs.getString("source_url"));
                    map.put("recorded_at", rs.getString("recorded_at"));
                    list.add(map);
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB ERROR] getGasolineHistory: " + e.getMessage());
        }
        return list;
    }

    public List<Map<String, Object>> getChangeEvents(int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT * FROM change_events ORDER BY id DESC LIMIT ?;";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit > 0 ? limit : 50);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", rs.getInt("id"));
                    map.put("event_type", rs.getString("event_type"));
                    map.put("product", rs.getString("product"));
                    map.put("old_price", rs.getString("old_price"));
                    map.put("new_price", rs.getString("new_price"));
                    map.put("diff", rs.getString("diff"));
                    map.put("message", rs.getString("message"));
                    map.put("source", rs.getString("source"));
                    map.put("created_at", rs.getString("created_at"));
                    list.add(map);
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB ERROR] getChangeEvents: " + e.getMessage());
        }
        return list;
    }

    public Map<String, Object> getLatestWeather() {
        String sql = "SELECT * FROM weather_latest WHERE temperature IS NOT NULL AND temperature != 'N/A' ORDER BY id DESC LIMIT 1;";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", rs.getInt("id"));
                map.put("location", rs.getString("location"));
                map.put("temperature", rs.getString("temperature"));
                map.put("condition", rs.getString("condition"));
                map.put("humidity", rs.getString("humidity"));
                map.put("wind", rs.getString("wind"));
                map.put("uv_index", rs.getString("uv_index"));
                map.put("precipitation_prob", rs.getString("precipitation_prob"));
                map.put("source_url", rs.getString("source_url"));
                map.put("updated_at", rs.getString("updated_at"));
                return map;
            }
        } catch (SQLException e) {
            System.err.println("[DB ERROR] getLatestWeather: " + e.getMessage());
        }
        return null;
    }
}
