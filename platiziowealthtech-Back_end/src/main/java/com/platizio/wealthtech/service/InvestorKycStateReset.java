package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorStatus;
import com.platizio.wealthtech.domain.KycStatus;

/**
 * Shared KYC attempt reset logic used when identity changes or a forced re-check
 * is requested. Kept separate from {@link InvestorKycService} and
 * {@link InvestorService} to avoid a Spring bean cycle
 * (InvestorKycService → BankVerificationStarter → InvestorService).
 */
final class InvestorKycStateReset {

    private InvestorKycStateReset() {
    }

    static void resetAttemptState(Investor investor) {
        if (investor == null) {
            return;
        }
        investor.setExternalKycCheckId(null);
        investor.setExternalKycRequestId(null);
        investor.setExternalKycStatus(null);
        investor.setKycReadinessStatus(null);
        investor.setKycReadinessCode(null);
        investor.setKycReadinessReason(null);
        investor.setPanVerificationStatus(null);
        investor.setPanVerificationCode(null);
        investor.setPanVerificationReason(null);
        investor.setPanAadhaarLinkStatus(null);
        investor.setPanAadhaarLinkReason(null);
        investor.setExternalKycComplianceId(null);
        investor.setKycComplianceStatus(null);
        investor.setKycComplianceReason(null);
        investor.setKycComplianceAction(null);
        investor.setKycConstraintsJson(null);
        investor.setExternalKycPayloadJson(null);
        investor.setKycStatus(KycStatus.IN_PROGRESS);
        if (investor.getInvestorStatus() == InvestorStatus.READY_FOR_TRANSACTIONS) {
            investor.setInvestorStatus(InvestorStatus.ONBOARDING);
        }
    }

    static void resetForIdentityChange(Investor investor) {
        if (investor == null || investor.getKycStatus() == KycStatus.COMPLETED) {
            return;
        }
        resetAttemptState(investor);
    }
}
