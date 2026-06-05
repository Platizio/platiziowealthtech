package com.platizio.wealthtech.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.kyc-sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InvestorKycStatusSyncScheduler {

    static final int LOCK_NAMESPACE = 0x504C545A; // PLTZ
    static final int LOCK_ID = 0x4B594353; // KYCS

    private static final Logger logger = LoggerFactory.getLogger(InvestorKycStatusSyncScheduler.class);

    private final InvestorKycService investorKycService;
    private final PostgresAdvisoryLockService advisoryLockService;
    private final int batchSize;

    public InvestorKycStatusSyncScheduler(
            InvestorKycService investorKycService,
            PostgresAdvisoryLockService advisoryLockService,
            @Value("${app.kyc-sync.batch-size:50}") int batchSize
    ) {
        this.investorKycService = investorKycService;
        this.advisoryLockService = advisoryLockService;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.kyc-sync.interval-ms:120000}",
            initialDelayString = "${app.kyc-sync.initial-delay-ms:${random.int[30000,90000]}}"
    )
    public void syncOutstandingKycStatuses() {
        try {
            boolean executed = advisoryLockService.runWithLock(LOCK_NAMESPACE, LOCK_ID, () -> {
                int synced = investorKycService.syncOutstandingExternalKycStatuses(batchSize);
                if (synced > 0) {
                    logger.info("external_kyc_sync_scheduler status='completed' synced='{}'", synced);
                }
            });
            if (!executed) {
                logger.debug("external_kyc_sync_scheduler status='skipped' reason='lock_not_acquired'");
            }
        } catch (RuntimeException ex) {
            logger.warn("external_kyc_sync_scheduler status='failed' reason='{}'", ex.getMessage());
        }
    }
}
