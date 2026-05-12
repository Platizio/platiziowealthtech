package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.DistributorStatus;
import java.util.UUID;

public record AuthResponse(
        @JsonIgnore
        String token,
        UUID distributorId,
        String email,
        String fullName,
        DistributorRole role,
        DistributorStatus status,
        String message
) {}
