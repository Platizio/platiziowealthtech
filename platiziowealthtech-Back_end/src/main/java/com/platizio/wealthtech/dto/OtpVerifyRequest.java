package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.OtpPurpose;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OtpVerifyRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email format is invalid")
        String email,

        @NotNull(message = "Purpose is required")
        OtpPurpose purpose,

        @NotBlank(message = "Code is required")
        String code
) {}
