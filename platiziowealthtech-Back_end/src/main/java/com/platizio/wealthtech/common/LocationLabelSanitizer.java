package com.platizio.wealthtech.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Cleans location labels returned by external pincode/IFSC APIs. Sandbox data may include
 * null-byte markers and junk suffixes (e.g. {@code Mumbai%00$##$$@#$...}).
 */
public final class LocationLabelSanitizer {

    private static final Pattern LEADING_LABEL = Pattern.compile("^[\\p{L}][\\p{L}\\p{M}\\s.'\\-]*");

    private LocationLabelSanitizer() {
    }

    public static String sanitize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        int marker = indexOfCorruptionMarker(trimmed);
        if (marker > 0) {
            trimmed = trimmed.substring(0, marker).trim();
        }
        trimmed = trimmed.replace('\0', ' ').trim();
        Matcher matcher = LEADING_LABEL.matcher(trimmed);
        if (!matcher.find()) {
            return null;
        }
        String cleaned = matcher.group().trim().replaceAll("\\s+", " ");
        return StringUtils.hasText(cleaned) ? cleaned : null;
    }

    public static String normalizeStateLabel(String raw) {
        String cleaned = sanitize(raw);
        if (!StringUtils.hasText(cleaned)) {
            return null;
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public static String resolveCityLabel(String city, String district) {
        String cleanCity = sanitize(city);
        if (StringUtils.hasText(cleanCity)) {
            return cleanCity;
        }
        return sanitize(district);
    }

    public static List<String> sanitizeCityOptions(String city, String district, List<String> cities) {
        Set<String> options = new LinkedHashSet<>();
        if (cities != null) {
            for (String option : cities) {
                String cleaned = sanitize(option);
                if (StringUtils.hasText(cleaned)) {
                    options.add(cleaned);
                }
            }
        }
        String resolvedCity = resolveCityLabel(city, district);
        if (StringUtils.hasText(resolvedCity)) {
            options.add(resolvedCity);
        }
        return List.copyOf(options);
    }

    private static int indexOfCorruptionMarker(String value) {
        int percent = value.indexOf('%');
        int nul = value.indexOf('\0');
        if (percent < 0) {
            return nul;
        }
        if (nul < 0) {
            return percent;
        }
        return Math.min(percent, nul);
    }
}
