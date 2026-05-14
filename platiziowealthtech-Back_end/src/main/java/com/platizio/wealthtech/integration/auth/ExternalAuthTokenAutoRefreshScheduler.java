package com.platizio.wealthtech.integration.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "external-auth.auto-refresh", name = "enabled", havingValue = "true")
public class ExternalAuthTokenAutoRefreshScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ExternalAuthTokenAutoRefreshScheduler.class);

    private final ExternalBearerTokenService tokenService;

    public ExternalAuthTokenAutoRefreshScheduler(ExternalBearerTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshTokensOnStartup() {
        refreshTokens("startup");
    }

    @Scheduled(
            fixedDelayString = "${external-auth.auto-refresh.check-interval-ms:60000}",
            initialDelayString = "${external-auth.auto-refresh.startup-delay-ms:60000}"
    )
    public void refreshTokensOnSchedule() {
        refreshTokens("scheduled_check");
    }

    private void refreshTokens(String trigger) {
        refreshCybrillaPreVerificationToken(trigger);
        refreshFinprimTenantToken(trigger);
    }

    private void refreshCybrillaPreVerificationToken(String trigger) {
        try {
            logger.info("external_auth_auto_refresh trigger='{}' provider='Cybrilla pre-verification'", trigger);
            tokenService.getCybrillaPreVerificationAccessToken();
        } catch (RuntimeException ex) {
            logger.warn("external_auth_auto_refresh failed trigger='{}' provider='Cybrilla pre-verification' reason='{}'", trigger, ex.getMessage());
        }
    }

    private void refreshFinprimTenantToken(String trigger) {
        try {
            logger.info("external_auth_auto_refresh trigger='{}' provider='Fintech Primitives tenant'", trigger);
            tokenService.getFinprimTenantAccessToken();
        } catch (RuntimeException ex) {
            logger.warn("external_auth_auto_refresh failed trigger='{}' provider='Fintech Primitives tenant' reason='{}'", trigger, ex.getMessage());
        }
    }
}
