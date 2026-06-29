package com.platizio.wealthtech.service;

import com.platizio.wealthtech.common.AccountNotApprovedException;
import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.DistributorStatus;
import com.platizio.wealthtech.domain.OtpPurpose;
import com.platizio.wealthtech.dto.AuthLoginRequest;
import com.platizio.wealthtech.dto.AuthResponse;
import com.platizio.wealthtech.dto.AuthSignupRequest;
import com.platizio.wealthtech.dto.DistributorVerificationStatusResponse;
import com.platizio.wealthtech.integration.arn.ArnValidationClient.ArnValidationResult;
import com.platizio.wealthtech.repository.DistributorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    public record RefreshResult(AuthResponse authResponse, UUID refreshToken) {}

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final DistributorRepository distributorRepository;
    private final DistributorService distributorService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final RefreshTokenService refreshTokenService;
    private final BlockedTokenService blockedTokenService;
    private final ArnValidationService arnValidationService;

    public AuthService(
            DistributorRepository distributorRepository,
            DistributorService distributorService,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            RefreshTokenService refreshTokenService,
            BlockedTokenService blockedTokenService,
            ArnValidationService arnValidationService
    ) {
        this.distributorRepository = distributorRepository;
        this.distributorService = distributorService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.refreshTokenService = refreshTokenService;
        this.blockedTokenService = blockedTokenService;
        this.arnValidationService = arnValidationService;
    }

    @Transactional
    public AuthResponse signup(AuthSignupRequest request) {
        if (distributorRepository.existsByArnNumber(request.arnNumber())) {
            // Prevent duplicate signup for the same ARN. Kept as a 400 field error (existing behavior
            // the signup form maps to the ARN field) rather than a 409 to avoid changing the FE contract.
            throw new IllegalArgumentException("Distributor with same ARN already exists");
        }
        if (distributorRepository.findByEmail(request.email()).isPresent()) {
            throw new IllegalArgumentException("Email already registered");
        }

        // Know-Your-Distributor: validate the ARN with the configured provider BEFORE creating the
        // account. Only a VERIFIED ARN may proceed; expired / KYD-incomplete / unrecognised ARNs are
        // rejected with a clear message. Provider-unreachable surfaces as 503 from the client.
        ArnValidationResult arn = arnValidationService.validate(request.arnNumber());
        requireVerifiedArn(arn);

        DistributorRole role = request.role() == null ? DistributorRole.SUB_DISTRIBUTOR : request.role();

        Distributor distributor = new Distributor();
        distributor.setFullName(request.fullName());
        distributor.setMobileNumber(request.mobileNumber());
        distributor.setEmail(request.email());
        distributor.setArnNumber(arn.arnNumber() != null ? arn.arnNumber() : request.arnNumber());
        distributor.setNismCertificateNumber(request.nismCertificateNumber());
        distributor.setNismExpiryDate(request.nismExpiryDate());
        distributor.seteUinNumber(request.eUinNumber());
        distributor.setBankAccountNumber(request.bankAccountNumber());
        distributor.setBankIfsc(request.bankIfsc());
        distributor.setBankAccountHolderName(request.bankAccountHolderName());
        distributor.setRole(role);
        distributor.setMasterDistributorId(request.masterDistributorId());
        distributor.setInternalRm(Boolean.TRUE.equals(request.internalRm()));
        distributor.setStatus(DistributorStatus.PENDING_APPROVAL);
        distributor.setProfileCompletionPercent(85);
        distributor.setPasswordHash(passwordEncoder.encode(request.password()));
        applyArnValidation(distributor, arn);

        Distributor saved = distributorRepository.save(distributor);
        auditService.log("DISTRIBUTOR", saved.getId(), "SIGNUP_SUBMITTED", saved.getId(),
                "{\"status\":\"PENDING_APPROVAL\",\"arnValidationStatus\":\"" + arn.status() + "\"}");

        return new AuthResponse(
                null,
                saved.getId(),
                saved.getEmail(),
                saved.getFullName(),
                saved.getRole(),
                saved.getStatus(),
                "Approval remaining. Your account is pending admin approval."
        );
    }

    /** Rejects signup unless the ARN validation verdict is VERIFIED, with a clear, status-specific message. */
    private void requireVerifiedArn(ArnValidationResult arn) {
        switch (arn.status()) {
            case VERIFIED -> { /* allowed to proceed */ }
            case EXPIRED -> throw new IllegalArgumentException(arn.message() != null
                    ? arn.message()
                    : "Your ARN registration has expired. Please renew it with AMFI and try again.");
            case KYD_INCOMPLETE -> throw new IllegalArgumentException(arn.message() != null
                    ? arn.message()
                    : "Your ARN is valid but KYD is incomplete. Complete KYD before signing up.");
            case REJECTED, PENDING_VERIFICATION -> throw new IllegalArgumentException(arn.message() != null
                    ? arn.message()
                    : "ARN could not be verified. Please check the ARN and try again.");
        }
    }

    /** Persists the ARN/KYD validation outcome on the distributor record at signup time. */
    private void applyArnValidation(Distributor distributor, ArnValidationResult arn) {
        distributor.setArnValidationStatus(arn.status());
        distributor.setArnValidatedAt(LocalDateTime.now());
        distributor.setArnValidationSource(arn.source());
        distributor.setKydStatus(arn.kydStatus());
        if (arn.distributorName() != null) {
            distributor.setArnHolderName(arn.distributorName());
        }
        if (arn.firmName() != null) {
            distributor.setFirmName(arn.firmName());
        }
        if (arn.arnExpiryDate() != null) {
            distributor.setArnExpiryDate(arn.arnExpiryDate());
        }
        logger.info(
                "distributor_signup status='arn_validation_applied' arn='{}' arn_status='{}' source='{}'",
                distributor.getArnNumber(), arn.status(), arn.source());
    }

    /** ARN/KYD verdict + account approval state for the authenticated distributor (by email). */
    @Transactional(readOnly = true)
    public DistributorVerificationStatusResponse verificationStatus(String email) {
        Distributor distributor = distributorRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Not authenticated"));
        return new DistributorVerificationStatusResponse(
                distributor.getId(),
                distributor.getArnNumber(),
                distributor.getArnValidationStatus(),
                distributor.getStatus(),
                distributor.getArnExpiryDate(),
                distributor.getKydStatus(),
                distributor.getFirmName(),
                distributor.getArnHolderName(),
                distributor.getArnValidationSource(),
                distributor.getArnValidatedAt()
        );
    }

    public AuthResponse login(AuthLoginRequest request) {
        Distributor distributor = distributorRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (distributor.getPasswordHash() == null ||
                !passwordEncoder.matches(request.password(), distributor.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        if (distributor.getStatus() != DistributorStatus.APPROVED) {
            throw new AccountNotApprovedException(distributor.getStatus());
        }

        String token = jwtService.generateToken(
                distributor.getId(), distributor.getEmail(), distributor.getRole().name());
        return new AuthResponse(
                token, distributor.getId(), distributor.getEmail(),
                distributor.getFullName(), distributor.getRole(), distributor.getStatus(), "Login successful");
    }

    /**
     * Decides whether an OTP should actually be generated for an email, without
     * revealing account existence to the caller (anti-enumeration): for LOGIN
     * the account must exist; for SIGNUP it must NOT already exist. The endpoint
     * returns the same generic response either way.
     */
    public boolean isOtpEligible(String email, OtpPurpose purpose) {
        boolean registered = distributorRepository.findByEmail(normalizeEmail(email)).isPresent();
        return switch (purpose) {
            case LOGIN -> registered;
            case SIGNUP -> !registered;
            // Investor-portal, transaction-approval, and profile-change purposes are handled
            // elsewhere (never via the distributor isOtpEligible path).
            case INVESTOR_LOGIN, INVESTOR_SIGNUP, TRANSACTION_APPROVAL, PROFILE_APPROVAL, PROFILE_CHANGE_APPROVAL -> false;
        };
    }

    /**
     * Passwordless login after a verified email OTP. Mirrors {@link #login} but
     * skips the password check (the OTP already proved control of the inbox).
     */
    public AuthResponse otpLogin(String email) {
        Distributor distributor = distributorRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or code"));

        if (distributor.getStatus() != DistributorStatus.APPROVED) {
            throw new AccountNotApprovedException(distributor.getStatus());
        }

        String token = jwtService.generateToken(
                distributor.getId(), distributor.getEmail(), distributor.getRole().name());
        return new AuthResponse(
                token, distributor.getId(), distributor.getEmail(),
                distributor.getFullName(), distributor.getRole(), distributor.getStatus(), "Login successful");
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public UUID createRefreshToken(UUID distributorId) {
        return refreshTokenService.createToken(distributorId);
    }

    public RefreshResult refresh(String refreshToken) {
        RefreshTokenService.RotatedRefreshToken rotatedRefreshToken = refreshTokenService.rotate(refreshToken);
        Distributor distributor = rotatedRefreshToken.distributor();
        if (distributor.getStatus() != DistributorStatus.APPROVED) {
            throw new AccountNotApprovedException(distributor.getStatus());
        }
        String token = jwtService.generateToken(
                distributor.getId(), distributor.getEmail(), distributor.getRole().name());
        AuthResponse response = new AuthResponse(
                token, distributor.getId(), distributor.getEmail(),
                distributor.getFullName(), distributor.getRole(), distributor.getStatus(), "Token refreshed");
        return new RefreshResult(response, rotatedRefreshToken.refreshToken());
    }

    public AuthResponse refreshAccessToken(String refreshToken) {
        return refresh(refreshToken).authResponse();
    }

    public void revokeRefreshToken(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    public void blockAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank() || !jwtService.isTokenValid(accessToken)) {
            return;
        }
        blockedTokenService.block(jwtService.extractJti(accessToken), jwtService.extractExpiresAt(accessToken));
    }

    public long purgeExpiredBlockedTokens() {
        return blockedTokenService.purgeExpired();
    }

    public AuthResponse currentUser(String email) {
        Distributor distributor = distributorRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Authenticated user no longer exists"));
        return new AuthResponse(
                null,
                distributor.getId(),
                distributor.getEmail(),
                distributor.getFullName(),
                distributor.getRole(),
                distributor.getStatus(),
                "Authenticated"
        );
    }
}
