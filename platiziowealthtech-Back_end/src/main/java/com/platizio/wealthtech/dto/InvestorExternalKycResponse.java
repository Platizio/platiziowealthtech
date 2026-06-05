package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.KycStatus;
import java.util.Locale;

public record InvestorExternalKycResponse(
        Investor investor,
        JsonNode externalResponse,
        KycDecision kyc
) {
    public InvestorExternalKycResponse(Investor investor, JsonNode externalResponse) {
        this(investor, externalResponse, KycDecision.from(investor));
    }

    /**
     * Frontend-facing summary of the KYC decision, derived from the FP KYC Check
     * (`/api/kyc/check`) compliance result when present, otherwise the POA
     * pre-verification readiness result. Lets the UI render the correct
     * customer indicator and skip re-KYC when the investor is already compliant
     * (e.g. KYC done earlier with another distributor/AMC/KRA) without
     * re-interpreting raw provider JSON.
     */
    public record KycDecision(
            KycStatus status,
            KycState state,
            String readinessStatus,
            String readinessCode,
            String readinessReason,
            String complianceReason,
            String complianceAction,
            String constraints,
            boolean alreadyKycCompliant,
            boolean freshKycRequired,
            boolean reKycSkipped,
            String message
    ) {
        public static KycDecision from(Investor investor) {
            if (investor == null) {
                return new KycDecision(null, KycState.UNKNOWN, null, null, null, null, null, null,
                        false, false, false, null);
            }

            KycStatus status = investor.getKycStatus();
            String readinessStatus = investor.getKycReadinessStatus();
            String readinessCode = investor.getKycReadinessCode();
            String readinessReason = investor.getKycReadinessReason();
            String complianceReason = investor.getKycComplianceReason();
            String complianceAction = investor.getKycComplianceAction();
            Boolean complianceStatus = investor.getKycComplianceStatus();
            String constraints = investor.getKycConstraintsJson();
            boolean hasConstraints = constraints != null && !constraints.isBlank() && !"[]".equals(constraints.trim());

            KycState state = resolveState(
                    complianceStatus, complianceReason, complianceAction, hasConstraints,
                    readinessStatus, readinessCode, status
            );

            boolean alreadyKycCompliant = state == KycState.VERIFIED || state == KycState.VERIFIED_WITH_CONSTRAINTS;
            boolean freshKycRequired = state == KycState.FRESH_KYC_REQUIRED;
            boolean reKycSkipped = alreadyKycCompliant;

            return new KycDecision(
                    status,
                    state,
                    readinessStatus,
                    readinessCode,
                    readinessReason,
                    complianceReason,
                    complianceAction,
                    hasConstraints ? constraints : null,
                    alreadyKycCompliant,
                    freshKycRequired,
                    reKycSkipped,
                    state.message()
            );
        }

        private static KycState resolveState(
                Boolean complianceStatus,
                String complianceReason,
                String complianceAction,
                boolean hasConstraints,
                String readinessStatus,
                String readinessCode,
                KycStatus status
        ) {
            // Prefer the richer FP KYC Check compliance result when available.
            if (complianceStatus != null || complianceReason != null || complianceAction != null) {
                if (Boolean.TRUE.equals(complianceStatus)) {
                    return hasConstraints ? KycState.VERIFIED_WITH_CONSTRAINTS : KycState.VERIFIED;
                }
                String action = lower(complianceAction);
                String reason = lower(complianceReason);
                if ("create".equals(action) || "unavailable".equals(reason) || "rejected".equals(reason)) {
                    return KycState.FRESH_KYC_REQUIRED;
                }
                if ("disallowed".equals(action) || "deactivated".equals(reason)) {
                    return KycState.BLOCKED;
                }
                if ("none".equals(action) || "underprocess".equals(reason)) {
                    return KycState.UNDER_PROCESS;
                }
                if ("modify".equals(action)
                        || "incomplete".equals(reason)
                        || "legacy".equals(reason)
                        || "onhold".equals(reason)) {
                    return KycState.NEEDS_MODIFICATION;
                }
                return KycState.UNKNOWN;
            }

            // Fall back to the POA pre-verification readiness signal.
            if ("verified".equalsIgnoreCase(readinessStatus)) {
                return KycState.VERIFIED;
            }
            if ("failed".equalsIgnoreCase(readinessStatus)) {
                String code = lower(readinessCode);
                if ("kyc_unavailable".equals(code) || "unavailable".equals(code)) {
                    return KycState.FRESH_KYC_REQUIRED;
                }
                if ("kyc_incomplete".equals(code) || "incomplete".equals(code)) {
                    return KycState.NEEDS_MODIFICATION;
                }
                return KycState.UNKNOWN;
            }

            if (status == KycStatus.COMPLETED) {
                return KycState.VERIFIED;
            }
            if (status == KycStatus.IN_PROGRESS || status == KycStatus.PENDING) {
                return KycState.IN_PROGRESS;
            }
            if (status == KycStatus.NOT_STARTED) {
                return KycState.FRESH_KYC_REQUIRED;
            }
            return KycState.UNKNOWN;
        }

        private static String lower(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Customer-facing KYC indicator states aligned with the Cybrilla/FP KYC
     * Check taxonomy (status + reason + action) and the SEBI KYC-validation
     * rules effective April 2024.
     */
    public enum KycState {
        VERIFIED("Investor is already KYC compliant. You can proceed to invest."),
        VERIFIED_WITH_CONSTRAINTS("Investor is KYC compliant but has investment limits. You can invest within the stated limit."),
        NEEDS_MODIFICATION("KYC exists but is not validated. A one-time KYC modification (Aadhaar) is required before new investments."),
        UNDER_PROCESS("KYC is under process at the KRA. No action needed \u2014 we'll update you once it's verified."),
        FRESH_KYC_REQUIRED("No usable KYC found for this PAN. A fresh KYC application is required."),
        BLOCKED("This PAN cannot be used to invest and KYC cannot be performed."),
        IN_PROGRESS("KYC is in progress."),
        UNKNOWN("KYC status could not be determined. Please retry.");

        private final String message;

        KycState(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }
}
