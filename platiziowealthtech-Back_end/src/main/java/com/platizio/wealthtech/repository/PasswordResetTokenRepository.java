package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.PasswordResetToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    List<PasswordResetToken> findByDistributorIdAndUsedAtIsNull(UUID distributorId);
}
