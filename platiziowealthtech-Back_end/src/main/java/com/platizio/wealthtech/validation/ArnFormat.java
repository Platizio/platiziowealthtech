package com.platizio.wealthtech.validation;

/**
 * Centralised ARN (AMFI Registration Number) format rules, mirroring {@link PanFormat}.
 * ARNs are issued as {@code ARN-<digits>} (e.g. {@code ARN-123456}). Matches the frontend's
 * client-side rule so validation is consistent across tiers.
 */
public final class ArnFormat {

    public static final String ARN_REGEX = "^ARN-\\d{1,9}$";
    public static final String ARN_MESSAGE = "ARN must be in the format ARN-XXXXX (for example ARN-123456).";

    private ArnFormat() {
    }

    /** Uppercases and trims; leaves null as null. */
    public static String normalize(String arn) {
        if (arn == null) {
            return null;
        }
        return arn.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public static boolean isValid(String arn) {
        String normalized = normalize(arn);
        return normalized != null && normalized.matches(ARN_REGEX);
    }
}
