package com.platizio.wealthtech.integration.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "external-auth.debug", name = "enabled", havingValue = "true")
public class ExternalAuthDebugStartupLogger {

    private static final Logger logger = LoggerFactory.getLogger(ExternalAuthDebugStartupLogger.class);

    private final ExternalBearerTokenService tokenService;

    public ExternalAuthDebugStartupLogger(ExternalBearerTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void printTokensOnStartup() {
        try {
            logger.info("external_auth startup_check provider='Cybrilla pre-verification'");
            tokenService.getCybrillaPreVerificationAccessToken();
        } catch (RuntimeException ex) {
            logger.warn("external_auth startup_check failed provider='Cybrilla pre-verification' reason='{}'", ex.getMessage());
        }

        try {
            logger.info("external_auth startup_check provider='Fintech Primitives tenant'");
            tokenService.getFinprimTenantAccessToken();
        } catch (RuntimeException ex) {
            logger.warn("external_auth startup_check failed provider='Fintech Primitives tenant' reason='{}'", ex.getMessage());
        }
    }
}
