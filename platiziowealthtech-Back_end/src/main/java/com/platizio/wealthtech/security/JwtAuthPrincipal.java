package com.platizio.wealthtech.security;

import com.platizio.wealthtech.domain.DistributorRole;
import java.util.UUID;

public interface JwtAuthPrincipal {

    UUID getDistributorId();

    DistributorRole getRole();
}
