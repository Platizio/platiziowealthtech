package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.domain.Investor;

public record InvestorExternalKycResponse(
        Investor investor,
        JsonNode externalResponse
) {}
