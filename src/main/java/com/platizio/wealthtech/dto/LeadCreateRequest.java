package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.LeadSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LeadCreateRequest(
        @NotBlank String prospectName,
        @NotBlank String mobileNumber,
        String email,
        String city,
        String stateName,
        @NotNull LeadSource source,
        String notes
) {}