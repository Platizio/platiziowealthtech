package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.platizio.wealthtech.domain.TransactionApprovalChallenge;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Compact view of a pending transaction-approval (2FA) challenge for the investor's
 * Approval Center list (Phase-2 plan §"Endpoint contract", {@code GET /investor/approvals}).
 * Never carries the OTP code or the full snapshot — those are revealed only on the
 * detail endpoint / consent flow.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalSummaryResponse(
        UUID challengeId,
        UUID transactionId,
        String transactionType,
        String status,
        String maskedDestination,
        String channel,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt) {

    public static ApprovalSummaryResponse from(TransactionApprovalChallenge challenge) {
        return new ApprovalSummaryResponse(
                challenge.getId(),
                challenge.getTransactionId(),
                challenge.getTransactionType() == null ? null : challenge.getTransactionType().name(),
                challenge.getStatus() == null ? null : challenge.getStatus().name(),
                challenge.getMaskedDestination(),
                challenge.getChannel(),
                challenge.getExpiresAt(),
                challenge.getCreatedAt());
    }
}
