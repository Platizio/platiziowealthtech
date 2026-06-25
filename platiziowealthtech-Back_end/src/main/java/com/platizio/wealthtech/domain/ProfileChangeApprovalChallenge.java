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
 * An immutable, hashed snapshot of a distributor-filled investor profile change
 * and its investor-approval (2FA) evidence (Person-A R9/R10).
 *
 * <p>A challenge freezes the exact pending profile the investor will approve into
 * {@code pendingProfileJson} + {@code profileChangeSha256}. No profile write may be
 * applied unless an {@code APPROVED} challenge for that exact investor exists AND its
 * {@code profileChangeSha256} still equals a freshly-recomputed hash; the atomic
 * {@link com.platizio.wealthtech.service.ProfileChangeApprovalService#assertApprovedAndConsume}
 * gate both checks that and flips {@code APPROVED → CONSUMED} in the same call (so a
 * replay finds {@code CONSUMED} and is blocked). Append-only evidence: status
 * transitions only, never destructive edits.
 */
@Entity
@Table(name = "profile_change_approval_challenges")
public class ProfileChangeApprovalChallenge extends BaseEntity {

    @Column(nullable = false)
    private UUID investorId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String pendingProfileJson;

    @Column(nullable = false, length = 64)
    private String profileChangeSha256;

    @Column(nullable = false, length = 32)
    private String consentTemplateVersion;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String consentRenderedText;

    /** Links to the immutable {@code consent_records} evidence row, set on approve. */
    private UUID consentRecordId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ProfileChangeApprovalStatus status;

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

    @Column(length = 64)
    private String ipAddress;

    @Column(length = 512)
    private String userAgent;

    @Column(length = 128)
    private String sessionId;

    @Column(nullable = false)
    private Integer deliveryAttempts = 0;

    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }
    public String getPendingProfileJson() { return pendingProfileJson; }
    public void setPendingProfileJson(String pendingProfileJson) { this.pendingProfileJson = pendingProfileJson; }
    public String getProfileChangeSha256() { return profileChangeSha256; }
    public void setProfileChangeSha256(String profileChangeSha256) { this.profileChangeSha256 = profileChangeSha256; }
    public String getConsentTemplateVersion() { return consentTemplateVersion; }
    public void setConsentTemplateVersion(String consentTemplateVersion) { this.consentTemplateVersion = consentTemplateVersion; }
    public String getConsentRenderedText() { return consentRenderedText; }
    public void setConsentRenderedText(String consentRenderedText) { this.consentRenderedText = consentRenderedText; }
    public UUID getConsentRecordId() { return consentRecordId; }
    public void setConsentRecordId(UUID consentRecordId) { this.consentRecordId = consentRecordId; }
    public ProfileChangeApprovalStatus getStatus() { return status; }
    public void setStatus(ProfileChangeApprovalStatus status) { this.status = status; }
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
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Integer getDeliveryAttempts() { return deliveryAttempts; }
    public void setDeliveryAttempts(Integer deliveryAttempts) { this.deliveryAttempts = deliveryAttempts; }
}
