package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "investors")
@SQLRestriction("is_deleted = false")
public class Investor extends BaseEntity {

    @Column(nullable = false)
    private UUID distributorId;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String mobileNumber;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false, unique = true)
    private String pan;

    private LocalDate dateOfBirth;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvestorStatus investorStatus = InvestorStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KycStatus kycStatus = KycStatus.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BankVerificationStatus bankVerificationStatus = BankVerificationStatus.NOT_CAPTURED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskProfileType riskProfile = RiskProfileType.UNASSESSED;

    private String cybrillaInvestorId;
    private String onboardingNotes;
    @Column(nullable = false)
    private Boolean isDeleted = Boolean.FALSE;
    private LocalDateTime deletedAt;

    public UUID getDistributorId() { return distributorId; }
    public void setDistributorId(UUID distributorId) { this.distributorId = distributorId; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPan() { return pan; }
    public void setPan(String pan) { this.pan = pan; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String addressLine1) { this.addressLine1 = addressLine1; }
    public String getAddressLine2() { return addressLine2; }
    public void setAddressLine2(String addressLine2) { this.addressLine2 = addressLine2; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    public InvestorStatus getInvestorStatus() { return investorStatus; }
    public void setInvestorStatus(InvestorStatus investorStatus) { this.investorStatus = investorStatus; }
    public KycStatus getKycStatus() { return kycStatus; }
    public void setKycStatus(KycStatus kycStatus) { this.kycStatus = kycStatus; }
    public BankVerificationStatus getBankVerificationStatus() { return bankVerificationStatus; }
    public void setBankVerificationStatus(BankVerificationStatus bankVerificationStatus) { this.bankVerificationStatus = bankVerificationStatus; }
    public RiskProfileType getRiskProfile() { return riskProfile; }
    public void setRiskProfile(RiskProfileType riskProfile) { this.riskProfile = riskProfile; }
    public String getCybrillaInvestorId() { return cybrillaInvestorId; }
    public void setCybrillaInvestorId(String cybrillaInvestorId) { this.cybrillaInvestorId = cybrillaInvestorId; }
    public String getOnboardingNotes() { return onboardingNotes; }
    public void setOnboardingNotes(String onboardingNotes) { this.onboardingNotes = onboardingNotes; }
    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean isDeleted) { this.isDeleted = isDeleted; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
