package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

public record InvestorKycSimulationRequest(
        @NotBlank String status
) {}
