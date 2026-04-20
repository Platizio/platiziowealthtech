package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "distributors")
public class Distributor extends BaseEntity {

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String mobileNumber;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false, unique = true)
    private String arnNumber;

    @Column(nullable = false)
    private String nismCertificateNumber;

    private LocalDate nismExpiryDate;

    @Column(unique = true)
    private String eUinNumber;

    private UUID masterDistributorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DistributorRole role = DistributorRole.MASTER_DISTRIBUTOR;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DistributorStatus status = DistributorStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KycStatus kycStatus = KycStatus.NOT_STARTED;

    private String bankAccountNumber;
    private String bankIfsc;
    private String bankAccountHolderName;
    private Integer profileCompletionPercent = 0;
    private Boolean internalRm = Boolean.FALSE;
    private String passwordHash;

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getArnNumber() { return arnNumber; }
    public void setArnNumber(String arnNumber) { this.arnNumber = arnNumber; }
    public String getNismCertificateNumber() { return nismCertificateNumber; }
    public void setNismCertificateNumber(String nismCertificateNumber) { this.nismCertificateNumber = nismCertificateNumber; }
    public LocalDate getNismExpiryDate() { return nismExpiryDate; }
    public void setNismExpiryDate(LocalDate nismExpiryDate) { this.nismExpiryDate = nismExpiryDate; }
    public String geteUinNumber() { return eUinNumber; }
    public void seteUinNumber(String eUinNumber) { this.eUinNumber = eUinNumber; }
    public UUID getMasterDistributorId() { return masterDistributorId; }
    public void setMasterDistributorId(UUID masterDistributorId) { this.masterDistributorId = masterDistributorId; }
    public DistributorRole getRole() { return role; }
    public void setRole(DistributorRole role) { this.role = role; }
    public DistributorStatus getStatus() { return status; }
    public void setStatus(DistributorStatus status) { this.status = status; }
    public KycStatus getKycStatus() { return kycStatus; }
    public void setKycStatus(KycStatus kycStatus) { this.kycStatus = kycStatus; }
    public String getBankAccountNumber() { return bankAccountNumber; }
    public void setBankAccountNumber(String bankAccountNumber) { this.bankAccountNumber = bankAccountNumber; }
    public String getBankIfsc() { return bankIfsc; }
    public void setBankIfsc(String bankIfsc) { this.bankIfsc = bankIfsc; }
    public String getBankAccountHolderName() { return bankAccountHolderName; }
    public void setBankAccountHolderName(String bankAccountHolderName) { this.bankAccountHolderName = bankAccountHolderName; }
    public Integer getProfileCompletionPercent() { return profileCompletionPercent; }
    public void setProfileCompletionPercent(Integer profileCompletionPercent) { this.profileCompletionPercent = profileCompletionPercent; }
    public Boolean getInternalRm() { return internalRm; }
    public void setInternalRm(Boolean internalRm) { this.internalRm = internalRm; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
