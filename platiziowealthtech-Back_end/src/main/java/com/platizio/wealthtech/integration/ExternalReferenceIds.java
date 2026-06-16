package com.platizio.wealthtech.integration;

import org.springframework.util.StringUtils;

/** Detects local/demo placeholder IDs vs real Fintech Primitives resource IDs. */
public final class ExternalReferenceIds {

    private ExternalReferenceIds() {
    }

    public static boolean isFpInvestorProfileId(String id) {
        return looksLikeFpResourceId(id, "invp_");
    }

    public static boolean isFpMfInvestmentAccountId(String id) {
        return looksLikeFpResourceId(id, "mfia_");
    }

    public static boolean isFpBankAccountId(String id) {
        return looksLikeFpResourceId(id, "bac_");
    }

    private static boolean looksLikeFpResourceId(String id, String prefix) {
        if (!StringUtils.hasText(id)) {
            return false;
        }
        String normalized = id.trim().toLowerCase();
        if (!normalized.startsWith(prefix)) {
            return false;
        }
        if (normalized.contains("demo") || normalized.startsWith("cyb-inv")) {
            return false;
        }
        return normalized.length() >= prefix.length() + 8;
    }
}
