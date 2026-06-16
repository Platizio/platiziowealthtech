package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.AuthRefreshToken;
import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.repository.AuthRefreshTokenRepository;
import com.platizio.wealthtech.repository.DistributorRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    public record RotatedRefreshToken(Distributor distributor, UUID refreshToken) {}

    private static final Duration ROTATION_GRACE_PERIOD = Duration.ofSeconds(60);

    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final DistributorRepository distributorRepository;
    private final long refreshTokenExpirationMs;
    private final ConcurrentHashMap<String, CachedRotation> recentRotations = new ConcurrentHashMap<>();

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
        UUID rawToken = UUID.randomUUID();
        AuthRefreshToken refreshToken = new AuthRefreshToken();
        refreshToken.setTokenHash(sha256(rawToken.toString()));
        refreshToken.setDistributorId(distributorId);
        refreshToken.setRevoked(false);
        refreshToken.setExpiresAt(OffsetDateTime.now().plus(Duration.ofMillis(refreshTokenExpirationMs)));
        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    @Transactional
    public RotatedRefreshToken rotate(String rawToken) {
        purgeExpiredRotations();
        String tokenHash = sha256(parseToken(rawToken).toString());
        CachedRotation cached = recentRotations.get(tokenHash);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            Distributor distributor = distributorRepository.findById(cached.distributorId())
                    .orElseThrow(() -> new BadCredentialsException("Refresh token owner no longer exists"));
            return new RotatedRefreshToken(distributor, cached.newRawToken());
        }

        AuthRefreshToken refreshToken = findUsableToken(rawToken);
        Distributor distributor = distributorRepository.findById(refreshToken.getDistributorId())
                .orElseThrow(() -> new BadCredentialsException("Refresh token owner no longer exists"));

        revoke(refreshToken);
        UUID newRawToken = createToken(refreshToken.getDistributorId());
        recentRotations.put(
                tokenHash,
                new CachedRotation(newRawToken, refreshToken.getDistributorId(), Instant.now().plus(ROTATION_GRACE_PERIOD))
        );
        return new RotatedRefreshToken(distributor, newRawToken);
    }

    @Transactional
    public Distributor validate(String rawToken) {
        AuthRefreshToken refreshToken = findUsableToken(rawToken);
        return distributorRepository.findById(refreshToken.getDistributorId())
                .orElseThrow(() -> new BadCredentialsException("Refresh token owner no longer exists"));
    }

    @Transactional
    public void revoke(String rawToken) {
        try {
            refreshTokenRepository.findByTokenHash(sha256(parseToken(rawToken).toString()))
                    .ifPresent(this::revoke);
        } catch (BadCredentialsException ignored) {
            // Logout should be idempotent even when the refresh cookie is absent or malformed.
        }
    }

    @Transactional
    public void revokeAllForDistributor(UUID distributorId) {
        if (distributorId == null) {
            return;
        }
        List<AuthRefreshToken> activeTokens = refreshTokenRepository.findByDistributorIdAndRevokedFalse(distributorId);
        activeTokens.forEach(this::revoke);
    }

    private AuthRefreshToken findUsableToken(String rawToken) {
        AuthRefreshToken refreshToken = refreshTokenRepository.findByTokenHash(sha256(parseToken(rawToken).toString()))
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        if (Boolean.TRUE.equals(refreshToken.getRevoked())) {
            throw new BadCredentialsException("Refresh token revoked");
        }
        if (refreshToken.getExpiresAt().isBefore(OffsetDateTime.now())) {
            revoke(refreshToken);
            throw new BadCredentialsException("Refresh token expired");
        }
        return refreshToken;
    }

    private void revoke(AuthRefreshToken refreshToken) {
        refreshToken.setRevoked(true);
        refreshToken.setRevokedAt(OffsetDateTime.now());
        refreshTokenRepository.save(refreshToken);
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

    private void purgeExpiredRotations() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, CachedRotation>> iterator = recentRotations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedRotation> entry = iterator.next();
            if (entry.getValue().expiresAt().isBefore(now)) {
                iterator.remove();
            }
        }
    }

    private record CachedRotation(UUID newRawToken, UUID distributorId, Instant expiresAt) {}

    String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
