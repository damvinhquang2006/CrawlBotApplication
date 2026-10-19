package com.bot.bridge;

import com.bot.model.CrawlResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PythonBridgeTest {

    private static PythonBridge pythonBridge;

    @BeforeAll
    static void setUp() {
        File scriptFile = new File("python" + File.separator + "crawler.py");
        pythonBridge = new PythonBridge("py", scriptFile.getPath(), 30, false, "", "353412");
    }

    @Test
    @DisplayName("PythonBridge should execute gasoline crawl on VnExpress successfully")
    void testVnExpressCrawl() {
        CrawlResponse response = pythonBridge.crawl(
                "https://vnexpress.net/chu-de/gia-xang-dau-3026",
                "vnexpress_gasoline",
                ""
        );

        assertNotNull(response);
        assertTrue(response.isSuccess(), "Crawl should succeed: " + response.getError());
        assertTrue(response.isChanged(), "First crawl should be marked as changed");
        assertEquals("VNEXPRESS_GASOLINE", response.getCategory());
        assertNotNull(response.getHash());

        JsonNode data = response.getData();
        assertNotNull(data, "Response data must not be null");
        assertTrue(data.has("items"), "Data should contain items array");
    }

    @Test
    @DisplayName("PythonBridge should block SSRF loopback attempt when allowLocalMock=false")
    void testSsrfBlockedThroughBridge() {
        CrawlResponse response = pythonBridge.crawl(
                "http://127.0.0.1:8080/secret",
                "gasoline",
                ""
        );

        assertNotNull(response);
        assertFalse(response.isSuccess(), "SSRF attack should be blocked");
        assertNotNull(response.getError());
        assertTrue(response.getError().contains("SSRF Protection"),
                "Error should mention SSRF Protection: " + response.getError());
    }
}
