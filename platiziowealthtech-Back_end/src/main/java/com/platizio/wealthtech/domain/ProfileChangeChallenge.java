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
 * An immutable, hashed snapshot of a proposed investor-profile change and its
 * investor-approval evidence (investor.md R9/R10). Mirrors
 * {@link TransactionApprovalChallenge}: the proposed profile (or diff) is frozen into
 * {@code snapshotJson} + {@code snapshotSha256}; the live profile is NOT mutated until
 * an atomic {@code APPROVED -> CONSUMED} flip authorises exactly one apply. One live
 * change per investor (partial unique index).
 */
@Entity
@Table(name = "profile_change_challenges")
public class ProfileChangeChallenge extends BaseEntity {

    @Column(nullable = false)
    private UUID investorId;

    private UUID investorAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ProfileChangeType changeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProfileChangeStatus status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(nullable = false, length = 64)
    private String snapshotSha256;

    @Column(length = 32)
    private String consentTemplateVersion;

    @Column(columnDefinition = "TEXT")
    private String consentRenderedText;

    private UUID consentRecordId;

    @Column(length = 16)
    private String channel;

    @Column(length = 320)
    private String maskedDestination;

    @Column(length = 32)
    private String otpPurpose;

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
    private int deliveryAttempts = 0;

    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }

    public UUID getInvestorAccountId() { return investorAccountId; }
    public void setInvestorAccountId(UUID investorAccountId) { this.investorAccountId = investorAccountId; }

    public ProfileChangeType getChangeType() { return changeType; }
    public void setChangeType(ProfileChangeType changeType) { this.changeType = changeType; }

    public ProfileChangeStatus getStatus() { return status; }
    public void setStatus(ProfileChangeStatus status) { this.status = status; }

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

    public String getOtpPurpose() { return otpPurpose; }
    public void setOtpPurpose(String otpPurpose) { this.otpPurpose = otpPurpose; }

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

    public int getDeliveryAttempts() { return deliveryAttempts; }
    public void setDeliveryAttempts(int deliveryAttempts) { this.deliveryAttempts = deliveryAttempts; }
}
