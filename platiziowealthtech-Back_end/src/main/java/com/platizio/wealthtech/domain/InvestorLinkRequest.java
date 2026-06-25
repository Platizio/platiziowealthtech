package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A token-addressed email approval link a distributor sends so the investor can review
 * the entered Step-1 basic identity and approve the investor↔distributor link
 * (investor.md §4 V62, R3/R5). The link is the second factor: the raw token is delivered
 * to the verified email and exchanged for review/approve. At most one {@code PENDING}
 * request is live per investor (DB partial-unique index {@code ux_investor_link_request_live}).
 */
@Entity
@Table(name = "investor_link_requests")
public class InvestorLinkRequest extends BaseEntity {

    @Column(nullable = false)
    private UUID investorId;

    @Column(nullable = false, length = 10)
    private String pan;

    @Column(nullable = false)
    private UUID pendingDistributorId;

    @Column(nullable = false, length = 64)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private InvestorLinkRequestStatus status;

    private UUID onboardingSubmissionId;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    private OffsetDateTime approvedAt;

    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }
    public String getPan() { return pan; }
    public void setPan(String pan) { this.pan = pan; }
    public UUID getPendingDistributorId() { return pendingDistributorId; }
    public void setPendingDistributorId(UUID pendingDistributorId) { this.pendingDistributorId = pendingDistributorId; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public InvestorLinkRequestStatus getStatus() { return status; }
    public void setStatus(InvestorLinkRequestStatus status) { this.status = status; }
    public UUID getOnboardingSubmissionId() { return onboardingSubmissionId; }
    public void setOnboardingSubmissionId(UUID onboardingSubmissionId) { this.onboardingSubmissionId = onboardingSubmissionId; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(OffsetDateTime approvedAt) { this.approvedAt = approvedAt; }
}
