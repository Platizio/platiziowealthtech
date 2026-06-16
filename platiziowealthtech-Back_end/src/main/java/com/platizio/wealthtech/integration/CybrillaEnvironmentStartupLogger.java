package com.platizio.wealthtech.integration;

import com.platizio.wealthtech.integration.auth.ExternalBearerTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CybrillaEnvironmentStartupLogger {

    private static final Logger logger = LoggerFactory.getLogger(CybrillaEnvironmentStartupLogger.class);

    private final CybrillaIntegrationEnvironment environment;
    private final ExternalBearerTokenService tokenService;

    public CybrillaEnvironmentStartupLogger(
            CybrillaIntegrationEnvironment environment,
            ExternalBearerTokenService tokenService
    ) {
        this.environment = environment;
        this.tokenService = tokenService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logEnvironmentMode() {
        if (environment.isProductionMode()) {
            tokenService.invalidateCybrillaPreVerificationToken();
            tokenService.invalidateFinprimTenantToken();
            logger.info(
                    "cybrilla_environment mode='production' configured_environment='{}' poa_base_url='{}' "
                            + "finprim_base_url='{}' enforce_sandbox_pan_patterns='{}' action='cleared_cached_tokens'",
                    environment.configuredEnvironment(),
                    environment.poaBaseUrl(),
                    environment.finprimBaseUrl(),
                    environment.enforceSandboxPanPatterns()
            );
            if (environment.isPoaSandbox() || environment.isFinprimSandbox() || environment.usesTestCredentials()) {
                logger.warn(
                        "cybrilla_environment mode='production' but sandbox signals remain: poa_sandbox='{}' "
                                + "finprim_sandbox='{}' test_credentials='{}'. Verify production URLs and client IDs.",
                        environment.isPoaSandbox(),
                        environment.isFinprimSandbox(),
                        environment.usesTestCredentials()
                );
            }
            return;
        }

        if (environment.isSandboxMode()) {
            logger.warn(
                    "cybrilla_environment mode='sandbox' configured_environment='{}' poa_base_url='{}' "
                            + "finprim_base_url='{}' test_credentials='{}' enforce_sandbox_pan_patterns='{}'. {}",
                    environment.configuredEnvironment(),
                    environment.poaBaseUrl(),
                    environment.finprimBaseUrl(),
                    environment.usesTestCredentials(),
                    environment.enforceSandboxPanPatterns(),
                    environment.sandboxGuidanceMessage()
            );
            return;
        }
        logger.info(
                "cybrilla_environment mode='inferred_production' configured_environment='{}' poa_base_url='{}' "
                        + "finprim_base_url='{}' enforce_sandbox_pan_patterns='{}'",
                environment.configuredEnvironment(),
                environment.poaBaseUrl(),
                environment.finprimBaseUrl(),
                environment.enforceSandboxPanPatterns()
        );
    }
}
