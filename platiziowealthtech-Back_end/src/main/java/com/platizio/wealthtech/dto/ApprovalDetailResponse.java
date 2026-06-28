package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.platizio.wealthtech.domain.TransactionApprovalChallenge;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Full view of a single transaction-approval (2FA) challenge for the investor's
 * review screen (Phase-2 plan §"Endpoint contract", {@code GET /investor/approvals/{id}}):
 * the frozen snapshot the investor is approving, the rendered consent text (immutable
 * evidence), the masked OTP destination and current status. Never carries the OTP code.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalDetailResponse(
        UUID challengeId,
        UUID transactionId,
        String transactionType,
        String status,
        String snapshotJson,
        String snapshotSha256,
        String consentRenderedText,
        String consentTemplateVersion,
        String maskedDestination,
        String channel,
        OffsetDateTime expiresAt,
        OffsetDateTime approvedAt,
        OffsetDateTime createdAt) {

    public static ApprovalDetailResponse from(TransactionApprovalChallenge challenge) {
        return new ApprovalDetailResponse(
                challenge.getId(),
                challenge.getTransactionId(),
                challenge.getTransactionType() == null ? null : challenge.getTransactionType().name(),
                challenge.getStatus() == null ? null : challenge.getStatus().name(),
                challenge.getSnapshotJson(),
                challenge.getSnapshotSha256(),
                challenge.getConsentRenderedText(),
                challenge.getConsentTemplateVersion(),
                challenge.getMaskedDestination(),
                challenge.getChannel(),
                challenge.getExpiresAt(),
                challenge.getApprovedAt(),
                challenge.getCreatedAt());
    }
}
