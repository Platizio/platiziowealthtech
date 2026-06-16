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
    private final boolean warmOnStartup;

    public ExternalAuthTokenAutoRefreshScheduler(
            ExternalBearerTokenService tokenService,
            @Value("${external-auth.auto-refresh.enabled:true}") boolean autoRefreshEnabled,
            @Value("${external-auth.auto-refresh.warm-on-startup:true}") boolean warmOnStartup
    ) {
        this.tokenService = tokenService;
        this.autoRefreshEnabled = autoRefreshEnabled;
        this.warmOnStartup = warmOnStartup;
    }

    /** Optionally warm Cybrilla/Finprim OAuth tokens once the app is ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void acquireTokensOnStartup() {
        if (!warmOnStartup) {
            logger.info(
                    "external_auth_auto_refresh trigger='startup' action='skipped' reason='warm_on_startup_disabled'"
            );
            return;
        }
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
        refreshCybrillaPreVerificationToken(trigger);
        refreshFinprimTenantToken(trigger);
    }

    private void refreshCybrillaPreVerificationToken(String trigger) {
        try {
            logger.info("external_auth_auto_refresh trigger='{}' provider='Cybrilla pre-verification' action='refresh_if_due'", trigger);
            if ("startup".equals(trigger)) {
                tokenService.getCybrillaPreVerificationAccessToken();
            } else {
                tokenService.refreshCybrillaPreVerificationTokenIfCachedAndDue();
            }
        } catch (RuntimeException ex) {
            logger.warn(
                    "external_auth_auto_refresh failed trigger='{}' provider='Cybrilla pre-verification' reason='{}'",
                    trigger,
                    ex.getMessage()
            );
        }
    }

    private void refreshFinprimTenantToken(String trigger) {
        try {
            logger.info("external_auth_auto_refresh trigger='{}' provider='Fintech Primitives tenant' action='refresh_if_due'", trigger);
            if ("startup".equals(trigger)) {
                tokenService.getFinprimTenantAccessToken();
            } else {
                tokenService.refreshFinprimTenantTokenIfCachedAndDue();
            }
        } catch (RuntimeException ex) {
            logger.warn(
                    "external_auth_auto_refresh failed trigger='{}' provider='Fintech Primitives tenant' reason='{}'",
                    trigger,
                    ex.getMessage()
            );
        }
    }
}
