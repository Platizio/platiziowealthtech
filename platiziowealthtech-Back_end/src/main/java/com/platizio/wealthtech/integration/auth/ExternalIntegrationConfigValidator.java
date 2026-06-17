package com.platizio.wealthtech.integration.auth;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * BUG-029: fail fast (loudly, at startup) when the live Cybrilla/Fintech Primitives integration is
 * enabled but not fully configured. Previously a missing FP tenant credential only surfaced deep in
 * the first order/KYC call as a confusing {@code 400 "... client secret is not configured"}; now the
 * exact gap is logged once at boot so operators see it before any investor flow is attempted.
 *
 * <p>This intentionally does NOT abort startup: running with the mock client
 * ({@code cybrilla.integration.real-client-enabled=false}) is a supported mode, and the app should
 * stay up for non-FP features even when FP is unconfigured. It only logs.
 */
@Component
public class ExternalIntegrationConfigValidator {

    private static final Logger logger = LoggerFactory.getLogger(ExternalIntegrationConfigValidator.class);

    private final FinprimTenantProperties finprimProperties;
    private final CybrillaPreVerificationProperties poaProperties;
    private final boolean realClientEnabled;

    public ExternalIntegrationConfigValidator(
            FinprimTenantProperties finprimProperties,
            CybrillaPreVerificationProperties poaProperties,
            @Value("${cybrilla.integration.real-client-enabled:true}") boolean realClientEnabled
    ) {
        this.finprimProperties = finprimProperties;
        this.poaProperties = poaProperties;
        this.realClientEnabled = realClientEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateOnStartup() {
        if (!realClientEnabled) {
            logger.info(
                    "external_integration_config status='mock_client' "
                            + "note='cybrilla.integration.real-client-enabled=false — live order/KYC/payment flows are mocked'");
            return;
        }

        List<String> missing = new ArrayList<>();
        collectMissing(missing, finprimProperties.credentials(), "Fintech Primitives tenant");
        if (!StringUtils.hasText(finprimProperties.tenantHeaderValue())) {
            missing.add("Fintech Primitives tenant name/id (finprim.tenant.name|id)");
        }
        collectMissing(missing, poaProperties.credentials(), "Cybrilla POA pre-verification");

        if (missing.isEmpty()) {
            logger.info(
                    "external_integration_config status='configured' fp_tenant='{}' poa_base_url='{}'",
                    finprimProperties.tenantHeaderValue(), poaProperties.getBaseUrl());
        } else {
            logger.warn(
                    "external_integration_config status='INCOMPLETE' "
                            + "note='live Cybrilla/FP order, KYC, payment and redemption flows will fail (503/502) until configured; "
                            + "the app stays up for non-FP features' missing={}",
                    missing);
        }
    }

    private void collectMissing(List<String> missing, OAuthClientCredentials credentials, String providerName) {
        try {
            credentials.validate(providerName);
        } catch (RuntimeException ex) {
            // validate() messages never include secret values — safe to log.
            missing.add(ex.getMessage());
        }
    }
}
