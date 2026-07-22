package com.example.enrollment.identity;

import com.example.enrollment.config.EnrollmentConfig;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Normalizes MSISDN path parameters to E.164 for SPML subscriber identifiers.
 */
public final class MsisdnNormalizer {

    private static final Pattern DIGITS = Pattern.compile("[^0-9+]+");

    private final EnrollmentConfig config;

    public MsisdnNormalizer(EnrollmentConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * @return normalized E.164 (e.g. +14155552671) or null if invalid
     */
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = DIGITS.matcher(raw.trim()).replaceAll("");
        if (digits.startsWith("+")) {
            digits = "+" + digits.substring(1).replace("+", "");
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
        if (digits.startsWith("+")) {
            return digits;
        }
        return "+" + config.defaultCountryCode() + digits;
    }
}
