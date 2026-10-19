package com.bot.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Validates URLs against Server-Side Request Forgery (SSRF) and protocol manipulation.
 * Ensures that only approved protocols and safe public endpoints are crawled.
 */
public class UrlSecurityValidator {

    private static final List<String> DEFAULT_ALLOWED_DOMAINS = Arrays.asList(
            "thoitiet.vn",
            "webgia.com",
            "vnexpress.net",
            "accuweather.com",
            "dataservice.accuweather.com"
    );

    /**
     * Validates a target URL.
     *
     * @param urlString       The URL to validate
     * @param allowLocalMock  Whether local loopback addresses (127.0.0.1 / localhost) are permitted (for test server)
     * @param allowedDomains  Optional list of allowed domains. If null or empty, uses DEFAULT_ALLOWED_DOMAINS
     * @return ValidationResult indicating if URL is safe, with description
     */
    public static ValidationResult validate(String urlString, boolean allowLocalMock, List<String> allowedDomains) {
        if (urlString == null || urlString.isBlank()) {
            return ValidationResult.denied("URL cannot be null or empty");
        }

        try {
            URI uri = URI.create(urlString.trim());
            URL url = uri.toURL();

            // 1. Scheme check: only HTTP and HTTPS allowed
            String scheme = url.getProtocol();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return ValidationResult.denied("Protocol '" + scheme + "' is forbidden. Only HTTP/HTTPS are allowed.");
            }

            String host = url.getHost();
            if (host == null || host.isBlank()) {
                return ValidationResult.denied("URL does not contain a valid host: " + urlString);
            }
            host = host.toLowerCase(Locale.ROOT);

            // 2. Local mock override check
            boolean isLocalHostName = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
            if (isLocalHostName) {
                if (allowLocalMock) {
                    return ValidationResult.allowed("Allowed: Local mock server enabled");
                } else {
                    return ValidationResult.denied("Localhost/127.0.0.1 access blocked (SSRF Protection). Enable 'bot.allow.local.mock=true' for local testing.");
                }
            }

            // 3. Domain Whitelist Verification
            List<String> activeAllowedDomains = (allowedDomains != null && !allowedDomains.isEmpty())
                    ? allowedDomains : DEFAULT_ALLOWED_DOMAINS;

            boolean domainWhitelisted = false;
            for (String allowed : activeAllowedDomains) {
                String cleanAllowed = allowed.trim().toLowerCase(Locale.ROOT);
                if (host.equals(cleanAllowed) || host.endsWith("." + cleanAllowed)) {
                    domainWhitelisted = true;
                    break;
                }
            }

            if (!domainWhitelisted && !allowLocalMock) {
                return ValidationResult.denied("Domain '" + host + "' is not in the allowed domain whitelist: " + activeAllowedDomains);
            }

            // 4. DNS Resolution & IP Address Security Inspection
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (isDangerousAddress(addr, allowLocalMock)) {
                    return ValidationResult.denied("Host '" + host + "' resolves to dangerous/internal IP: " + addr.getHostAddress() + " (SSRF Protection)");
                }
            }

            return ValidationResult.allowed("Safe URL verified");

        } catch (Exception e) {
            return ValidationResult.denied("Invalid URL or DNS resolution failed: " + e.getMessage());
        }
    }

    /**
     * Checks if an IP address falls into private, loopback, link-local or reserved ranges.
     */
    public static boolean isDangerousAddress(InetAddress addr, boolean allowLocalMock) {
        if (addr.isLoopbackAddress()) {
            return !allowLocalMock;
        }

        if (addr.isAnyLocalAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }

        byte[] raw = addr.getAddress();

        // IPv4 checks
        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;

            // 10.0.0.0/8
            if (b0 == 10) return true;

            // 172.16.0.0/12 (172.16.x.x - 172.31.x.x)
            if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;

            // 192.168.0.0/16
            if (b0 == 192 && b1 == 168) return true;

            // 169.254.0.0/16 (Link Local)
            if (b0 == 169 && b1 == 254) return true;

            // 0.0.0.0/8 (Current network)
            if (b0 == 0) return true;

            // 127.0.0.0/8
            if (b0 == 127) return !allowLocalMock;

            // 100.64.0.0/10 (Carrier-grade NAT)
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) return true;
        }

        // IPv6 Unique Local Addresses (fc00::/7)
        if (raw.length == 16) {
            int firstByte = raw[0] & 0xFF;
            if ((firstByte & 0xFE) == 0xFC) {
                return true;
            }
        }

        return false;
    }

    public static class ValidationResult {
        private final boolean valid;
        private final String message;

        public ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public static ValidationResult allowed(String message) {
            return new ValidationResult(true, message);
        }

        public static ValidationResult denied(String message) {
            return new ValidationResult(false, message);
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }
    }
}
