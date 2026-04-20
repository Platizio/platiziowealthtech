package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.DistributorRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public record DistributorSignupRequest(
        @NotBlank String fullName,
        @NotBlank String mobileNumber,
        @NotBlank @Email String email,
        @NotBlank String arnNumber,
        @NotBlank String nismCertificateNumber,
        @NotNull LocalDate nismExpiryDate,
        String eUinNumber,
        String bankAccountNumber,
        String bankIfsc,
        String bankAccountHolderName,
        DistributorRole role,
        UUID masterDistributorId,
        Boolean internalRm
) {}
