package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record InvestorPreVerificationResponse(
        JsonNode externalResponse
) {}
