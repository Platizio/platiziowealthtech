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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BankVerificationStatus verificationStatus = BankVerificationStatus.NOT_CAPTURED;

    private String cybrillaBankId;

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
    public BankVerificationStatus getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(BankVerificationStatus verificationStatus) { this.verificationStatus = verificationStatus; }
    public String getCybrillaBankId() { return cybrillaBankId; }
    public void setCybrillaBankId(String cybrillaBankId) { this.cybrillaBankId = cybrillaBankId; }
}
