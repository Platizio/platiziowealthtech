package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.DistributorRole;
import java.util.UUID;

public record AuthResponse(
        String token,
        UUID distributorId,
        String email,
        String fullName,
        DistributorRole role
) {}
