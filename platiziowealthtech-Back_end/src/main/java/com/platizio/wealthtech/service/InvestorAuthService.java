package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.InvestorAccountStatus;
import com.platizio.wealthtech.domain.OtpPurpose;
import com.platizio.wealthtech.dto.InvestorSignupRequest;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.repository.InvestorAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.validation.MobileFormat;
import com.platizio.wealthtech.validation.PanFormat;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passwordless investor-portal auth (SRS FR-AUTH). Signup collects only
 * name/PAN/email/mobile; the email OTP proves the inbox; the ownership
 * declaration + T&C are recorded as immutable consent; the account activates on
 * email verification + declaration (mobile stays pending while SMS is dormant).
 * Login is email OTP. Reuses {@link OtpService} (email), {@link JwtService},
 * {@link TermsAcceptanceService}, {@link ConsentRecordService}.
 */
@Service
public class InvestorAuthService {

    public static final String CONSENT_TNC = "investor_tnc";
    public static final String CONSENT_EMAIL_OWNERSHIP = "contact_ownership_email";

    // Placeholder canonical wording — compliance finalizes exact text (OD-02).
    static final String EMAIL_OWNERSHIP_TEXT = "I confirm this email address belongs to the investor.";
    static final String TNC_TEXT =
            "The investor has read and accepted the Platizio Terms & Conditions, the schedule of charges, "
                    + "the risk disclosures, and the privacy policy.";

    private final InvestorAccountRepository accountRepository;
    private final InvestorRepository investorRepository;
    private final OtpService otpService;
    private final JwtService jwtService;
    private final TermsAcceptanceService termsAcceptanceService;
    private final ConsentRecordService consentRecordService;

    public InvestorAuthService(
            InvestorAccountRepository accountRepository,
            InvestorRepository investorRepository,
            OtpService otpService,
            JwtService jwtService,
            TermsAcceptanceService termsAcceptanceService,
            ConsentRecordService consentRecordService) {
        this.accountRepository = accountRepository;
        this.investorRepository = investorRepository;
        this.otpService = otpService;
        this.jwtService = jwtService;
        this.termsAcceptanceService = termsAcceptanceService;
        this.consentRecordService = consentRecordService;
    }

    public record InvestorAuthResult(String token, InvestorAccount account) {}

    /** Issue an investor OTP only when eligible; otherwise a generic (anti-enumeration) response. */
    public OtpRequestResponse requestOtp(String email, OtpPurpose purpose) {
        requireInvestorPurpose(purpose);
        return isOtpEligible(email, purpose)
                ? otpService.requestOtp(email, purpose)
                : otpService.genericResponse();
    }

    boolean isOtpEligible(String email, OtpPurpose purpose) {
        Optional<InvestorAccount> existing = accountRepository.findByEmailIgnoreCase(normalizeEmail(email));
        return switch (purpose) {
            case INVESTOR_LOGIN -> existing.filter(a -> a.getStatus() == InvestorAccountStatus.ACTIVE).isPresent();
            case INVESTOR_SIGNUP -> existing.isEmpty();
            default -> false;
        };
    }

    @Transactional
    public InvestorAuthResult signup(InvestorSignupRequest request, String ip, String userAgent) {
        String email = normalizeEmail(request.email());
        String pan = PanFormat.normalize(request.pan());
        String mobile = MobileFormat.normalize(request.mobileNumber());

        if (accountRepository.existsByEmailIgnoreCase(email) || accountRepository.existsByPan(pan)) {
            throw new IllegalArgumentException("An investor account already exists for this email or PAN.");
        }
        // Consumes the OTP; throws BadCredentialsException on a wrong/expired code.
        otpService.verify(email, OtpPurpose.INVESTOR_SIGNUP, request.emailOtp());
        if (!request.ownershipDeclarationAccepted() || !request.tncAccepted()) {
            throw new IllegalArgumentException("The required declarations were not accepted.");
        }

        InvestorAccount account = new InvestorAccount();
        account.setFullName(request.fullName().trim());
        account.setPan(pan);
        account.setEmail(email);
        account.setMobileNumber(mobile);
        account.setEmailVerified(Boolean.TRUE);   // proven by the OTP just consumed
        account.setMobileVerified(Boolean.FALSE);  // SMS dormant; verified later
        account.setStatus(InvestorAccountStatus.ACTIVE);
        account.setActivatedAt(OffsetDateTime.now());
        // FR-AUTH-002: claim a distributor-created draft on matching PAN + verified email.
        investorRepository.findByPan(pan).ifPresent(inv -> account.setInvestorId(inv.getId()));
        InvestorAccount saved = accountRepository.save(account);

        // Immutable consent evidence.
        termsAcceptanceService.record(
                TermsAcceptanceService.SUBJECT_INVESTOR, saved.getId(), CONSENT_TNC, request.tncVersion(),
                ip, userAgent, saved.getId());
        consentRecordService.record(
                ConsentRecordService.SUBJECT_INVESTOR, saved.getId(), CONSENT_TNC, request.tncVersion(),
                TNC_TEXT, ip, userAgent);
        consentRecordService.record(
                ConsentRecordService.SUBJECT_INVESTOR, saved.getId(), CONSENT_EMAIL_OWNERSHIP, "v1.0",
                EMAIL_OWNERSHIP_TEXT, ip, userAgent);

        return new InvestorAuthResult(jwtService.generateInvestorToken(saved.getId(), saved.getEmail()), saved);
    }

    @Transactional
    public InvestorAuthResult otpLogin(String email, String code) {
        String normalized = normalizeEmail(email);
        otpService.verify(normalized, OtpPurpose.INVESTOR_LOGIN, code); // consumes; throws on failure
        InvestorAccount account = accountRepository.findByEmailIgnoreCase(normalized)
                .filter(a -> a.getStatus() == InvestorAccountStatus.ACTIVE)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or code"));
        return new InvestorAuthResult(jwtService.generateInvestorToken(account.getId(), account.getEmail()), account);
    }

    @Transactional(readOnly = true)
    public InvestorAccount requireAccount(UUID accountId) {
        InvestorAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BadCredentialsException("Investor account not found"));
        // Enforce status on every session use, not just at login: a BLOCKED/deactivated
        // account must lose access immediately, even while its JWT is still unexpired.
        if (account.getStatus() != InvestorAccountStatus.ACTIVE) {
            throw new AccessDeniedException("Investor account is not active.");
        }
        return account;
    }

    private void requireInvestorPurpose(OtpPurpose purpose) {
        if (purpose != OtpPurpose.INVESTOR_LOGIN && purpose != OtpPurpose.INVESTOR_SIGNUP) {
            throw new IllegalArgumentException("Unsupported investor OTP purpose: " + purpose);
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
