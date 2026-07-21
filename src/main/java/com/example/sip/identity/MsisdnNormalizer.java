package com.example.sip.identity;

import com.example.sip.config.GatewayConfig;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts and normalizes MSISDNs / display names from SIP identity headers.
 */
public final class MsisdnNormalizer {

    private static final Pattern URI_USER = Pattern.compile(
            "(?i)(?:sip:|tel:|sips:)?\\s*(\\+?[0-9][0-9\\-().\\s]*)");
    private static final Pattern ANGLE_URI = Pattern.compile("<([^>]+)>");
    private static final Pattern DISPLAY_NAME = Pattern.compile("^\\s*\"([^\"]+)\"\\s*<");
    private static final Pattern DOMAIN = Pattern.compile("(?i)(?:sip:|sips:)[^@]+@([^;>\\s]+)");

    private final GatewayConfig config;

    public MsisdnNormalizer(GatewayConfig config) {
        this.config = config;
    }

    public Optional<String> extractUri(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return Optional.empty();
        }
        Matcher angle = ANGLE_URI.matcher(headerValue);
        if (angle.find()) {
            return Optional.of(angle.group(1).trim());
        }
        return Optional.of(headerValue.trim());
    }

    public Optional<String> extractDomain(String headerValue) {
        return extractUri(headerValue).flatMap(uri -> {
            Matcher m = DOMAIN.matcher(uri.startsWith("sip:") || uri.startsWith("sips:") ? uri : "sip:" + uri);
            if (m.find()) {
                return Optional.of(m.group(1).toLowerCase());
            }
            // bare host in angle URI without scheme
            int at = uri.indexOf('@');
            if (at > 0 && at < uri.length() - 1) {
                String host = uri.substring(at + 1);
                int semi = host.indexOf(';');
                return Optional.of((semi >= 0 ? host.substring(0, semi) : host).toLowerCase());
            }
            return Optional.empty();
        });
    }

    public Optional<String> extractDisplayName(String fromHeader) {
        if (fromHeader == null) {
            return Optional.empty();
        }
        Matcher m = DISPLAY_NAME.matcher(fromHeader);
        if (m.find()) {
            return Optional.of(m.group(1).trim());
        }
        return Optional.empty();
    }

    public Optional<String> extractMsisdn(String headerValue) {
        return extractUri(headerValue).flatMap(uri -> {
            Matcher m = URI_USER.matcher(uri);
            if (!m.find()) {
                return Optional.empty();
            }
            return Optional.ofNullable(normalize(m.group(1)));
        });
    }

    public String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+")) {
            return "+" + digits.substring(1).replace("+", "");
        }
        if (config.stripTrunkPrefix()
                && !config.trunkPrefix().isEmpty()
                && digits.startsWith(config.trunkPrefix())
                && digits.length() > config.trunkPrefix().length()) {
            digits = digits.substring(config.trunkPrefix().length());
        }
        if (digits.isEmpty()) {
            return null;
        }
        return "+" + config.defaultCountryCode() + digits;
    }

    public String resolveCallerDisplay(String fromHeader) {
        Optional<String> msisdn = extractMsisdn(fromHeader);
        if (msisdn.isPresent()) {
            return msisdn.get();
        }
        String lowered = fromHeader == null ? "" : fromHeader.toLowerCase();
        if (lowered.contains("anonymous@anonymous.invalid") || lowered.contains("\"anonymous\"")) {
            return "Anonymous";
        }
        if (lowered.contains("privacy") || lowered.contains("unavailable")) {
            return "Private Number";
        }
        return extractDisplayName(fromHeader)
                .orElseGet(() -> fromHeader == null ? "Unknown" : fromHeader.trim());
    }
}
