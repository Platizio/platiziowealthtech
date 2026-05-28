package com.platizio.wealthtech.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BlockedTokenPurgeScheduler {

    static final int LOCK_NAMESPACE = 0x504C545A; // PLTZ
    static final int LOCK_ID = 0x42545053; // BTPS

    private static final Logger logger = LoggerFactory.getLogger(BlockedTokenPurgeScheduler.class);

    private final AuthService authService;
    private final PostgresAdvisoryLockService advisoryLockService;

    public BlockedTokenPurgeScheduler(AuthService authService, PostgresAdvisoryLockService advisoryLockService) {
        this.authService = authService;
        this.advisoryLockService = advisoryLockService;
    }

    @Scheduled(
            fixedDelayString = "${app.auth.blocked-token-purge-interval-ms:3600000}",
            initialDelayString = "${app.auth.blocked-token-purge-initial-delay-ms:${random.int[30000,90000]}}"
    )
    public void purgeExpiredBlockedTokens() {
        try {
            boolean executed = advisoryLockService.runWithLock(LOCK_NAMESPACE, LOCK_ID, () -> {
                long purged = authService.purgeExpiredBlockedTokens();
                if (purged > 0) {
                    logger.info("blocked_token_purge status='completed' purged='{}'", purged);
                }
            });
            if (!executed) {
                logger.debug("blocked_token_purge status='skipped' reason='lock_not_acquired'");
            }
        } catch (RuntimeException ex) {
            logger.warn("blocked_token_purge status='failed' reason='{}'", ex.getMessage());
        }
    }
}
