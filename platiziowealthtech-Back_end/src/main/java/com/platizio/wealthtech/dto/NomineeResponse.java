package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.Nominee;
import java.time.LocalDate;
import java.util.UUID;

/** Read view of a single nominee (REQUIREMENT #4). */
public record NomineeResponse(
        UUID id,
        UUID investorId,
        String fullName,
        String relationship,
        LocalDate dateOfBirth,
        Integer allocationPercentage,
        String addressLine,
        String guardianName
) {
    public static NomineeResponse from(Nominee n) {
        return new NomineeResponse(
                n.getId(),
                n.getInvestorId(),
                n.getFullName(),
                n.getRelationship(),
                n.getDateOfBirth(),
                n.getAllocationPercentage(),
                n.getAddressLine(),
                n.getGuardianName());
    }
}
