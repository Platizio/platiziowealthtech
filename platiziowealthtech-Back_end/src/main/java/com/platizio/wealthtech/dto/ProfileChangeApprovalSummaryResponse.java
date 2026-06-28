package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Compact view of a pending distributor-filled profile-change (2FA) challenge for the
 * investor's Approvals page ({@code GET /investor/profile-changes}). Carries the frozen
 * pending-profile JSON the investor is approving and the rendered consent text, but NEVER
 * the OTP code — that is delivered out-of-band to the investor's verified email.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfileChangeApprovalSummaryResponse(
        UUID challengeId,
        UUID investorId,
        String status,
        String pendingProfileJson,
        String profileChangeSha256,
        String consentTemplateVersion,
        String consentRenderedText,
        String maskedDestination,
        String channel,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt) {

    public static ProfileChangeApprovalSummaryResponse from(ProfileChangeApprovalChallenge challenge) {
        return new ProfileChangeApprovalSummaryResponse(
                challenge.getId(),
                challenge.getInvestorId(),
                challenge.getStatus() == null ? null : challenge.getStatus().name(),
                challenge.getPendingProfileJson(),
                challenge.getProfileChangeSha256(),
                challenge.getConsentTemplateVersion(),
                challenge.getConsentRenderedText(),
                challenge.getMaskedDestination(),
                challenge.getChannel(),
                challenge.getExpiresAt(),
                challenge.getCreatedAt());
    }
}
