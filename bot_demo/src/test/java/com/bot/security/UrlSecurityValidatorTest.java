package com.bot.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UrlSecurityValidatorTest {

    @Test
    @DisplayName("Whitelisted public URLs should be allowed")
    void testAllowedPublicUrls() {
        UrlSecurityValidator.ValidationResult r1 = UrlSecurityValidator.validate(
                "https://vnexpress.net/chu-de/gia-xang-dau-3026", false, null);
        assertTrue(r1.isValid(), "VnExpress should be allowed: " + r1.getMessage());

        UrlSecurityValidator.ValidationResult r2 = UrlSecurityValidator.validate(
                "https://thoitiet.vn/ha-noi", false, null);
        assertTrue(r2.isValid(), "Thoitiet.vn should be allowed: " + r2.getMessage());

        UrlSecurityValidator.ValidationResult r3 = UrlSecurityValidator.validate(
                "https://webgia.com/gia-xang-dau/petrolimex/", false, null);
        assertTrue(r3.isValid(), "WebGia should be allowed: " + r3.getMessage());
    }

    @Test
    @DisplayName("Forbidden schemes like file:// and gopher:// should be blocked")
    void testForbiddenSchemes() {
        UrlSecurityValidator.ValidationResult r1 = UrlSecurityValidator.validate(
                "file:///etc/passwd", false, null);
        assertFalse(r1.isValid(), "file:// scheme must be blocked");

        UrlSecurityValidator.ValidationResult r2 = UrlSecurityValidator.validate(
                "ftp://ftp.example.com", false, null);
        assertFalse(r2.isValid(), "ftp:// scheme must be blocked");
    }

    @Test
    @DisplayName("Localhost/127.0.0.1 should be blocked when allowLocalMock=false (SSRF defense)")
    void testLocalhostBlockedByDefault() {
        UrlSecurityValidator.ValidationResult r1 = UrlSecurityValidator.validate(
                "http://localhost:8080/gas.html", false, null);
        assertFalse(r1.isValid(), "Localhost must be blocked by default");

        UrlSecurityValidator.ValidationResult r2 = UrlSecurityValidator.validate(
                "http://127.0.0.1:8080/weather.html", false, null);
        assertFalse(r2.isValid(), "127.0.0.1 must be blocked by default");
    }

    @Test
    @DisplayName("Localhost should be permitted when allowLocalMock=true")
    void testLocalhostAllowedWhenExplicitlyConfigured() {
        UrlSecurityValidator.ValidationResult r1 = UrlSecurityValidator.validate(
                "http://localhost:8080/gas.html", true, null);
        assertTrue(r1.isValid(), "Localhost should be permitted when allowLocalMock=true");

        UrlSecurityValidator.ValidationResult r2 = UrlSecurityValidator.validate(
                "http://127.0.0.1:8080/weather.html", true, null);
        assertTrue(r2.isValid(), "127.0.0.1 should be permitted when allowLocalMock=true");
    }

    @Test
    @DisplayName("Private and link-local IP addresses should be blocked (SSRF defense)")
    void testPrivateIpsBlocked() {
        // 10.x.x.x
        UrlSecurityValidator.ValidationResult r1 = UrlSecurityValidator.validate(
                "http://10.0.0.1/admin", true, List.of("10.0.0.1"));
        assertFalse(r1.isValid(), "10.0.0.1 must be blocked");

        // 192.168.x.x
        UrlSecurityValidator.ValidationResult r2 = UrlSecurityValidator.validate(
                "http://192.168.1.1/router", true, List.of("192.168.1.1"));
        assertFalse(r2.isValid(), "192.168.1.1 must be blocked");

        // AWS/Cloud metadata service IP (169.254.169.254)
        UrlSecurityValidator.ValidationResult r3 = UrlSecurityValidator.validate(
                "http://169.254.169.254/latest/meta-data/", true, List.of("169.254.169.254"));
        assertFalse(r3.isValid(), "169.254.169.254 must be blocked");
    }
}
