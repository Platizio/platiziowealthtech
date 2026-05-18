package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.AuthRefreshToken;
import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.repository.AuthRefreshTokenRepository;
import com.platizio.wealthtech.repository.DistributorRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final DistributorRepository distributorRepository;
    private final long refreshTokenExpirationMs;

    public RefreshTokenService(
            AuthRefreshTokenRepository refreshTokenRepository,
            DistributorRepository distributorRepository,
            @Value("${app.auth.refresh-token-expiration-ms}") long refreshTokenExpirationMs
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.distributorRepository = distributorRepository;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
    }

    @Transactional
    public UUID createToken(UUID distributorId) {
        AuthRefreshToken refreshToken = new AuthRefreshToken();
        refreshToken.setToken(UUID.randomUUID());
        refreshToken.setDistributorId(distributorId);
        refreshToken.setExpiresAt(OffsetDateTime.now().plus(Duration.ofMillis(refreshTokenExpirationMs)));
        return refreshTokenRepository.save(refreshToken).getToken();
    }

    @Transactional
    public Distributor validate(String rawToken) {
        AuthRefreshToken refreshToken = refreshTokenRepository.findByToken(parseToken(rawToken))
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        if (refreshToken.getExpiresAt().isBefore(OffsetDateTime.now())) {
            refreshTokenRepository.delete(refreshToken);
            throw new BadCredentialsException("Refresh token expired");
        }
        return distributorRepository.findById(refreshToken.getDistributorId())
                .orElseThrow(() -> new BadCredentialsException("Refresh token owner no longer exists"));
    }

    @Transactional
    public void revoke(String rawToken) {
        try {
            refreshTokenRepository.findByToken(parseToken(rawToken))
                    .ifPresent(refreshTokenRepository::delete);
        } catch (BadCredentialsException ignored) {
            // Logout should be idempotent even when the refresh cookie is absent or malformed.
        }
    }

    private UUID parseToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BadCredentialsException("Refresh token is required");
        }
        try {
            return UUID.fromString(rawToken);
        } catch (IllegalArgumentException ex) {
            throw new BadCredentialsException("Invalid refresh token");
        }
    }
}
