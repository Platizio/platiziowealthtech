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
 * Self-authenticated investor portal identity (SRS FR-AUTH). Separate from the
 * distributor-owned {@link Investor} record. Passwordless — login is email/mobile
 * OTP, so there is no credential column. {@code investorId} links to the
 * distributor-created {@link Investor} by PAN, set only on explicit confirmation.
 */
@Entity
@Table(name = "investor_accounts")
public class InvestorAccount extends BaseEntity {

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false, length = 10, unique = true)
    private String pan;

    @Column(nullable = false, length = 320, unique = true)
    private String email;

    @Column(nullable = false, length = 20)
    private String mobileNumber;

    @Column(nullable = false)
    private Boolean emailVerified = Boolean.FALSE;

    @Column(nullable = false)
    private Boolean mobileVerified = Boolean.FALSE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private InvestorAccountStatus status = InvestorAccountStatus.PENDING_ACTIVATION;

    /** Links to the distributor-created Investor row (by PAN), once confirmed. */
    private UUID investorId;

    private OffsetDateTime activatedAt;

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPan() { return pan; }
    public void setPan(String pan) { this.pan = pan; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
    public Boolean getEmailVerified() { return emailVerified; }
    public void setEmailVerified(Boolean emailVerified) { this.emailVerified = emailVerified; }
    public Boolean getMobileVerified() { return mobileVerified; }
    public void setMobileVerified(Boolean mobileVerified) { this.mobileVerified = mobileVerified; }
    public InvestorAccountStatus getStatus() { return status; }
    public void setStatus(InvestorAccountStatus status) { this.status = status; }
    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }
    public OffsetDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(OffsetDateTime activatedAt) { this.activatedAt = activatedAt; }
}
