package com.platizio.wealthtech.domain;

/**
 * What to do next based on a POA pre-verification <em>readiness</em> result
 * ({@code readiness.status} + {@code readiness.code}). Per Cybrilla's expanded readiness
 * codes (2026), routing is driven by the nested {@code code}, NOT the free-text reason.
 *
 * <p>SUBMIT_NEW_KYC and MODIFY_KYC are both completed by the INVESTOR through the
 * investor-side DigiLocker/advance chain (POST /api/v1/investor/kyc/advance:
 * pre-verify -> create-request -> Aadhaar/DigiLocker -> eSign), NOT through the
 * kyc_forms API — kyc_forms returns 403 "Partner not allowed" in the sandbox, so it
 * is deliberately not used.
 *
 * <pre>
 *  verified                                   -> PROCEED         (investor may invest)
 *  failed + kyc_unavailable | kyc_rejected    -> SUBMIT_NEW_KYC  (investor DigiLocker/advance chain: fresh KYC)
 *  failed + kyc_incomplete | kyc_onhold |
 *           kyc_legacy                        -> MODIFY_KYC      (investor DigiLocker/advance chain: update KYC)
 *  failed + kyc_underprocess                  -> WAIT           (KRA still processing; no action)
 *  failed + kyc_deactivated                   -> BLOCKED        (permanent; no investments)
 *  failed + upstream_error                    -> RETRY          (transient; retry later)
 *  failed + unknown | (anything else)         -> MANUAL_REVIEW  (non-compliant, undetermined)
 * </pre>
 */
public enum KycReadinessAction {
    PROCEED,
    SUBMIT_NEW_KYC,
    MODIFY_KYC,
    WAIT,
    BLOCKED,
    RETRY,
    MANUAL_REVIEW;

    /** Maps a pre-verification readiness {@code status}/{@code code} to the next action. */
    public static KycReadinessAction forReadiness(String status, String code) {
        if ("verified".equalsIgnoreCase(status)) {
            return PROCEED;
        }
        if (code == null) {
            return MANUAL_REVIEW;
        }
        return switch (code.toLowerCase()) {
            case "kyc_unavailable", "kyc_rejected" -> SUBMIT_NEW_KYC;
            case "kyc_incomplete", "kyc_onhold", "kyc_legacy" -> MODIFY_KYC;
            case "kyc_underprocess" -> WAIT;
            case "kyc_deactivated" -> BLOCKED;
            case "upstream_error" -> RETRY;
            default -> MANUAL_REVIEW; // unknown + any future code
        };
    }

    /** Whether the investor can proceed to transactions right now. */
    public boolean canInvest() {
        return this == PROCEED;
    }

    /** Whether KYC can still be completed (vs. blocked/wait/manual). */
    public boolean isActionable() {
        return this == SUBMIT_NEW_KYC || this == MODIFY_KYC;
    }

    /** Investor-facing guidance for this outcome. */
    public String message() {
        return switch (this) {
            case PROCEED -> "You are KYC-verified and ready to invest.";
            case SUBMIT_NEW_KYC -> "No usable KYC record was found. Complete a fresh KYC with DigiLocker to continue.";
            case MODIFY_KYC -> "Your KYC needs updating to meet current norms. Update it with DigiLocker to continue.";
            case WAIT -> "Your KYC is being processed at the KRA. No action is needed — please check back shortly.";
            case BLOCKED -> "Your KYC has been deactivated at the KRA, so investments cannot be accepted. Please contact your KRA to resolve this.";
            case RETRY -> "We could not reach the KRA just now. Please try again in a few minutes.";
            case MANUAL_REVIEW -> "We could not determine your KYC status automatically. Please retry, or your distributor can help resolve this.";
        };
    }
}
