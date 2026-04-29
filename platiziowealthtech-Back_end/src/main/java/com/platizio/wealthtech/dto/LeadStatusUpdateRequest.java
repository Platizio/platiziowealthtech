package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.LeadStatus;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record LeadStatusUpdateRequest(
        @NotNull LeadStatus status,
        @NotNull UUID actorId,
        String notes
) {}
