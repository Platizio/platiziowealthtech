package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.validation.MobileFormat;
import com.platizio.wealthtech.validation.PanFormat;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Distributor "Send to Investor" (investor.md §3 T1, §6.1, R1/R2): the Step-1 basic
 * identity the distributor entered, plus the frozen wizard {@code payloadJson} the
 * investor will review. The investor is parked PENDING (distributor_id NOT linked — R5)
 * and the snapshot is sent for approval. PAN is the sole linking identifier (R4).
 */
public record SendToInvestorRequest(
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
        /** The frozen Step-1 wizard payload the investor reviews before approving. */
        @NotBlank String payloadJson
) {}
