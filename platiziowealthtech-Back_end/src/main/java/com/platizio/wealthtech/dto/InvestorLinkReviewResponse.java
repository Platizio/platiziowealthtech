package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * Read-only review screen for the investor email-approval link (investor.md §6.2, R7a).
 * The authenticated investor (proven to own the link by the ownership guard + PAN match)
 * sees the distributor who sent it, the distributor-entered Step-1 profile details (the
 * frozen {@link com.platizio.wealthtech.domain.OnboardingSubmission} payload — passed through
 * verbatim as JSON for the FE to render), the link status and its expiry, so they can make
 * an informed Approve/Reject decision.
 *
 * <p>{@code profileDetailsJson} is the exact frozen payload the distributor entered; the
 * matching {@code contentSha256} lets the FE/audit trail bind any later attestation to the
 * exact revision that was reviewed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvestorLinkReviewResponse(
        String distributorDisplayName,
        String profileDetailsJson,
        String contentSha256,
        Integer revisionNo,
        String status,
        OffsetDateTime expiresAt) {
}
