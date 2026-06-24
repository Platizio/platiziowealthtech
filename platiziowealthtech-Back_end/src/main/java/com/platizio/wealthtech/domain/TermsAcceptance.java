package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A persisted Terms &amp; Conditions acceptance (Task 4 / DF-10). One row per
 * accepted (subject, document, version), with the timestamp and request IP /
 * user-agent for the audit trail. Separate from the FATCA declaration.
 */
@Entity
@Table(name = "terms_acceptances")
public class TermsAcceptance extends BaseEntity {

    @Column(nullable = false, length = 16)
    private String subjectType;   // INVESTOR | DISTRIBUTOR

    @Column(nullable = false)
    private UUID subjectId;

    @Column(nullable = false, length = 64)
    private String documentKey;   // e.g. investor_tnc

    @Column(nullable = false, length = 32)
    private String version;       // e.g. v1.0

    @Column(nullable = false)
    private OffsetDateTime acceptedAt;

    @Column(length = 64)
    private String ipAddress;

    @Column(length = 512)
    private String userAgent;

    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }
    public String getDocumentKey() { return documentKey; }
    public void setDocumentKey(String documentKey) { this.documentKey = documentKey; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(OffsetDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
