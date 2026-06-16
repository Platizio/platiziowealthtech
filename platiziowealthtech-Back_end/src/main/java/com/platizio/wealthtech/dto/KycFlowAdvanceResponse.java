package com.platizio.wealthtech.dto;

public record KycFlowAdvanceResponse(
        KycFlowStatusResponse status,
        Object stepResult,
        String message
) {}
