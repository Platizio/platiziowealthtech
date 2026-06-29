package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.KycReadinessAction;

/**
 * The investor-facing outcome of a POA pre-verification readiness check: the raw
 * {@code readiness.status}/{@code readiness.code} plus the derived next {@link KycReadinessAction}
 * and guidance. The frontend routes on {@code action} (e.g. SUBMIT_NEW_KYC / MODIFY_KYC → open the
 * DigiLocker KYC form; WAIT/BLOCKED/RETRY → show {@code message}).
 */
public record KycReadinessDecision(
        String status,
        String code,
        KycReadinessAction action,
        String message,
        String preVerificationId,
        boolean canInvest,
        boolean actionable
) {
    public static KycReadinessDecision from(String status, String code, String preVerificationId) {
        KycReadinessAction action = KycReadinessAction.forReadiness(status, code);
        return new KycReadinessDecision(
                status, code, action, action.message(), preVerificationId, action.canInvest(), action.isActionable());
    }
}
