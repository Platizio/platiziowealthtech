package com.platizio.wealthtech.dto;

import java.time.LocalDate;
import java.util.Map;

public record InvestorKycRequestCreateRequest(
        String name,
        String pan,
        String email,
        String mobile,
        LocalDate dateOfBirth,
        Map<String, Object> fields
) {}
