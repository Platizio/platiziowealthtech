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
 * An immutable, hashed revision of a distributor-assembled onboarding payload and
 * its investor-attestation evidence (SRS FR-ONB-001/002/003).
 */
@Entity
@Table(name = "onboarding_submissions")
public class OnboardingSubmission extends BaseEntity {

    @Column(nullable = false)
    private UUID investorId;

    @Column(nullable = false)
    private Integer revisionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OnboardingSubmissionStatus status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false, length = 64)
    private String contentSha256;

    @Column(nullable = false)
    private UUID submittedBy;

    @Column(nullable = false)
    private OffsetDateTime submittedAt;

    private OffsetDateTime attestedAt;
    private UUID attestedBy;

    @Column(length = 64)
    private String attestationIp;

    @Column(length = 512)
    private String attestationUa;

    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }
    public Integer getRevisionNo() { return revisionNo; }
    public void setRevisionNo(Integer revisionNo) { this.revisionNo = revisionNo; }
    public OnboardingSubmissionStatus getStatus() { return status; }
    public void setStatus(OnboardingSubmissionStatus status) { this.status = status; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public UUID getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(UUID submittedBy) { this.submittedBy = submittedBy; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(OffsetDateTime submittedAt) { this.submittedAt = submittedAt; }
    public OffsetDateTime getAttestedAt() { return attestedAt; }
    public void setAttestedAt(OffsetDateTime attestedAt) { this.attestedAt = attestedAt; }
    public UUID getAttestedBy() { return attestedBy; }
    public void setAttestedBy(UUID attestedBy) { this.attestedBy = attestedBy; }
    public String getAttestationIp() { return attestationIp; }
    public void setAttestationIp(String attestationIp) { this.attestationIp = attestationIp; }
    public String getAttestationUa() { return attestationUa; }
    public void setAttestationUa(String attestationUa) { this.attestationUa = attestationUa; }
}
