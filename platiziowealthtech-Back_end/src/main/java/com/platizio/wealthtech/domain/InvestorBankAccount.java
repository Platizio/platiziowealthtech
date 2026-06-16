package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "investor_bank_accounts")
public class InvestorBankAccount extends BaseEntity {

    @Column(nullable = false)
    private UUID investorId;

    @Column(nullable = false)
    private String accountHolderName;

    @Column(nullable = false)
    private String accountNumber;

    @Column(nullable = false)
    private String ifscCode;

    private String bankName;
    private String branchName;

    @Column(nullable = false)
    private String accountType = "savings";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BankVerificationStatus verificationStatus = BankVerificationStatus.NOT_CAPTURED;

    private String cybrillaBankId;
    private Integer fpBankAccountOldId;
    private String cybrillaBankVerificationId;
    private String cybrillaBankVerificationStatus;
    private String cybrillaBankVerificationConfidence;
    @Column(columnDefinition = "TEXT")
    private String externalVerificationRequestJson;
    @Column(columnDefinition = "TEXT")
    private String externalVerificationResponseJson;

    @Column(nullable = false)
    private Boolean externalSyncPending = Boolean.FALSE;

    @Column(length = 1000)
    private String externalSyncMessage;

    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }
    public String getAccountHolderName() { return accountHolderName; }
    public void setAccountHolderName(String accountHolderName) { this.accountHolderName = accountHolderName; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public String getIfscCode() { return ifscCode; }
    public void setIfscCode(String ifscCode) { this.ifscCode = ifscCode; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public String getAccountType() { return accountType; }
    public void setAccountType(String accountType) { this.accountType = accountType; }
    public BankVerificationStatus getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(BankVerificationStatus verificationStatus) { this.verificationStatus = verificationStatus; }
    public String getCybrillaBankId() { return cybrillaBankId; }
    public void setCybrillaBankId(String cybrillaBankId) { this.cybrillaBankId = cybrillaBankId; }
    public Integer getFpBankAccountOldId() { return fpBankAccountOldId; }
    public void setFpBankAccountOldId(Integer fpBankAccountOldId) { this.fpBankAccountOldId = fpBankAccountOldId; }
    public String getCybrillaBankVerificationId() { return cybrillaBankVerificationId; }
    public void setCybrillaBankVerificationId(String cybrillaBankVerificationId) { this.cybrillaBankVerificationId = cybrillaBankVerificationId; }
    public String getCybrillaBankVerificationStatus() { return cybrillaBankVerificationStatus; }
    public void setCybrillaBankVerificationStatus(String cybrillaBankVerificationStatus) { this.cybrillaBankVerificationStatus = cybrillaBankVerificationStatus; }
    public String getCybrillaBankVerificationConfidence() { return cybrillaBankVerificationConfidence; }
    public void setCybrillaBankVerificationConfidence(String cybrillaBankVerificationConfidence) { this.cybrillaBankVerificationConfidence = cybrillaBankVerificationConfidence; }
    public String getExternalVerificationRequestJson() { return externalVerificationRequestJson; }
    public void setExternalVerificationRequestJson(String externalVerificationRequestJson) { this.externalVerificationRequestJson = externalVerificationRequestJson; }
    public String getExternalVerificationResponseJson() { return externalVerificationResponseJson; }
    public void setExternalVerificationResponseJson(String externalVerificationResponseJson) { this.externalVerificationResponseJson = externalVerificationResponseJson; }
    public Boolean getExternalSyncPending() { return externalSyncPending; }
    public void setExternalSyncPending(Boolean externalSyncPending) { this.externalSyncPending = externalSyncPending; }
    public String getExternalSyncMessage() { return externalSyncMessage; }
    public void setExternalSyncMessage(String externalSyncMessage) { this.externalSyncMessage = externalSyncMessage; }
}
