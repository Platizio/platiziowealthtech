package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A nomination on an investor profile (REQUIREMENT #4). Each row is one nominee
 * with an allocation percentage; allocations across an investor's live nominees
 * sum to 100. The "I choose not to nominate" opt-out is NOT a nominee row — it is
 * a flag on the {@link Investor} ({@code nominationOptedOut}) plus a consent record.
 */
@Entity
@Table(name = "investor_nominees")
public class Nominee extends BaseEntity {

    @Column(nullable = false)
    private UUID investorId;

    /**
     * Position of this nominee within the investor's list. Shared, NOT-NULL column on
     * the canonical {@code investor_nominees} table with a UNIQUE (investor_id,
     * nominee_index) index; {@link com.platizio.wealthtech.service.NomineeService}
     * assigns the next free index on add so self-service rows never collide with
     * IRIS-onboarding rows.
     */
    @Column(nullable = false)
    private Integer nomineeIndex;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String relationship;

    private LocalDate dateOfBirth;

    private Integer allocationPercentage;

    private String addressLine;

    /** Guardian's name when the nominee is a minor; null otherwise. */
    private String guardianName;

    public UUID getInvestorId() { return investorId; }
    public void setInvestorId(UUID investorId) { this.investorId = investorId; }

    public Integer getNomineeIndex() { return nomineeIndex; }
    public void setNomineeIndex(Integer nomineeIndex) { this.nomineeIndex = nomineeIndex; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getRelationship() { return relationship; }
    public void setRelationship(String relationship) { this.relationship = relationship; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public Integer getAllocationPercentage() { return allocationPercentage; }
    public void setAllocationPercentage(Integer allocationPercentage) { this.allocationPercentage = allocationPercentage; }

    public String getAddressLine() { return addressLine; }
    public void setAddressLine(String addressLine) { this.addressLine = addressLine; }

    public String getGuardianName() { return guardianName; }
    public void setGuardianName(String guardianName) { this.guardianName = guardianName; }
}
