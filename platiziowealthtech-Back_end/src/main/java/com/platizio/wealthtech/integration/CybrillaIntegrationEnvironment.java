package com.platizio.wealthtech.integration;

import com.platizio.wealthtech.integration.auth.CybrillaPreVerificationProperties;
import com.platizio.wealthtech.integration.auth.FinprimTenantProperties;
import com.platizio.wealthtech.validation.PanFormat;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Detects whether Cybrilla/Finprim are configured for sandbox simulation or production verification.
 */
@Component
public class CybrillaIntegrationEnvironment {

    public static final String ENV_SANDBOX = "sandbox";
    public static final String ENV_PRODUCTION = "production";

    private final CybrillaPreVerificationProperties poaProperties;
    private final FinprimTenantProperties finprimProperties;
    private final String configuredEnvironment;
    private final Boolean enforceSandboxPanPatternsOverride;

    public CybrillaIntegrationEnvironment(
            CybrillaPreVerificationProperties poaProperties,
            FinprimTenantProperties finprimProperties,
            @Value("${cybrilla.environment:sandbox}") String configuredEnvironment,
            @Value("${cybrilla.pre-verification.enforce-sandbox-pan-patterns:}") String enforceSandboxPanPatterns
    ) {
        this.poaProperties = poaProperties;
        this.finprimProperties = finprimProperties;
        this.configuredEnvironment = normalizeEnvironment(configuredEnvironment);
        this.enforceSandboxPanPatternsOverride = parseOptionalBoolean(enforceSandboxPanPatterns);
    }

    public boolean isPoaSandbox() {
        return PanFormat.isCybrillaSandboxEnvironment(poaProperties.getBaseUrl());
    }

    public boolean isFinprimSandbox() {
        String baseUrl = finprimProperties.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            return true;
        }
        String normalized = baseUrl.toLowerCase(Locale.ROOT);
        return normalized.contains("s.finprim.com") || normalized.contains("sandbox");
    }

    public boolean usesTestCredentials() {
        return containsTestMarker(poaProperties.credentials().clientId())
                || containsTestMarker(finprimProperties.credentials().clientId());
    }

    public boolean isProductionMode() {
        return ENV_PRODUCTION.equals(configuredEnvironment);
    }

    public boolean isSandboxMode() {
        if (isProductionMode()) {
            return false;
        }
        return isPoaSandbox() || isFinprimSandbox() || usesTestCredentials();
    }

    public String configuredEnvironment() {
        return configuredEnvironment;
    }

    /**
     * When true, only documented simulator PAN patterns are accepted before POA/KYC calls.
     * Defaults to true in sandbox mode and false in production mode.
     */
    public boolean enforceSandboxPanPatterns() {
        if (isProductionMode()) {
            return enforceSandboxPanPatternsOverride != null && enforceSandboxPanPatternsOverride;
        }
        if (enforceSandboxPanPatternsOverride != null) {
            return enforceSandboxPanPatternsOverride;
        }
        return isSandboxMode();
    }

    public String poaBaseUrl() {
        return poaProperties.getBaseUrl();
    }

    public String finprimBaseUrl() {
        return finprimProperties.getBaseUrl();
    }

    public String sandboxGuidanceMessage() {
        return "Cybrilla sandbox only simulates PAN/KYC checks (pattern XXXPXNNNNX with P as the 4th character). "
                + "It does not verify real PANs against the Income Tax Department. "
                + "For live PAN verification, switch to production credentials from Cybrilla onboarding and set "
                + "CYBRILLA_PRE_VERIFICATION_BASE_URL=https://api.cybrilla.com, "
                + "FINPRIM_BASE_URL=https://api.fintechprimitives.com, "
                + "and production client IDs (without _test_). "
                + "Current config: POA base URL=" + poaBaseUrl()
                + ", Finprim base URL=" + finprimBaseUrl()
                + ", test credentials=" + usesTestCredentials() + ".";
    }

    private static boolean containsTestMarker(String clientId) {
        return StringUtils.hasText(clientId) && clientId.toLowerCase(Locale.ROOT).contains("_test_");
    }

    private static Boolean parseOptionalBoolean(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        return Boolean.parseBoolean(rawValue.trim());
    }

    private static String normalizeEnvironment(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return ENV_SANDBOX;
        }
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        if (ENV_PRODUCTION.equals(normalized) || "prod".equals(normalized) || "live".equals(normalized)) {
            return ENV_PRODUCTION;
        }
        return ENV_SANDBOX;
    }
}
