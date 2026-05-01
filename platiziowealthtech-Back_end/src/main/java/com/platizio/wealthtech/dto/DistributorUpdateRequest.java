package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.Email;
import java.time.LocalDate;

public record DistributorUpdateRequest(
        String fullName,
        String mobileNumber,
        @Email String email,
        String nismCertificateNumber,
        LocalDate nismExpiryDate,
        LocalDate arnExpiryDate,
        String eUinNumber,
        String bankAccountNumber,
        String bankIfsc,
        String bankAccountHolderName
) {}
