package com.platizio.wealthtech.validation;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Indian PAN structure and Cybrilla sandbox simulator patterns.
 *
 * @see <a href="https://poa.cybrilla.com/docs/additional-apis/pre-verifications">POA pre-verifications</a>
 * @see <a href="https://docs.fintechprimitives.com/fp-cybrillapoa-gateway/sandbox-simulation">FP gateway sandbox</a>
 */
public final class PanFormat {

    public static final String INDIAN_PAN_REGEX = "^[A-Z]{5}[0-9]{4}[A-Z]$";

    /**
     * POA sandbox simulator PAN: {@code XXXPXNNNNX} — 4th character must be {@code P}.
     * Official examples include {@code GYAPS3751D} and {@code AAAPA3751A}.
     */
    public static final String POA_SANDBOX_PAN_REGEX = "^[A-Z]{3}P[A-Z][0-9]{4}[A-Z]$";

    /** FP gateway sandbox KYC-ready / KYC-unavailable readiness patterns. */
    public static final String GATEWAY_KYC_READY_PAN_REGEX = "^[A-Z]{3}P[A-Z]3751[A-Z]$";

    public static final String GATEWAY_KYC_UNAVAILABLE_PAN_REGEX = "^[A-Z]{3}P[A-Z]3753[A-Z]$";

    public static final Pattern INDIAN_PAN = Pattern.compile(INDIAN_PAN_REGEX);

    public static final Pattern POA_SANDBOX_PAN = Pattern.compile(POA_SANDBOX_PAN_REGEX);

    public static final Pattern GATEWAY_KYC_READY_PAN = Pattern.compile(GATEWAY_KYC_READY_PAN_REGEX);

    public static final Pattern GATEWAY_KYC_UNAVAILABLE_PAN = Pattern.compile(GATEWAY_KYC_UNAVAILABLE_PAN_REGEX);

    public static final String INDIAN_PAN_MESSAGE = "Invalid PAN format. Expected format: AAAAA9999A";

    public static final String POA_SANDBOX_PAN_MESSAGE =
            "Sandbox mode is active: Cybrilla only simulates PAN checks and does not verify real PANs against ITD. "
                    + "For sandbox testing, use a simulator PAN with P as the 4th character (pattern XXXPXNNNNX), "
                    + "e.g. GYAPS3751D or AAAPA3751A. Use XXXPINNNNX for invalid PAN or XXXPANNNNX for Aadhaar-not-linked. "
                    + "For live PAN verification of any real investor PAN, switch to production credentials from Cybrilla "
                    + "(CYBRILLA_PRE_VERIFICATION_BASE_URL=https://api.cybrilla.com, "
                    + "FINPRIM_BASE_URL=https://api.fintechprimitives.com, production client IDs without _test_).";

    public static final String GATEWAY_KYC_READINESS_PAN_MESSAGE =
            "Sandbox KYC readiness checks require XXXPX3751X (KYC-compliant) or XXXPX3753X (fresh KYC). "
                    + "Example: GYAPS3751D.";

    private PanFormat() {
    }

    public static String normalize(String rawPan) {
        if (!StringUtils.hasText(rawPan)) {
            return null;
        }
        return rawPan.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isIndianPan(String pan) {
        return StringUtils.hasText(pan) && INDIAN_PAN.matcher(pan).matches();
    }

    public static boolean isPoaSandboxPan(String pan) {
        return StringUtils.hasText(pan) && POA_SANDBOX_PAN.matcher(pan).matches();
    }

    public static boolean isGatewayKycReadyPan(String pan) {
        return StringUtils.hasText(pan) && GATEWAY_KYC_READY_PAN.matcher(pan).matches();
    }

    public static boolean isGatewayKycUnavailablePan(String pan) {
        return StringUtils.hasText(pan) && GATEWAY_KYC_UNAVAILABLE_PAN.matcher(pan).matches();
    }

    public static boolean isCybrillaSandboxEnvironment(String poaBaseUrl) {
        return StringUtils.hasText(poaBaseUrl)
                && poaBaseUrl.toLowerCase(Locale.ROOT).contains("sandbox");
    }

    public static void validateIndianPan(String pan) {
        if (!isIndianPan(pan)) {
            throw new IllegalArgumentException(INDIAN_PAN_MESSAGE);
        }
    }

    /** Validate before persisting investor identity to the local database. */
    public static void validateForDatabase(String pan) {
        validateIndianPan(pan);
    }

    /**
     * Validate immediately before a POA {@code /poa/pre_verifications} call in sandbox.
     * Production only requires a structurally valid Indian PAN.
     */
    public static void validateBeforePoaApi(String pan, boolean cybrillaSandbox) {
        validateIndianPan(pan);
        if (cybrillaSandbox && !isPoaSandboxPan(pan)) {
            throw new IllegalArgumentException(POA_SANDBOX_PAN_MESSAGE);
        }
    }

    /**
     * Validate immediately before FP {@code /api/kyc/check} in sandbox.
     * Accepts the documented KYC-ready or fresh-KYC simulator patterns.
     */
    public static void validateBeforeKycComplianceApi(String pan, boolean cybrillaSandbox) {
        validateIndianPan(pan);
        if (cybrillaSandbox
                && !isGatewayKycReadyPan(pan)
                && !isGatewayKycUnavailablePan(pan)
                && !isPoaSandboxPan(pan)) {
            throw new IllegalArgumentException(GATEWAY_KYC_READINESS_PAN_MESSAGE);
        }
    }
}
