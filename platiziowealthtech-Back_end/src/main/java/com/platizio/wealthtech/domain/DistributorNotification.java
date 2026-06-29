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
 * A lightweight in-app notification to a distributor (investor.md R8): investor
 * approved, form submitted, form skipped, profile-change approved, or link rejected.
 */
@Entity
@Table(name = "distributor_notifications")
public class DistributorNotification extends BaseEntity {

    @Column(nullable = false)
    private UUID distributorId;

    private UUID investorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private DistributorNotificationType type;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 1000)
    private String body;

    private OffsetDateTime readAt;

    public UUID getDistributorId() { return distributorId; }
    public void setDistributorId(UUID distributorId) { this.distributorId = distributorId; }

    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }

    public DistributorNotificationType getType() { return type; }
    public void setType(DistributorNotificationType type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public OffsetDateTime getReadAt() { return readAt; }
    public void setReadAt(OffsetDateTime readAt) { this.readAt = readAt; }
}
