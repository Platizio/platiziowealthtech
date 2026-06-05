package com.platizio.wealthtech.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Local mirror of a Cybrilla POA <code>kyc_form</code> object (the KYC Forms
 * "modify" workflow). We persist a snapshot of every outbound request and the
 * latest inbound response so the form survives provider/network failures and so
 * the frontend can resume the Digilocker + eSign journey without re-fetching
 * from Cybrilla on every render.
 */
@Entity
@Table(name = "investor_kyc_forms")
public class InvestorKycForm extends BaseEntity {

    @Column(nullable = false)
    private UUID investorId;

    @Column(nullable = false)
    private UUID distributorId;

    @Column(length = 100)
    private String externalKycFormId;

    @Column(length = 30)
    private String type;

    @Column(length = 50)
    private String status;

    @Column(length = 1000)
    private String reason;

    @Column(length = 20)
    private String pan;

    @Column(length = 255)
    private String name;

    private LocalDate dateOfBirth;

    @Column(length = 1000)
    private String proofFetchUrl;

    @Column(length = 50)
    private String proofStatus;

    @Column(length = 1000)
    private String esignUrl;

    @Column(length = 50)
    private String esignStatus;

    @Column(nullable = false)
    private Boolean signatureProvided = Boolean.FALSE;

    @Column(columnDefinition = "TEXT")
    private String fieldsNeededJson;

    @Column(length = 1000)
    private String proofCallbackUrl;

    @Column(length = 1000)
    private String esignCallbackUrl;

    private OffsetDateTime expiresAt;

    private OffsetDateTime lastSyncedAt;

    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String externalRequestJson;

    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String externalResponseJson;

    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }
    public UUID getDistributorId() { return distributorId; }
    public void setDistributorId(UUID distributorId) { this.distributorId = distributorId; }
    public String getExternalKycFormId() { return externalKycFormId; }
    public void setExternalKycFormId(String externalKycFormId) { this.externalKycFormId = externalKycFormId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getPan() { return pan; }
    public void setPan(String pan) { this.pan = pan; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getProofFetchUrl() { return proofFetchUrl; }
    public void setProofFetchUrl(String proofFetchUrl) { this.proofFetchUrl = proofFetchUrl; }
    public String getProofStatus() { return proofStatus; }
    public void setProofStatus(String proofStatus) { this.proofStatus = proofStatus; }
    public String getEsignUrl() { return esignUrl; }
    public void setEsignUrl(String esignUrl) { this.esignUrl = esignUrl; }
    public String getEsignStatus() { return esignStatus; }
    public void setEsignStatus(String esignStatus) { this.esignStatus = esignStatus; }
    public Boolean getSignatureProvided() { return signatureProvided; }
    public void setSignatureProvided(Boolean signatureProvided) { this.signatureProvided = signatureProvided; }
    public String getFieldsNeededJson() { return fieldsNeededJson; }
    public void setFieldsNeededJson(String fieldsNeededJson) { this.fieldsNeededJson = fieldsNeededJson; }
    public String getProofCallbackUrl() { return proofCallbackUrl; }
    public void setProofCallbackUrl(String proofCallbackUrl) { this.proofCallbackUrl = proofCallbackUrl; }
    public String getEsignCallbackUrl() { return esignCallbackUrl; }
    public void setEsignCallbackUrl(String esignCallbackUrl) { this.esignCallbackUrl = esignCallbackUrl; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(OffsetDateTime lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public String getExternalRequestJson() { return externalRequestJson; }
    public void setExternalRequestJson(String externalRequestJson) { this.externalRequestJson = externalRequestJson; }
    public String getExternalResponseJson() { return externalResponseJson; }
    public void setExternalResponseJson(String externalResponseJson) { this.externalResponseJson = externalResponseJson; }
}
