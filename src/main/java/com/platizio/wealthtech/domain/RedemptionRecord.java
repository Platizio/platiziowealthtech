package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "redemption_records")
public class RedemptionRecord extends BaseEntity {

    @Column(nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private UUID investorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RedemptionStatus redemptionStatus = RedemptionStatus.CREATED;

    private BigDecimal units;
    private BigDecimal amount;
    private String externalRedemptionId;
    private String bankCreditReference;
    private String failureReason;

    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }
    public RedemptionStatus getRedemptionStatus() { return redemptionStatus; }
    public void setRedemptionStatus(RedemptionStatus redemptionStatus) { this.redemptionStatus = redemptionStatus; }
    public BigDecimal getUnits() { return units; }
    public void setUnits(BigDecimal units) { this.units = units; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getExternalRedemptionId() { return externalRedemptionId; }
    public void setExternalRedemptionId(String externalRedemptionId) { this.externalRedemptionId = externalRedemptionId; }
    public String getBankCreditReference() { return bankCreditReference; }
    public void setBankCreditReference(String bankCreditReference) { this.bankCreditReference = bankCreditReference; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
