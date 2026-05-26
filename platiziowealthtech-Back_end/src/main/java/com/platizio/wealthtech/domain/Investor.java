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
    private LocalDate anniversaryDate;
    private LocalDate goalMaturityDate;
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

    @Column(nullable = false)
    private UUID householdId;

    private String householdName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvestorRelationshipType relationshipType = InvestorRelationshipType.SELF;

    private UUID guardianInvestorId;
    private String guardianPan;
    private String cybrillaInvestorId;
    private String externalKycCheckId;
    private String externalKycRequestId;
    private String externalKycStatus;

    @Column(columnDefinition = "TEXT")
    private String externalKycPayloadJson;

    @Transient
    private Boolean externalSyncPending = Boolean.FALSE;

    @Transient
    private String externalSyncMessage;

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
    public LocalDate getAnniversaryDate() { return anniversaryDate; }
    public void setAnniversaryDate(LocalDate anniversaryDate) { this.anniversaryDate = anniversaryDate; }
    public LocalDate getGoalMaturityDate() { return goalMaturityDate; }
    public void setGoalMaturityDate(LocalDate goalMaturityDate) { this.goalMaturityDate = goalMaturityDate; }
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
    public UUID getHouseholdId() { return householdId; }
    public void setHouseholdId(UUID householdId) { this.householdId = householdId; }
    public String getHouseholdName() { return householdName; }
    public void setHouseholdName(String householdName) { this.householdName = householdName; }
    public InvestorRelationshipType getRelationshipType() { return relationshipType; }
    public void setRelationshipType(InvestorRelationshipType relationshipType) { this.relationshipType = relationshipType; }
    public UUID getGuardianInvestorId() { return guardianInvestorId; }
    public void setGuardianInvestorId(UUID guardianInvestorId) { this.guardianInvestorId = guardianInvestorId; }
    public String getGuardianPan() { return guardianPan; }
    public void setGuardianPan(String guardianPan) { this.guardianPan = guardianPan; }
    public String getCybrillaInvestorId() { return cybrillaInvestorId; }
    public void setCybrillaInvestorId(String cybrillaInvestorId) { this.cybrillaInvestorId = cybrillaInvestorId; }
    public String getExternalKycCheckId() { return externalKycCheckId; }
    public void setExternalKycCheckId(String externalKycCheckId) { this.externalKycCheckId = externalKycCheckId; }
    public String getExternalKycRequestId() { return externalKycRequestId; }
    public void setExternalKycRequestId(String externalKycRequestId) { this.externalKycRequestId = externalKycRequestId; }
    public String getExternalKycStatus() { return externalKycStatus; }
    public void setExternalKycStatus(String externalKycStatus) { this.externalKycStatus = externalKycStatus; }
    public String getExternalKycPayloadJson() { return externalKycPayloadJson; }
    public void setExternalKycPayloadJson(String externalKycPayloadJson) { this.externalKycPayloadJson = externalKycPayloadJson; }
    public Boolean getExternalSyncPending() { return externalSyncPending; }
    public void setExternalSyncPending(Boolean externalSyncPending) { this.externalSyncPending = externalSyncPending; }
    public String getExternalSyncMessage() { return externalSyncMessage; }
    public void setExternalSyncMessage(String externalSyncMessage) { this.externalSyncMessage = externalSyncMessage; }
    public String getOnboardingNotes() { return onboardingNotes; }
    public void setOnboardingNotes(String onboardingNotes) { this.onboardingNotes = onboardingNotes; }
    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean isDeleted) { this.isDeleted = isDeleted; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
