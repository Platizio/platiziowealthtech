package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.PasswordResetToken;
import com.platizio.wealthtech.dto.ForgotPasswordRequest;
import com.platizio.wealthtech.dto.ForgotPasswordResponse;
import com.platizio.wealthtech.dto.ResetPasswordRequest;
import com.platizio.wealthtech.repository.DistributorRepository;
import com.platizio.wealthtech.repository.PasswordResetTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {

    private static final String GENERIC_RESET_MESSAGE =
            "If an account exists for this email, a password reset link has been generated.";

    private final DistributorRepository distributorRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final RefreshTokenService refreshTokenService;
    private final Environment environment;
    private final long expirationMinutes;

    public PasswordResetService(
            DistributorRepository distributorRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            RefreshTokenService refreshTokenService,
            Environment environment,
            @Value("${app.auth.password-reset-expiration-minutes:15}") long expirationMinutes
    ) {
        this.distributorRepository = distributorRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.refreshTokenService = refreshTokenService;
        this.environment = environment;
        this.expirationMinutes = expirationMinutes;
    }

    @Transactional
    public ForgotPasswordResponse requestReset(ForgotPasswordRequest request) {
        String email = normalizeEmail(request.email());
        return distributorRepository.findByEmail(email)
                .map(this::createResetToken)
                .orElseGet(() -> new ForgotPasswordResponse(GENERIC_RESET_MESSAGE, null));
    }

    @Transactional
    public Map<String, String> resetPassword(ResetPasswordRequest request) {
        UUID rawToken = parseToken(request.token());
        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(sha256(rawToken.toString()))
                .orElseThrow(this::invalidToken);

        OffsetDateTime now = OffsetDateTime.now();
        if (resetToken.getUsedAt() != null || resetToken.getExpiresAt().isBefore(now)) {
            throw invalidToken();
        }

        Distributor distributor = distributorRepository.findById(resetToken.getDistributorId())
                .orElseThrow(this::invalidToken);
        distributor.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        distributorRepository.save(distributor);
        refreshTokenService.revokeAllForDistributor(distributor.getId());

        resetToken.setUsedAt(now);
        passwordResetTokenRepository.save(resetToken);
        auditService.log("DISTRIBUTOR", distributor.getId(), "PASSWORD_RESET_COMPLETED", distributor.getId(), "{}");

        return Map.of("message", "Password reset successful. You can sign in with your new password.");
    }

    private ForgotPasswordResponse createResetToken(Distributor distributor) {
        OffsetDateTime now = OffsetDateTime.now();
        passwordResetTokenRepository.findByDistributorIdAndUsedAtIsNull(distributor.getId())
                .forEach(existing -> {
                    existing.setUsedAt(now);
                    passwordResetTokenRepository.save(existing);
                });

        UUID rawToken = UUID.randomUUID();
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setTokenHash(sha256(rawToken.toString()));
        resetToken.setDistributorId(distributor.getId());
        resetToken.setExpiresAt(now.plusMinutes(expirationMinutes));
        passwordResetTokenRepository.save(resetToken);

        auditService.log("DISTRIBUTOR", distributor.getId(), "PASSWORD_RESET_REQUESTED", distributor.getId(), "{}");
        return new ForgotPasswordResponse(GENERIC_RESET_MESSAGE, isLocalProfile() ? rawToken.toString() : null);
    }

    private UUID parseToken(String rawToken) {
        try {
            return UUID.fromString(rawToken == null ? "" : rawToken.trim());
        } catch (IllegalArgumentException ex) {
            throw invalidToken();
        }
    }

    private IllegalArgumentException invalidToken() {
        return new IllegalArgumentException("Invalid or expired reset token");
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isLocalProfile() {
        return environment.acceptsProfiles(Profiles.of("local"));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
