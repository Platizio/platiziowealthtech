package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "lead_interactions")
public class LeadInteraction extends BaseEntity {

    @Column(nullable = false)
    private UUID leadId;

    @Column(nullable = false)
    private UUID distributorId;

    @Column(nullable = false, length = 1000)
    private String commentText;

    private String interactionType;

    public UUID getLeadId() { return leadId; }
    public void setLeadId(UUID leadId) { this.leadId = leadId; }
    public UUID getDistributorId() { return distributorId; }
    public void setDistributorId(UUID distributorId) { this.distributorId = distributorId; }
    public String getCommentText() { return commentText; }
    public void setCommentText(String commentText) { this.commentText = commentText; }
    public String getInteractionType() { return interactionType; }
    public void setInteractionType(String interactionType) { this.interactionType = interactionType; }
}
