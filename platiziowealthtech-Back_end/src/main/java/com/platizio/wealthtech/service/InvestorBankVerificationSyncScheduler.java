package com.platizio.wealthtech.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.bank-sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InvestorBankVerificationSyncScheduler {

    static final int LOCK_NAMESPACE = 0x504C545A; // PLTZ
    static final int LOCK_ID = 0x42414E4B; // BANK

    private static final Logger logger = LoggerFactory.getLogger(InvestorBankVerificationSyncScheduler.class);

    private final InvestorService investorService;
    private final PostgresAdvisoryLockService advisoryLockService;
    private final int batchSize;

    public InvestorBankVerificationSyncScheduler(
            InvestorService investorService,
            PostgresAdvisoryLockService advisoryLockService,
            @Value("${app.bank-sync.batch-size:50}") int batchSize
    ) {
        this.investorService = investorService;
        this.advisoryLockService = advisoryLockService;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.bank-sync.interval-ms:120000}",
            initialDelayString = "${app.bank-sync.initial-delay-ms:${random.int[30000,90000]}}"
    )
    public void syncOutstandingBankVerificationStatuses() {
        try {
            boolean executed = advisoryLockService.runWithLock(LOCK_NAMESPACE, LOCK_ID, () -> {
                int synced = investorService.syncOutstandingBankVerificationStatuses(batchSize);
                if (synced > 0) {
                    logger.info("external_bank_verification_sync_scheduler status='completed' synced='{}'", synced);
                }
            });
            if (!executed) {
                logger.debug("external_bank_verification_sync_scheduler status='skipped' reason='lock_not_acquired'");
            }
        } catch (RuntimeException ex) {
            logger.warn("external_bank_verification_sync_scheduler status='failed' reason='{}'", ex.getMessage());
        }
    }
}
