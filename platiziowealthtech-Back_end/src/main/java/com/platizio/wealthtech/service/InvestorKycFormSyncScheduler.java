package com.platizio.wealthtech.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polling fallback for the Cybrilla POA KYC Forms (modify) workflow. Webhooks
 * are the primary reconciliation path; this scheduler periodically re-fetches
 * any still-active kyc_form so the local mirror converges even if a webhook is
 * missed. Disabled by default ({@code app.kyc-form-sync.enabled=false}) to avoid
 * background provider calls when not configured.
 */
@Component
@ConditionalOnProperty(prefix = "app.kyc-form-sync", name = "enabled", havingValue = "true", matchIfMissing = false)
public class InvestorKycFormSyncScheduler {

    static final int LOCK_NAMESPACE = 0x504C545A; // PLTZ
    static final int LOCK_ID = 0x4B594346; // KYCF

    private static final Logger logger = LoggerFactory.getLogger(InvestorKycFormSyncScheduler.class);

    private final InvestorKycFormService investorKycFormService;
    private final PostgresAdvisoryLockService advisoryLockService;
    private final int batchSize;

    public InvestorKycFormSyncScheduler(
            InvestorKycFormService investorKycFormService,
            PostgresAdvisoryLockService advisoryLockService,
            @Value("${app.kyc-form-sync.batch-size:50}") int batchSize
    ) {
        this.investorKycFormService = investorKycFormService;
        this.advisoryLockService = advisoryLockService;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.kyc-form-sync.interval-ms:180000}",
            initialDelayString = "${app.kyc-form-sync.initial-delay-ms:${random.int[30000,90000]}}"
    )
    public void syncActiveKycForms() {
        try {
            boolean executed = advisoryLockService.runWithLock(LOCK_NAMESPACE, LOCK_ID, () -> {
                int synced = investorKycFormService.syncActiveForms(batchSize);
                if (synced > 0) {
                    logger.info("kyc_form_sync_scheduler status='completed' synced='{}'", synced);
                }
            });
            if (!executed) {
                logger.debug("kyc_form_sync_scheduler status='skipped' reason='lock_not_acquired'");
            }
        } catch (RuntimeException ex) {
            logger.warn("kyc_form_sync_scheduler status='failed' reason='{}'", ex.getMessage());
        }
    }
}
