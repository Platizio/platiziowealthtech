package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.InvestorNominee;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read view of a single nominee (REQUIREMENT #4) — the canonical rich shape shared
 * by the investor self-service and distributor/IRIS flows. {@code hasIdDocument}
 * reports whether an identity document has been uploaded for this nominee, so the
 * client can skip re-asking for it.
 */
public record NomineeResponse(
        UUID id,
        UUID investorId,
        Integer nomineeIndex,
        String fullName,
        String relationship,
        LocalDate dateOfBirth,
        BigDecimal sharePercent,
        String mobileNumber,
        String email,
        String idType,
        String idNumber,
        String addressLine1,
        String addressLine2,
        String addressLine3,
        String city,
        String state,
        String postalCode,
        String country,
        Boolean sameAsApplicant,
        String guardianName,
        boolean hasIdDocument
) {
    public static NomineeResponse from(InvestorNominee n, boolean hasIdDocument) {
        return new NomineeResponse(
                n.getId(),
                n.getInvestorId(),
                n.getNomineeIndex(),
                n.getFullName(),
                n.getRelationship(),
                n.getDateOfBirth(),
                n.getSharePercent(),
                n.getMobileNumber(),
                n.getEmail(),
                n.getIdType(),
                n.getIdNumber(),
                n.getAddressLine1(),
                n.getAddressLine2(),
                n.getAddressLine3(),
                n.getCity(),
                n.getState(),
                n.getPostalCode(),
                n.getCountry(),
                n.getSameAsApplicant(),
                n.getGuardianName(),
                hasIdDocument);
    }

    public static NomineeResponse from(InvestorNominee n) {
        return from(n, false);
    }
}
