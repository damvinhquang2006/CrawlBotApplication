package com.bot.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DatabaseManagerTest {

    private String testDbPath;
    private DatabaseManager dbManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        testDbPath = "data/test_crawler_" + System.currentTimeMillis() + ".db";
        dbManager = new DatabaseManager(testDbPath);
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        File file = new File(testDbPath);
        if (file.exists()) {
            file.delete();
        }
    }

    @Test
    @DisplayName("Transaction should atomically record price history and change events only when price differs")
    void testGasolineAtomicTransaction() throws Exception {
        // 1. First run: Initial gasoline prices
        String initialJson = """
            [
                {
                    "product": "Xăng E5 RON 92",
                    "price": "26.970",
                    "zone_1_price": "26.970",
                    "zone_2_price": null,
                    "diff": "-730"
                },
                {
                    "product": "Xăng RON 95-III",
                    "price": "27.020",
                    "zone_1_price": "27.020",
                    "zone_2_price": null,
                    "diff": "-1.230"
                }
            ]
        """;
        JsonNode node1 = objectMapper.readTree(initialJson);
        List<String> events1 = dbManager.processGasolineUpdateTransaction(node1, "https://test.url");

        assertEquals(2, events1.size(), "Two new products should trigger 2 events");
        assertEquals(2, dbManager.getLatestGasolinePrices().size(), "gasoline_latest should have 2 products");
        assertEquals(2, dbManager.getGasolineHistory(null, 10).size(), "gasoline_history should have 2 initial rows");
        assertEquals(2, dbManager.getChangeEvents(10).size(), "change_events should have 2 rows");

        // 2. Second run: SAME prices crawled -> NO changes, NO new history/events recorded
        List<String> events2 = dbManager.processGasolineUpdateTransaction(node1, "https://test.url");
        assertTrue(events2.isEmpty(), "No events when prices are unchanged");
        assertEquals(2, dbManager.getGasolineHistory(null, 10).size(), "History count remains 2");
        assertEquals(2, dbManager.getChangeEvents(10).size(), "Events count remains 2");

        // 3. Third run: Price of 'Xăng E5 RON 92' changes from 26.970 to 27.700 (+730)
        String updateJson = """
            [
                {
                    "product": "Xăng E5 RON 92",
                    "price": "27.700",
                    "zone_1_price": "27.700",
                    "zone_2_price": null,
                    "diff": "+730"
                },
                {
                    "product": "Xăng RON 95-III",
                    "price": "27.020",
                    "zone_1_price": "27.020",
                    "zone_2_price": null,
                    "diff": "0"
                }
            ]
        """;
        JsonNode node3 = objectMapper.readTree(updateJson);
        List<String> events3 = dbManager.processGasolineUpdateTransaction(node3, "https://test.url");

        assertEquals(1, events3.size(), "Only the changed product should generate an event");
        assertTrue(events3.get(0).contains("26.970") && events3.get(0).contains("27.700"),
                "Message should detail the old and new prices");

        // Verify latest table is updated
        List<Map<String, Object>> latest = dbManager.getLatestGasolinePrices();
        assertEquals(2, latest.size());
        Map<String, Object> e5Latest = latest.stream()
                .filter(m -> "Xăng E5 RON 92".equals(m.get("product")))
                .findFirst().orElseThrow();
        assertEquals("27.700", e5Latest.get("price"));
        assertEquals("+730", e5Latest.get("diff"));

        // Verify history has exactly 3 records now (2 initial + 1 update)
        List<Map<String, Object>> history = dbManager.getGasolineHistory(null, 10);
        assertEquals(3, history.size(), "History should now have 3 records");

        // Verify change_events has exactly 3 records now (2 NEW_PRODUCT + 1 PRICE_CHANGED)
        List<Map<String, Object>> eventsList = dbManager.getChangeEvents(10);
        assertEquals(3, eventsList.size(), "change_events should now have 3 records");
        Map<String, Object> latestEvent = eventsList.get(0); // Ordered DESC
        assertEquals("PRICE_CHANGED", latestEvent.get("event_type"));
        assertEquals("Xăng E5 RON 92", latestEvent.get("product"));
        assertEquals("26.970", latestEvent.get("old_price"));
        assertEquals("27.700", latestEvent.get("new_price"));
        assertEquals("+730", latestEvent.get("diff"));
    }
}
