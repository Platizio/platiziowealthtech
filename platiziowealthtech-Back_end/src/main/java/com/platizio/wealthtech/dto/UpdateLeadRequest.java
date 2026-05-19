package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.LeadStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateLeadRequest(
        @NotBlank String name,
        @Email String email,
        @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "phone must contain 10 to 15 digits, with an optional leading +")
        String phone,
        String notes,
        LeadStatus status
) {}
