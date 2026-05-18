package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.BlockedToken;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlockedTokenRepository extends JpaRepository<BlockedToken, String> {
    long deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
