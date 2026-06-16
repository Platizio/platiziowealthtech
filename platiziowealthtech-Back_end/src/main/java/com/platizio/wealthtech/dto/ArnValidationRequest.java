package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/auth/arn-validation}. */
public record ArnValidationRequest(
        @NotBlank(message = "ARN is required") String arnNumber
) {
}
