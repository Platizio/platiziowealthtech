package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.InvestorRelationshipType;
import com.platizio.wealthtech.validation.MobileFormat;
import com.platizio.wealthtech.validation.PanFormat;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record InvestorCreateRequest(
        @NotNull UUID distributorId,
        @NotBlank String fullName,
        @NotBlank
        @Pattern(regexp = MobileFormat.INDIAN_MOBILE_REGEX, message = MobileFormat.INDIAN_MOBILE_MESSAGE)
        String mobileNumber,
        @NotBlank @Email String email,
        @NotBlank
        @Size(min = 10, max = 10, message = "PAN must be exactly 10 characters")
        @Pattern(regexp = PanFormat.INDIAN_PAN_REGEX, message = PanFormat.INDIAN_PAN_MESSAGE)
        String pan,
        LocalDate dateOfBirth,
        LocalDate anniversaryDate,
        LocalDate goalMaturityDate,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        UUID householdId,
        String householdName,
        InvestorRelationshipType relationshipType,
        UUID guardianInvestorId,
        @Size(min = 10, max = 10, message = "Guardian PAN must be exactly 10 characters")
        @Pattern(regexp = PanFormat.INDIAN_PAN_REGEX, message = PanFormat.INDIAN_PAN_MESSAGE)
        String guardianPan,
        String onboardingNotes,
        // investor.md R1/R5: when true, create a pending investor (distributor_id NULL,
        // pending_distributor_id set, linking_status PENDING_INVESTOR_APPROVAL) for the
        // "Send to Investor" flow. Defaults to false to preserve the legacy create path.
        Boolean gatedOnInvestorApproval
) {
    /**
     * Backward-compatible constructor (pre-M2 call sites): defaults the linking flag to
     * false, preserving the legacy distributor-owned create path.
     */
    public InvestorCreateRequest(
            UUID distributorId, String fullName, String mobileNumber, String email, String pan,
            LocalDate dateOfBirth, LocalDate anniversaryDate, LocalDate goalMaturityDate,
            String addressLine1, String addressLine2, String city, String state, String postalCode,
            UUID householdId, String householdName, InvestorRelationshipType relationshipType,
            UUID guardianInvestorId, String guardianPan, String onboardingNotes) {
        this(distributorId, fullName, mobileNumber, email, pan, dateOfBirth, anniversaryDate, goalMaturityDate,
                addressLine1, addressLine2, city, state, postalCode, householdId, householdName, relationshipType,
                guardianInvestorId, guardianPan, onboardingNotes, null);
    }
}
