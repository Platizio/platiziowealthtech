package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.InvestorAccountStatus;
import com.platizio.wealthtech.domain.OtpPurpose;
import com.platizio.wealthtech.dto.InvestorSignupRequest;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.repository.DistributorRepository;
import com.platizio.wealthtech.repository.InvestorAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.validation.MobileFormat;
import com.platizio.wealthtech.validation.PanFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 *
 * <p>Login is OTP-only (the old PAN-as-password login is removed — a static,
 * low-entropy PAN is not a credential): email OTP via {@link OtpService}, or
 * mobile OTP via {@link SmsOtpService} (demo stub until the MSG91 integration —
 * see the TODO(MSG91) markers there). Reuses {@link JwtService},
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
    private final SmsOtpService smsOtpService;
    private final JwtService jwtService;
    private final TermsAcceptanceService termsAcceptanceService;
    private final ConsentRecordService consentRecordService;
    private final DistributorRepository distributorRepository;

    public InvestorAuthService(
            InvestorAccountRepository accountRepository,
            InvestorRepository investorRepository,
            OtpService otpService,
            SmsOtpService smsOtpService,
            JwtService jwtService,
            TermsAcceptanceService termsAcceptanceService,
            ConsentRecordService consentRecordService,
            DistributorRepository distributorRepository) {
        this.accountRepository = accountRepository;
        this.investorRepository = investorRepository;
        this.otpService = otpService;
        this.smsOtpService = smsOtpService;
        this.jwtService = jwtService;
        this.termsAcceptanceService = termsAcceptanceService;
        this.consentRecordService = consentRecordService;
        this.distributorRepository = distributorRepository;
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
            // Any non-blocked account may log in via OTP: a successful OTP proves the
            // inbox, so otpLogin() activates PENDING_ACTIVATION accounts on the spot
            // (activation used to happen only on the removed PAN-password login).
            case INVESTOR_LOGIN -> existing.filter(a -> a.getStatus() != InvestorAccountStatus.BLOCKED).isPresent();
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
        // Invite-only signup (investor.md D2/R6): a portal account can be created ONLY for a PAN a
        // distributor has already onboarded. Open self-signup with an unknown PAN is rejected so no
        // distributor-less (orphan) investor accounts can ever exist.
        var linkedInvestor = investorRepository.findByPan(pan)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No distributor has invited this PAN yet. Please ask your distributor to onboard you "
                                + "or send you the registration link."));
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
        account.setMobileVerified(Boolean.TRUE);   // dummy-verified at registration (no live SMS yet)
        account.setStatus(InvestorAccountStatus.ACTIVE);
        account.setActivatedAt(OffsetDateTime.now());
        // Link to the distributor-created investor row (guaranteed present by the invite check above).
        account.setInvestorId(linkedInvestor.getId());
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
                .filter(a -> a.getStatus() != InvestorAccountStatus.BLOCKED)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or code"));
        return completeLogin(account);
    }

    /**
     * Mobile-OTP login. TODO(MSG91): the OTP send/verify below runs through the
     * {@link SmsOtpService} demo stub (code 000000) until MSG91 is integrated —
     * swap happens inside SmsOtpService, this flow stays as-is.
     */
    public OtpRequestResponse requestMobileLoginOtp(String mobileNumber) {
        // Anti-enumeration: same generic response whether or not an account matches.
        if (findLoginAccountByMobile(mobileNumber).isPresent()) {
            smsOtpService.sendOtp(MobileFormat.toE164India(MobileFormat.normalize(mobileNumber)));
        }
        return otpService.genericResponse();
    }

    /** See {@link #requestMobileLoginOtp} — demo stub until MSG91. */
    @Transactional
    public InvestorAuthResult mobileOtpLogin(String mobileNumber, String code) {
        InvestorAccount account = findLoginAccountByMobile(mobileNumber)
                .orElseThrow(() -> new BadCredentialsException("Invalid mobile number or code"));
        String phoneE164 = MobileFormat.toE164India(MobileFormat.normalize(mobileNumber));
        if (!smsOtpService.verifyOtp(phoneE164, code == null ? "" : code.trim())) {
            throw new BadCredentialsException("Invalid mobile number or code");
        }
        return completeLogin(account);
    }

    /**
     * Resolves the single non-blocked account for a mobile number. Mobile is not
     * unique on investor_accounts, so an ambiguous match (several accounts sharing
     * the number) refuses mobile login — those investors use email OTP instead.
     */
    private Optional<InvestorAccount> findLoginAccountByMobile(String mobileNumber) {
        var candidates = accountRepository.findByMobileNumber(MobileFormat.normalize(mobileNumber)).stream()
                .filter(a -> a.getStatus() != InvestorAccountStatus.BLOCKED)
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    /**
     * Shared post-OTP login steps: the verified OTP proved the contact channel, so
     * link the account to its distributor-created investor when possible, require a
     * distributor, and activate a PENDING_ACTIVATION account (activation used to
     * happen only on the removed PAN-password login path).
     */
    private InvestorAuthResult completeLogin(InvestorAccount account) {
        account = tryLinkAccountToDistributorInvestor(account);
        assertDistributorAllotted(account);
        if (account.getStatus() != InvestorAccountStatus.ACTIVE) {
            account.setStatus(InvestorAccountStatus.ACTIVE);
            if (account.getActivatedAt() == null) {
                account.setActivatedAt(OffsetDateTime.now());
            }
            account = accountRepository.save(account);
        }
        return new InvestorAuthResult(jwtService.generateInvestorToken(account.getId(), account.getEmail()), account);
    }

    /** Best-effort link to the distributor-created investor with the same PAN + email. */
    private InvestorAccount tryLinkAccountToDistributorInvestor(InvestorAccount account) {
        if (account.getInvestorId() != null || account.getPan() == null) {
            return account;
        }
        return investorRepository.findByPan(account.getPan())
                .filter(inv -> normalizeEmail(inv.getEmail()).equals(normalizeEmail(account.getEmail())))
                .map(inv -> {
                    account.setInvestorId(inv.getId());
                    return accountRepository.save(account);
                })
                .orElse(account);
    }

    private void assertDistributorAllotted(InvestorAccount account) {
        if (account.getInvestorId() == null) {
            throw new BadCredentialsException(
                    "No distributor allotted yet. Ask your distributor to onboard you, then open the approval link they email you.");
        }
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

    /**
     * The linked investor's KYC status straight from the local DB (no live Cybrilla call),
     * so the profile/dashboard can show it even when the provider is unavailable — the DB is
     * the cache/fallback. Returns null when the account isn't linked yet.
     */
    @Transactional(readOnly = true)
    public String investorKycStatusFor(InvestorAccount account) {
        if (account == null || account.getInvestorId() == null) {
            return null;
        }
        return investorRepository.findById(account.getInvestorId())
                .map(inv -> inv.getKycStatus() == null ? null : inv.getKycStatus().name())
                .orElse(null);
    }

    /**
     * The linked investor's distributor name (from the local DB), or null when the account is
     * not yet linked to a distributor. Shown read-only on the investor profile.
     */
    @Transactional(readOnly = true)
    public String investorDistributorNameFor(InvestorAccount account) {
        if (account == null || account.getInvestorId() == null) {
            return null;
        }
        return investorRepository.findById(account.getInvestorId())
                .map(inv -> inv.getDistributorId())
                .filter(id -> id != null)
                .flatMap(id -> distributorRepository.findById(id))
                .map(d -> d.getFullName())
                .orElse(null);
    }

    private void requireInvestorPurpose(OtpPurpose purpose) {
        if (purpose != OtpPurpose.INVESTOR_LOGIN && purpose != OtpPurpose.INVESTOR_SIGNUP) {
            throw new IllegalArgumentException("Unsupported investor OTP purpose: " + purpose);
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
