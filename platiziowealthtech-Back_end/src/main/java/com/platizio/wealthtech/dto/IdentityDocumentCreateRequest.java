package com.platizio.wealthtech.dto;

import java.util.Map;

public record IdentityDocumentCreateRequest(
        String kycRequestId,
        String type,
        String postbackUrl,
        Map<String, Object> fields
) {}
