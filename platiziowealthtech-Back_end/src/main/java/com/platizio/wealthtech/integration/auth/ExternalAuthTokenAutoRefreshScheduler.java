package com.platizio.wealthtech.integration.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExternalAuthTokenAutoRefreshScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ExternalAuthTokenAutoRefreshScheduler.class);

    private final ExternalBearerTokenService tokenService;
    private final boolean autoRefreshEnabled;

    public ExternalAuthTokenAutoRefreshScheduler(
            ExternalBearerTokenService tokenService,
            @Value("${external-auth.auto-refresh.enabled:true}") boolean autoRefreshEnabled
    ) {
        this.tokenService = tokenService;
        this.autoRefreshEnabled = autoRefreshEnabled;
    }

    /** Always warm Cybrilla/Finprim OAuth tokens once the app is ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void acquireTokensOnStartup() {
        acquireTokens("startup");
    }

    @Scheduled(
            fixedDelayString = "${external-auth.auto-refresh.check-interval-ms:1800000}",
            initialDelayString = "${external-auth.auto-refresh.startup-delay-ms:1800000}"
    )
    public void acquireTokensOnSchedule() {
        if (!autoRefreshEnabled) {
            return;
        }
        acquireTokens("scheduled");
    }

    private void acquireTokens(String trigger) {
        acquireCybrillaPreVerificationToken(trigger);
        acquireFinprimTenantToken(trigger);
    }

    private void acquireCybrillaPreVerificationToken(String trigger) {
        try {
            logger.info("external_auth_auto_refresh trigger='{}' provider='Cybrilla pre-verification' action='acquire'", trigger);
            tokenService.getCybrillaPreVerificationAccessToken();
        } catch (RuntimeException ex) {
            logger.warn(
                    "external_auth_auto_refresh failed trigger='{}' provider='Cybrilla pre-verification' reason='{}'",
                    trigger,
                    ex.getMessage()
            );
        }
    }

    private void acquireFinprimTenantToken(String trigger) {
        try {
            logger.info("external_auth_auto_refresh trigger='{}' provider='Fintech Primitives tenant' action='acquire'", trigger);
            tokenService.getFinprimTenantAccessToken();
        } catch (RuntimeException ex) {
            logger.warn(
                    "external_auth_auto_refresh failed trigger='{}' provider='Fintech Primitives tenant' reason='{}'",
                    trigger,
                    ex.getMessage()
            );
        }
    }
}
