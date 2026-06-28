package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.DistributorRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.UUID;

public record AuthSignupRequest(
        @NotBlank String fullName,
        @NotBlank String mobileNumber,
        @NotBlank @Email String email,
        @NotBlank String password,
        // ARN / NISM are now collected later inside the dashboard (registration is basic-identity only),
        // so they are optional at signup.
        String arnNumber,
        String nismCertificateNumber,
        LocalDate nismExpiryDate,
        String eUinNumber,
        String bankAccountNumber,
        String bankIfsc,
        String bankAccountHolderName,
        DistributorRole role,
        UUID masterDistributorId,
        Boolean internalRm
) {}
