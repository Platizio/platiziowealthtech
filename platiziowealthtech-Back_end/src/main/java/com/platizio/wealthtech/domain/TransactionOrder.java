package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "transaction_orders")
@SQLRestriction("is_deleted = false")
public class TransactionOrder extends BaseEntity {

    @Column(nullable = false)
    private UUID investorId;

    @Column(nullable = false)
    private UUID distributorId;

    @Column(nullable = false)
    private UUID productSchemeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus orderStatus = OrderStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    private ProductCategory productCategory;

    private BigDecimal amount;
    private BigDecimal units;
    private String paymentMode;
    private String mandateMode;
    private String externalOrderId;
    private String failureReason;
    private String investorActionUrl;
    private String sipFrequency;
    private LocalDate sipStartDate;
    private Integer sipInstalments;
    @Column(nullable = false)
    private Boolean isDeleted = Boolean.FALSE;
    private LocalDateTime deletedAt;

    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }
    public UUID getDistributorId() { return distributorId; }
    public void setDistributorId(UUID distributorId) { this.distributorId = distributorId; }
    public UUID getProductSchemeId() { return productSchemeId; }
    public void setProductSchemeId(UUID productSchemeId) { this.productSchemeId = productSchemeId; }
    public TransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(TransactionType transactionType) { this.transactionType = transactionType; }
    public OrderStatus getOrderStatus() { return orderStatus; }
    public void setOrderStatus(OrderStatus orderStatus) { this.orderStatus = orderStatus; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getUnits() { return units; }
    public void setUnits(BigDecimal units) { this.units = units; }
    public String getPaymentMode() { return paymentMode; }
    public void setPaymentMode(String paymentMode) { this.paymentMode = paymentMode; }
    public String getMandateMode() { return mandateMode; }
    public void setMandateMode(String mandateMode) { this.mandateMode = mandateMode; }
    public String getExternalOrderId() { return externalOrderId; }
    public void setExternalOrderId(String externalOrderId) { this.externalOrderId = externalOrderId; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getInvestorActionUrl() { return investorActionUrl; }
    public void setInvestorActionUrl(String investorActionUrl) { this.investorActionUrl = investorActionUrl; }
    public String getSipFrequency() { return sipFrequency; }
    public void setSipFrequency(String sipFrequency) { this.sipFrequency = sipFrequency; }
    public LocalDate getSipStartDate() { return sipStartDate; }
    public void setSipStartDate(LocalDate sipStartDate) { this.sipStartDate = sipStartDate; }
    public Integer getSipInstalments() { return sipInstalments; }
    public void setSipInstalments(Integer sipInstalments) { this.sipInstalments = sipInstalments; }

    public ProductCategory getProductCategory() { return productCategory; }
    public void setProductCategory(ProductCategory productCategory) { this.productCategory = productCategory; }
    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean isDeleted) { this.isDeleted = isDeleted; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
