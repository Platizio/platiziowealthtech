package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.LeadStatus;
import jakarta.validation.constraints.NotNull;

public record LeadStatusUpdateRequest(
        @NotNull LeadStatus status,
        String notes
) {}
