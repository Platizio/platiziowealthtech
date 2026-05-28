package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "life_event_reminders",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_life_event_reminders_investor_event",
                columnNames = {"investor_id", "event_type", "event_date"}),
        indexes = @Index(
                name = "idx_life_event_reminders_dashboard",
                columnList = "distributor_id, status, event_date"))
public class LifeEventReminder extends BaseEntity {

    @Column(nullable = false)
    private UUID distributorId;

    @Column(nullable = false)
    private UUID investorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LifeEventType eventType;

    @Column(nullable = false)
    private LocalDate eventDate;

    @Column(nullable = false)
    private LocalDate reminderDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LifeEventReminderStatus status = LifeEventReminderStatus.OPEN;

    @Column(nullable = false, length = 250)
    private String title;

    @Column(nullable = false, length = 1000)
    private String message;

    public UUID getDistributorId() {
        return distributorId;
    }

    public void setDistributorId(UUID distributorId) {
        this.distributorId = distributorId;
    }

    public UUID getInvestorId() {
        return investorId;
    }

    public void setInvestorId(UUID investorId) {
        this.investorId = investorId;
    }

    public LifeEventType getEventType() {
        return eventType;
    }

    public void setEventType(LifeEventType eventType) {
        this.eventType = eventType;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public void setEventDate(LocalDate eventDate) {
        this.eventDate = eventDate;
    }

    public LocalDate getReminderDate() {
        return reminderDate;
    }

    public void setReminderDate(LocalDate reminderDate) {
        this.reminderDate = reminderDate;
    }

    public LifeEventReminderStatus getStatus() {
        return status;
    }

    public void setStatus(LifeEventReminderStatus status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
