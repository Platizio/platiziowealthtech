package com.platizio.wealthtech.dto;

public record IfscLookupResponse(
        String ifscCode,
        String bankName,
        String branchName,
        String branchAddress,
        String city,
        String district,
        String state,
        String micrCode
) {}
