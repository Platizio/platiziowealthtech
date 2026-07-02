package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A single investor nominee (IRIS Phase 1). Modelled on {@link InvestorBankAccount}:
 * an {@code investorId} FK column with NO JPA relationship; the set of nominees for an
 * investor is delete-then-insert replaced and frozen into the onboarding snapshot in
 * {@code nomineeIndex} order.
 */
@Entity
@Table(name = "investor_nominees")
public class InvestorNominee extends BaseEntity {

    @Column(nullable = false)
    private UUID investorId;

    @Column(nullable = false)
    private Integer nomineeIndex;

    @Column(nullable = false)
    private String fullName;

    private LocalDate dateOfBirth;
    private String relationship;
    private BigDecimal sharePercent;
    private String mobileNumber;
    private String email;
    private String idType;
    private String idNumber;
    private String addressLine1;
    private String addressLine2;
    private String addressLine3;
    private String city;
    private String state;
    private String postalCode;
    private String country;

    @Column(nullable = false)
    private Boolean sameAsApplicant = Boolean.FALSE;

    /** Guardian, required by self-service flows when the nominee is a minor (V71 graft). */
    private String guardianName;

    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }
    public Integer getNomineeIndex() { return nomineeIndex; }
    public void setNomineeIndex(Integer nomineeIndex) { this.nomineeIndex = nomineeIndex; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getRelationship() { return relationship; }
    public void setRelationship(String relationship) { this.relationship = relationship; }
    public BigDecimal getSharePercent() { return sharePercent; }
    public void setSharePercent(BigDecimal sharePercent) { this.sharePercent = sharePercent; }
    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getIdType() { return idType; }
    public void setIdType(String idType) { this.idType = idType; }
    public String getIdNumber() { return idNumber; }
    public void setIdNumber(String idNumber) { this.idNumber = idNumber; }
    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String addressLine1) { this.addressLine1 = addressLine1; }
    public String getAddressLine2() { return addressLine2; }
    public void setAddressLine2(String addressLine2) { this.addressLine2 = addressLine2; }
    public String getAddressLine3() { return addressLine3; }
    public void setAddressLine3(String addressLine3) { this.addressLine3 = addressLine3; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public Boolean getSameAsApplicant() { return sameAsApplicant; }
    public void setSameAsApplicant(Boolean sameAsApplicant) { this.sameAsApplicant = sameAsApplicant; }
    public String getGuardianName() { return guardianName; }
    public void setGuardianName(String guardianName) { this.guardianName = guardianName; }
}
