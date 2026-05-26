package com.platizio.wealthtech.dto;

import java.util.Map;

public record InvestorKycRequestUpdateRequest(
        Map<String, Object> fields
) {}
