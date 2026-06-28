package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable consent evidence (SRS FR-CNS-002): the exact rendered consent text,
 * its SHA-256, the template version, and the actor context. Append-only.
 */
@Entity
@Table(name = "consent_records")
public class ConsentRecord extends BaseEntity {

    @Column(nullable = false, length = 16)
    private String subjectType;   // INVESTOR | DISTRIBUTOR

    @Column(nullable = false)
    private UUID subjectId;

    @Column(nullable = false, length = 64)
    private String consentKey;    // e.g. investor_tnc, contact_ownership_email, onboarding_attestation

    @Column(nullable = false, length = 32)
    private String templateVersion;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String renderedText;

    @Column(nullable = false, length = 64)
    private String contentSha256;

    @Column(length = 64)
    private String ipAddress;

    @Column(length = 512)
    private String userAgent;

    @Column(nullable = false)
    private OffsetDateTime acceptedAt;

    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }
    public String getConsentKey() { return consentKey; }
    public void setConsentKey(String consentKey) { this.consentKey = consentKey; }
    public String getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(String templateVersion) { this.templateVersion = templateVersion; }
    public String getRenderedText() { return renderedText; }
    public void setRenderedText(String renderedText) { this.renderedText = renderedText; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(OffsetDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
}
