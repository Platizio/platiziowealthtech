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
 * A token-addressed email approval link sent to an investor after the distributor
 * enters Step-1 basic identity (investor.md R3). The distributor-entered details are
 * frozen into {@code reviewPayloadJson} + {@code reviewSha256}; only the SHA-256 of the
 * opaque link token is stored. At most one PENDING request per investor (a re-send
 * supersedes the prior one). Approval links {@code investors.distributor_id} by PAN.
 */
@Entity
@Table(name = "investor_link_requests")
public class InvestorLinkRequest extends BaseEntity {

    @Column(nullable = false)
    private UUID investorId;

    @Column(nullable = false, length = 20)
    private String pan;

    @Column(nullable = false)
    private UUID distributorId;

    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private LinkRequestStatus status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reviewPayloadJson;

    @Column(nullable = false, length = 64)
    private String reviewSha256;

    @Column(nullable = false, length = 320)
    private String sentToEmail;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    private OffsetDateTime approvedAt;
    private OffsetDateTime rejectedAt;

    @Column(length = 64)
    private String approvalIp;

    @Column(length = 512)
    private String approvalUa;

    /** Links to the immutable consent_records evidence row, set on approve. */
    private UUID consentRecordId;

    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }

    public String getPan() { return pan; }
    public void setPan(String pan) { this.pan = pan; }

    public UUID getDistributorId() { return distributorId; }
    public void setDistributorId(UUID distributorId) { this.distributorId = distributorId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public LinkRequestStatus getStatus() { return status; }
    public void setStatus(LinkRequestStatus status) { this.status = status; }

    public String getReviewPayloadJson() { return reviewPayloadJson; }
    public void setReviewPayloadJson(String reviewPayloadJson) { this.reviewPayloadJson = reviewPayloadJson; }

    public String getReviewSha256() { return reviewSha256; }
    public void setReviewSha256(String reviewSha256) { this.reviewSha256 = reviewSha256; }

    public String getSentToEmail() { return sentToEmail; }
    public void setSentToEmail(String sentToEmail) { this.sentToEmail = sentToEmail; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }

    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(OffsetDateTime approvedAt) { this.approvedAt = approvedAt; }

    public OffsetDateTime getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(OffsetDateTime rejectedAt) { this.rejectedAt = rejectedAt; }

    public String getApprovalIp() { return approvalIp; }
    public void setApprovalIp(String approvalIp) { this.approvalIp = approvalIp; }

    public String getApprovalUa() { return approvalUa; }
    public void setApprovalUa(String approvalUa) { this.approvalUa = approvalUa; }

    public UUID getConsentRecordId() { return consentRecordId; }
    public void setConsentRecordId(UUID consentRecordId) { this.consentRecordId = consentRecordId; }
}
