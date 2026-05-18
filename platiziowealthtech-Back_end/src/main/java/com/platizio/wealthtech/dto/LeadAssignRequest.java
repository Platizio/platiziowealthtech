package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record LeadAssignRequest(
        @NotNull UUID distributorId
) {}
