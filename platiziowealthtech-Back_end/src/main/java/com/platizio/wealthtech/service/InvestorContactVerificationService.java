package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.ContactChannel;
import com.platizio.wealthtech.domain.ContactVerificationMethod;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.dto.ContactVerificationStatus;
import com.platizio.wealthtech.integration.SupabaseAuthClient;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.validation.MobileFormat;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Email + mobile contact verification and self-declaration for investors.
 *
 * <p>Each channel can be satisfied two ways:
 * <ul>
 *   <li><b>OTP</b> — email codes go via {@link SupabaseAuthClient}; mobile codes go
 *       via {@link SmsOtpService} (Supabase Phone provider when enabled, otherwise
 *       a demo stub until the MSG91 integration lands);</li>
 *   <li><b>Self-declaration</b> — the distributor attests the contact belongs to
 *       the investor and supplies the relationship ({@code belongs_to}).</li>
 * </ul>
 * Both write {@code *_verified / *_verified_at / *_verification_method /
 * *_belongs_to} on the investor and emit an audit event. The declared
 * {@code belongs_to} is later forwarded to FP (replacing the old hardcoded
 * {@code "self"}). Every call enforces that the investor belongs to the acting
 * distributor.
 */
@Service
public class InvestorContactVerificationService {

    private static final String DEFAULT_BELONGS_TO = "self";

    private final InvestorRepository investorRepository;
    private final SupabaseAuthClient supabaseAuthClient;
    private final SmsOtpService smsOtpService;
    private final AuditService auditService;

    public InvestorContactVerificationService(
            InvestorRepository investorRepository,
            SupabaseAuthClient supabaseAuthClient,
            SmsOtpService smsOtpService,
            AuditService auditService) {
        this.investorRepository = investorRepository;
        this.supabaseAuthClient = supabaseAuthClient;
        this.smsOtpService = smsOtpService;
        this.auditService = auditService;
    }

    @Transactional
    public ContactVerificationStatus requestEmailOtp(UUID investorId, UUID actorId) {
        Investor investor = loadOwned(investorId, actorId);
        String email = requireContact(investor.getEmail(), "email");
        supabaseAuthClient.sendEmailOtp(email);
        auditService.log("INVESTOR", investorId, "EMAIL_OTP_SENT", actorId, null);
        return status(investor);
    }

    @Transactional
    public ContactVerificationStatus verifyEmailOtp(UUID investorId, UUID actorId, String code) {
        Investor investor = loadOwned(investorId, actorId);
        String email = requireContact(investor.getEmail(), "email");
        if (!supabaseAuthClient.verifyEmailOtp(email, normalizeCode(code))) {
            auditService.log("INVESTOR", investorId, "EMAIL_VERIFICATION_FAILED", actorId, null);
            throw new BadCredentialsException("Invalid or expired code. Please request a new one.");
        }
        markVerified(investor, ContactChannel.EMAIL, ContactVerificationMethod.OTP, null);
        investorRepository.save(investor);
        auditService.log("INVESTOR", investorId, "EMAIL_OTP_VERIFIED", actorId, null);
        return status(investor);
    }

    @Transactional
    public ContactVerificationStatus requestMobileOtp(UUID investorId, UUID actorId) {
        Investor investor = loadOwned(investorId, actorId);
        String phoneE164 = MobileFormat.toE164India(requireContact(investor.getMobileNumber(), "mobile"));
        smsOtpService.sendOtp(phoneE164);
        auditService.log("INVESTOR", investorId, "MOBILE_OTP_SENT", actorId, null);
        return status(investor);
    }

    @Transactional
    public ContactVerificationStatus verifyMobileOtp(UUID investorId, UUID actorId, String code) {
        Investor investor = loadOwned(investorId, actorId);
        String phoneE164 = MobileFormat.toE164India(requireContact(investor.getMobileNumber(), "mobile"));
        if (!smsOtpService.verifyOtp(phoneE164, normalizeCode(code))) {
            auditService.log("INVESTOR", investorId, "MOBILE_VERIFICATION_FAILED", actorId, null);
            throw new BadCredentialsException("Invalid or expired code. Please request a new one.");
        }
        markVerified(investor, ContactChannel.MOBILE, ContactVerificationMethod.OTP, null);
        investorRepository.save(investor);
        auditService.log("INVESTOR", investorId, "MOBILE_OTP_VERIFIED", actorId, null);
        return status(investor);
    }

    @Transactional
    public ContactVerificationStatus declareContact(
            UUID investorId, UUID actorId, ContactChannel channel, String belongsTo) {
        Investor investor = loadOwned(investorId, actorId);
        String relationship = StringUtils.hasText(belongsTo) ? belongsTo.trim() : DEFAULT_BELONGS_TO;
        if (channel == ContactChannel.EMAIL) {
            requireContact(investor.getEmail(), "email");
        } else {
            requireContact(investor.getMobileNumber(), "mobile");
        }
        markVerified(investor, channel, ContactVerificationMethod.SELF_DECLARED, relationship);
        investorRepository.save(investor);
        String action = channel == ContactChannel.EMAIL ? "EMAIL_SELF_DECLARED" : "MOBILE_SELF_DECLARED";
        auditService.log("INVESTOR", investorId, action, actorId, "{\"belongs_to\":\"" + relationship + "\"}");
        return status(investor);
    }

    @Transactional(readOnly = true)
    public ContactVerificationStatus getStatus(UUID investorId, UUID actorId) {
        return status(loadOwned(investorId, actorId));
    }

    // ── investor-self variants (authenticated investor, no distributor actor) ──
    // The caller resolves {@code investorId} from the authenticated investor
    // account's linked profile, so ownership is guaranteed by the session and the
    // distributor-ownership check is intentionally skipped.

    @Transactional
    public ContactVerificationStatus requestEmailOtpAsInvestor(UUID investorId, UUID investorAccountId) {
        Investor investor = loadSelf(investorId);
        String email = requireContact(investor.getEmail(), "email");
        supabaseAuthClient.sendEmailOtp(email);
        auditService.log("INVESTOR", investorId, "EMAIL_OTP_SENT", investorAccountId, null);
        return status(investor);
    }

    @Transactional
    public ContactVerificationStatus verifyEmailOtpAsInvestor(UUID investorId, UUID investorAccountId, String code) {
        Investor investor = loadSelf(investorId);
        String email = requireContact(investor.getEmail(), "email");
        if (!supabaseAuthClient.verifyEmailOtp(email, normalizeCode(code))) {
            auditService.log("INVESTOR", investorId, "EMAIL_VERIFICATION_FAILED", investorAccountId, null);
            throw new BadCredentialsException("Invalid or expired code. Please request a new one.");
        }
        markVerified(investor, ContactChannel.EMAIL, ContactVerificationMethod.OTP, null);
        investorRepository.save(investor);
        auditService.log("INVESTOR", investorId, "EMAIL_OTP_VERIFIED", investorAccountId, null);
        return status(investor);
    }

    @Transactional
    public ContactVerificationStatus requestMobileOtpAsInvestor(UUID investorId, UUID investorAccountId) {
        Investor investor = loadSelf(investorId);
        String phoneE164 = MobileFormat.toE164India(requireContact(investor.getMobileNumber(), "mobile"));
        smsOtpService.sendOtp(phoneE164);
        auditService.log("INVESTOR", investorId, "MOBILE_OTP_SENT", investorAccountId, null);
        return status(investor);
    }

    @Transactional
    public ContactVerificationStatus verifyMobileOtpAsInvestor(UUID investorId, UUID investorAccountId, String code) {
        Investor investor = loadSelf(investorId);
        String phoneE164 = MobileFormat.toE164India(requireContact(investor.getMobileNumber(), "mobile"));
        if (!smsOtpService.verifyOtp(phoneE164, normalizeCode(code))) {
            auditService.log("INVESTOR", investorId, "MOBILE_VERIFICATION_FAILED", investorAccountId, null);
            throw new BadCredentialsException("Invalid or expired code. Please request a new one.");
        }
        markVerified(investor, ContactChannel.MOBILE, ContactVerificationMethod.OTP, null);
        investorRepository.save(investor);
        auditService.log("INVESTOR", investorId, "MOBILE_OTP_VERIFIED", investorAccountId, null);
        return status(investor);
    }

    @Transactional
    public ContactVerificationStatus declareContactAsInvestor(
            UUID investorId, UUID investorAccountId, ContactChannel channel, String belongsTo) {
        Investor investor = loadSelf(investorId);
        String relationship = StringUtils.hasText(belongsTo) ? belongsTo.trim() : DEFAULT_BELONGS_TO;
        if (channel == ContactChannel.EMAIL) {
            requireContact(investor.getEmail(), "email");
        } else {
            requireContact(investor.getMobileNumber(), "mobile");
        }
        markVerified(investor, channel, ContactVerificationMethod.SELF_DECLARED, relationship);
        investorRepository.save(investor);
        String action = channel == ContactChannel.EMAIL ? "EMAIL_SELF_DECLARED" : "MOBILE_SELF_DECLARED";
        auditService.log("INVESTOR", investorId, action, investorAccountId, "{\"belongs_to\":\"" + relationship + "\"}");
        return status(investor);
    }

    @Transactional(readOnly = true)
    public ContactVerificationStatus getStatusAsInvestor(UUID investorId) {
        return status(loadSelf(investorId));
    }

    // ── internals ──────────────────────────────────────────────────────────

    private Investor loadSelf(UUID investorId) {
        return investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor profile not found"));
    }

    private void markVerified(
            Investor investor, ContactChannel channel, ContactVerificationMethod method, String belongsTo) {
        OffsetDateTime now = OffsetDateTime.now();
        if (channel == ContactChannel.EMAIL) {
            investor.setEmailVerified(Boolean.TRUE);
            investor.setEmailVerifiedAt(now);
            investor.setEmailVerificationMethod(method.name());
            if (belongsTo != null) {
                investor.setEmailBelongsTo(belongsTo);
            } else if (!StringUtils.hasText(investor.getEmailBelongsTo())) {
                investor.setEmailBelongsTo(DEFAULT_BELONGS_TO);
            }
        } else {
            investor.setMobileVerified(Boolean.TRUE);
            investor.setMobileVerifiedAt(now);
            investor.setMobileVerificationMethod(method.name());
            if (belongsTo != null) {
                investor.setMobileBelongsTo(belongsTo);
            } else if (!StringUtils.hasText(investor.getMobileBelongsTo())) {
                investor.setMobileBelongsTo(DEFAULT_BELONGS_TO);
            }
        }
    }

    private Investor loadOwned(UUID investorId, UUID actorId) {
        Investor investor = investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
        if (actorId == null || !actorId.equals(investor.getDistributorId())) {
            throw new AccessDeniedException("Investor does not belong to the authenticated distributor");
        }
        return investor;
    }

    private String requireContact(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Investor has no " + label + " on file to verify.");
        }
        return value.trim();
    }

    private String normalizeCode(String code) {
        return code == null ? "" : code.trim();
    }

    private ContactVerificationStatus status(Investor investor) {
        // Mobile OTP is always offerable: real SMS when a provider is configured,
        // the SmsOtpService demo stub otherwise (TODO(MSG91): becomes real then).
        return ContactVerificationStatus.of(
                investor, supabaseAuthClient.isEnabled(), smsOtpService.isAvailable());
    }
}
