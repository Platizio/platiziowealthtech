package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "investor_leads")
@SQLRestriction("is_deleted = false")
public class InvestorLead extends BaseEntity {

    @Column(nullable = false)
    private String prospectName;

    @Column(nullable = false)
    private String mobileNumber;

    private String email;
    private String city;
    private String stateName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeadSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeadStatus status = LeadStatus.NEW;

    private UUID assignedDistributorId;
    private UUID convertedInvestorId;
    private String notes;
    @Column(nullable = false)
    private Boolean isDeleted = Boolean.FALSE;
    private LocalDateTime deletedAt;

    public String getProspectName() { return prospectName; }
    public void setProspectName(String prospectName) { this.prospectName = prospectName; }
    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getStateName() { return stateName; }
    public void setStateName(String stateName) { this.stateName = stateName; }
    public LeadSource getSource() { return source; }
    public void setSource(LeadSource source) { this.source = source; }
    public LeadStatus getStatus() { return status; }
    public void setStatus(LeadStatus status) { this.status = status; }
    public UUID getAssignedDistributorId() { return assignedDistributorId; }
    public void setAssignedDistributorId(UUID assignedDistributorId) { this.assignedDistributorId = assignedDistributorId; }
    public UUID getConvertedInvestorId() { return convertedInvestorId; }
    public void setConvertedInvestorId(UUID convertedInvestorId) { this.convertedInvestorId = convertedInvestorId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Boolean getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Boolean isDeleted) { this.isDeleted = isDeleted; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
