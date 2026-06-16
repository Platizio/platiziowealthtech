package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

public record InvestorBankRequest(
        @NotBlank String accountHolderName,
        @NotBlank String accountNumber,
        @NotBlank String ifscCode,
        String bankName,
        String branchName,
        /** POA-supported values: savings, current, nre_savings, nro_savings */
        String accountType
) {}