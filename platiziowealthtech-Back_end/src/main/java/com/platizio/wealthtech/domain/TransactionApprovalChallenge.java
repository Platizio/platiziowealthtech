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
 * An immutable, hashed snapshot of a transaction (purchase / SIP / redemption)
 * and its investor-approval (2FA) evidence (Phase-2 plan §"Data model").
 *
 * <p>A challenge freezes the exact transaction details the investor will approve
 * into {@code snapshotJson} + {@code snapshotSha256}. No Cybrilla/FP write may fire
 * unless an {@code APPROVED} challenge for that exact transaction exists AND its
 * {@code snapshotSha256} still equals a freshly-recomputed snapshot hash; the atomic
 * {@link com.platizio.wealthtech.service.TransactionApprovalService#assertApprovedAndConsume}
 * gate both checks that and flips {@code APPROVED → CONSUMED} in the same call (so a
 * replay finds {@code CONSUMED} and is blocked). Append-only evidence: status
 * transitions only, never destructive edits.
 */
@Entity
@Table(name = "transaction_approval_challenges")
public class TransactionApprovalChallenge extends BaseEntity {

    /** The local id of the transaction being approved (order id or redemption id). */
    @Column(nullable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionType transactionType;   // PURCHASE | SIP | REDEMPTION

    @Column(nullable = false)
    private UUID investorId;

    @Column(nullable = false)
    private UUID investorAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionApprovalStatus status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(nullable = false, length = 64)
    private String snapshotSha256;

    @Column(nullable = false, length = 32)
    private String consentTemplateVersion;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String consentRenderedText;

    /** Links to the immutable {@code consent_records} evidence row, set on approve. */
    private UUID consentRecordId;

    @Column(nullable = false, length = 16)
    private String channel;    // EMAIL | MOBILE

    @Column(length = 320)
    private String maskedDestination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OtpPurpose otpPurpose;

    private OffsetDateTime expiresAt;
    private OffsetDateTime approvedAt;
    private OffsetDateTime consumedAt;
    private UUID supersededBy;

    @Column(length = 64)
    private String ipAddress;

    @Column(length = 512)
    private String userAgent;

    @Column(length = 128)
    private String sessionId;

    @Column(length = 128)
    private String correlationId;

    @Column(nullable = false)
    private Integer deliveryAttempts = 0;

    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }
    public TransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(TransactionType transactionType) { this.transactionType = transactionType; }
    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }
    public UUID getInvestorAccountId() { return investorAccountId; }
    public void setInvestorAccountId(UUID investorAccountId) { this.investorAccountId = investorAccountId; }
    public TransactionApprovalStatus getStatus() { return status; }
    public void setStatus(TransactionApprovalStatus status) { this.status = status; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public String getSnapshotSha256() { return snapshotSha256; }
    public void setSnapshotSha256(String snapshotSha256) { this.snapshotSha256 = snapshotSha256; }
    public String getConsentTemplateVersion() { return consentTemplateVersion; }
    public void setConsentTemplateVersion(String consentTemplateVersion) { this.consentTemplateVersion = consentTemplateVersion; }
    public String getConsentRenderedText() { return consentRenderedText; }
    public void setConsentRenderedText(String consentRenderedText) { this.consentRenderedText = consentRenderedText; }
    public UUID getConsentRecordId() { return consentRecordId; }
    public void setConsentRecordId(UUID consentRecordId) { this.consentRecordId = consentRecordId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getMaskedDestination() { return maskedDestination; }
    public void setMaskedDestination(String maskedDestination) { this.maskedDestination = maskedDestination; }
    public OtpPurpose getOtpPurpose() { return otpPurpose; }
    public void setOtpPurpose(OtpPurpose otpPurpose) { this.otpPurpose = otpPurpose; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(OffsetDateTime approvedAt) { this.approvedAt = approvedAt; }
    public OffsetDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(OffsetDateTime consumedAt) { this.consumedAt = consumedAt; }
    public UUID getSupersededBy() { return supersededBy; }
    public void setSupersededBy(UUID supersededBy) { this.supersededBy = supersededBy; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public Integer getDeliveryAttempts() { return deliveryAttempts; }
    public void setDeliveryAttempts(Integer deliveryAttempts) { this.deliveryAttempts = deliveryAttempts; }
}
