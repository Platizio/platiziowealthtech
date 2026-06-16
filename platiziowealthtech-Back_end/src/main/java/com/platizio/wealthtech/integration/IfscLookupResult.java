package com.platizio.wealthtech.integration;

public record IfscLookupResult(
        String ifscCode,
        String bankName,
        String branchName,
        String branchAddress,
        String city,
        String district,
        String state,
        String micrCode
) {}
