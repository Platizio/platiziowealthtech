package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.AuthRefreshToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshToken, UUID> {
    Optional<AuthRefreshToken> findByTokenHash(String tokenHash);

    List<AuthRefreshToken> findByDistributorIdAndRevokedFalse(UUID distributorId);
}
