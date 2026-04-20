package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.LeadSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record LeadCreateWithDistributorRequest(
        @NotBlank String prospectName,
        @NotBlank String mobileNumber,
        String email,
        String city,
        String stateName,
        @NotNull LeadSource source,
        String notes,
        @NotNull UUID distributorId,
        @NotNull UUID actorId
) {}
