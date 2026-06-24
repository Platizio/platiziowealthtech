package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.EmailOtp;
import com.platizio.wealthtech.domain.OtpPurpose;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.repository.EmailOtpRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and verifies email one-time passcodes for login / signup.
 *
 * <p>Security properties:
 * <ul>
 *   <li>Only the SHA-256 hash of {@code email:code} is persisted.</li>
 *   <li>Each challenge expires ({@code app.otp.expiration-minutes}).</li>
 *   <li>A bounded number of verify attempts ({@code app.otp.max-attempts})
 *       before the code is burned.</li>
 *   <li>A resend cooldown ({@code app.otp.resend-cooldown-seconds}) throttles
 *       request spam per (email, purpose).</li>
 *   <li>Requesting a new code invalidates any prior unconsumed code.</li>
 * </ul>
 */
@Service
public class OtpService {

    private static final Logger logger = LoggerFactory.getLogger(OtpService.class);
    private static final String GENERIC_SENT_MESSAGE =
            "If the email is eligible, a one-time passcode has been sent.";
    private static final String INVALID_CODE_MESSAGE =
            "Invalid or expired code. Please request a new one.";

    private final EmailOtpRepository otpRepository;
    private final EmailService emailService;
    private final SecureRandom random = new SecureRandom();

    private final int length;
    private final long expirationMinutes;
    private final int maxAttempts;
    private final long resendCooldownSeconds;

    /**
     * When true (and email delivery is disabled) the live code is echoed in the
     * API response so developers can test the flow without SMTP. Defaults to
     * {@code false}; enabled only via {@code app.otp.expose-dev-code} on the
     * local profile. It must stay {@code false} in every deployed/demo
     * environment — see DF-13.
     */
    private final boolean exposeDevCode;

    public OtpService(
            EmailOtpRepository otpRepository,
            EmailService emailService,
            @Value("${app.otp.length:6}") int length,
            @Value("${app.otp.expiration-minutes:5}") long expirationMinutes,
            @Value("${app.otp.max-attempts:5}") int maxAttempts,
            @Value("${app.otp.resend-cooldown-seconds:30}") long resendCooldownSeconds,
            @Value("${app.otp.expose-dev-code:false}") boolean exposeDevCode
    ) {
        this.otpRepository = otpRepository;
        this.emailService = emailService;
        this.length = Math.max(4, length);
        this.expirationMinutes = expirationMinutes;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.resendCooldownSeconds = resendCooldownSeconds;
        this.exposeDevCode = exposeDevCode;
    }

    @Transactional
    public OtpRequestResponse requestOtp(String rawEmail, OtpPurpose purpose) {
        return requestOtp(rawEmail, purpose, null);
    }

    /**
     * Reference-scoped variant. When {@code referenceId} is non-null, the cooldown,
     * invalidation and storage are all scoped to {@code (email, purpose, referenceId)}
     * so a second concurrent challenge for the same email+purpose cannot invalidate
     * or be confused with this one. {@code null} reproduces the legacy login/signup
     * behaviour (purpose alone isolates those flows).
     */
    @Transactional
    public OtpRequestResponse requestOtp(String rawEmail, OtpPurpose purpose, UUID referenceId) {
        String email = normalizeEmail(rawEmail);
        if (!isValidEmail(email)) {
            throw new IllegalArgumentException("Please enter a valid email address.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<EmailOtp> active = referenceId == null
                ? otpRepository.findByEmailAndPurposeAndConsumedAtIsNull(email, purpose)
                : otpRepository.findByEmailAndPurposeAndReferenceIdAndConsumedAtIsNull(email, purpose, referenceId);

        // Resend cooldown: block if the most recent code is still within the window.
        active.stream()
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .ifPresent(latest -> {
                    long sinceSeconds = ChronoUnit.SECONDS.between(latest.getCreatedAt(), now);
                    if (sinceSeconds < resendCooldownSeconds) {
                        long wait = resendCooldownSeconds - sinceSeconds;
                        throw new IllegalArgumentException(
                                "Please wait " + wait + " second" + (wait == 1 ? "" : "s")
                                        + " before requesting another code.");
                    }
                });

        // Invalidate any earlier unconsumed codes so only one is ever live.
        active.forEach(existing -> existing.setConsumedAt(now));
        otpRepository.saveAll(active);

        String code = generateCode();
        EmailOtp otp = new EmailOtp();
        otp.setEmail(email);
        otp.setPurpose(purpose);
        otp.setReferenceId(referenceId);
        otp.setCodeHash(hash(email, code));
        otp.setExpiresAt(now.plusMinutes(expirationMinutes));
        otp.setAttempts(0);
        otpRepository.save(otp);

        boolean delivered = deliver(email, code, purpose);
        if (!delivered) {
            logger.info("OTP for {} (purpose={}) is {} (email delivery disabled)", email, purpose, code);
        }

        String devCode = (!delivered && exposeDevCode) ? code : null;
        return new OtpRequestResponse(
                GENERIC_SENT_MESSAGE,
                expirationMinutes * 60,
                resendCooldownSeconds,
                devCode);
    }

    /**
     * Verifies a submitted code. Throws {@link BadCredentialsException} (HTTP
     * 401) on any failure; returns normally on success after burning the code.
     */
    @Transactional
    public void verify(String rawEmail, OtpPurpose purpose, String rawCode) {
        verify(rawEmail, purpose, rawCode, null);
    }

    /**
     * Reference-scoped verify. When {@code referenceId} is non-null only the code
     * bound to that reference can match — a sibling challenge's code (same
     * email+purpose, different reference) is never returned, closing cross-consume.
     */
    @Transactional
    public void verify(String rawEmail, OtpPurpose purpose, String rawCode, UUID referenceId) {
        String email = normalizeEmail(rawEmail);
        String code = rawCode == null ? "" : rawCode.trim();

        EmailOtp otp = (referenceId == null
                ? otpRepository.findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(email, purpose)
                : otpRepository.findFirstByEmailAndPurposeAndReferenceIdAndConsumedAtIsNullOrderByCreatedAtDesc(
                        email, purpose, referenceId))
                .orElseThrow(() -> new BadCredentialsException(INVALID_CODE_MESSAGE));

        OffsetDateTime now = OffsetDateTime.now();
        if (otp.getExpiresAt().isBefore(now)) {
            otp.setConsumedAt(now);
            otpRepository.save(otp);
            throw new BadCredentialsException(INVALID_CODE_MESSAGE);
        }
        if (otp.getAttempts() >= maxAttempts) {
            otp.setConsumedAt(now);
            otpRepository.save(otp);
            throw new BadCredentialsException("Too many incorrect attempts. Please request a new code.");
        }

        if (!constantTimeEquals(otp.getCodeHash(), hash(email, code))) {
            otp.setAttempts(otp.getAttempts() + 1);
            if (otp.getAttempts() >= maxAttempts) {
                otp.setConsumedAt(now);
            }
            otpRepository.save(otp);
            throw new BadCredentialsException(INVALID_CODE_MESSAGE);
        }

        otp.setConsumedAt(now);
        otpRepository.save(otp);
    }

    private boolean deliver(String email, String code, OtpPurpose purpose) {
        String subject = "Your Platizio verification code: " + code;
        String html = buildEmailBody(code, purpose);
        return emailService.sendHtml(email, subject, html);
    }

    private String buildEmailBody(String code, OtpPurpose purpose) {
        boolean signup = purpose == OtpPurpose.SIGNUP || purpose == OtpPurpose.INVESTOR_SIGNUP;
        String action;
        if (purpose == OtpPurpose.TRANSACTION_APPROVAL) {
            action = "approve your transaction";
        } else if (signup) {
            action = "complete your sign up";
        } else {
            action = "sign in";
        }
        return """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:auto;color:#0B1B3E">
                  <h2 style="margin-bottom:4px">Platizio</h2>
                  <p style="color:#475569;font-size:14px">Use the code below to %s. It expires in %d minutes.</p>
                  <div style="font-size:32px;font-weight:700;letter-spacing:8px;background:#F1F5F9;
                              border-radius:12px;padding:16px;text-align:center;margin:16px 0">%s</div>
                  <p style="color:#94a3b8;font-size:12px">If you didn't request this, you can safely ignore this email.</p>
                </div>
                """.formatted(action, expirationMinutes, code);
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, length);
        int value = random.nextInt(bound);
        return String.format("%0" + length + "d", value);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isValidEmail(String email) {
        return email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private String hash(String email, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((email + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * Generic response used when an email is not eligible for a code (e.g. a
     * LOGIN request for an address that isn't registered). Shaped identically
     * to a real send so the caller cannot distinguish the two.
     */
    public OtpRequestResponse genericResponse() {
        return new OtpRequestResponse(
                GENERIC_SENT_MESSAGE, expirationMinutes * 60, resendCooldownSeconds, null);
    }

    /** Convenience for tests / callers that want the latest live challenge. */
    Optional<EmailOtp> latestActive(String email, OtpPurpose purpose) {
        return otpRepository.findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                normalizeEmail(email), purpose);
    }
}
