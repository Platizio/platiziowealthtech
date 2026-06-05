package com.platizio.wealthtech.dto;

import java.time.LocalDate;

public record InvestorKycCheckRequest(
        LocalDate dateOfBirth,
        Boolean forceNewCheck
) {}
