package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.ConsentRecord;
import com.platizio.wealthtech.domain.DistributorNotificationType;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.InvestorLinkingStatus;
import com.platizio.wealthtech.domain.OtpPurpose;
import com.platizio.wealthtech.domain.ProfileChangeChallenge;
import com.platizio.wealthtech.domain.ProfileChangeStatus;
import com.platizio.wealthtech.domain.ProfileChangeType;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.repository.InvestorAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.ProfileChangeChallengeRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profile-change approval engine (investor.md R9/R10/M4). A sibling of
 * {@link TransactionApprovalService}: a distributor-proposed profile change is frozen into a
 * hashed {@link ProfileChangeChallenge}; the live profile is NOT mutated until the investor
 * approves via email OTP + consent, at which point the change is applied and
 * {@code linking_status} returns to READY. One live change per investor (partial unique index).
 */
@Service
public class ProfileChangeApprovalService {

    private static final List<ProfileChangeStatus> LIVE = List.of(
            ProfileChangeStatus.PENDING, ProfileChangeStatus.CHALLENGE_SENT, ProfileChangeStatus.APPROVED);
    private static final String CONSENT_VERSION = "v1";
    private static final String CONSENT_KEY = "profile_change_approval";

    private final ProfileChangeChallengeRepository repository;
    private final InvestorAccountRepository accountRepository;
    private final InvestorRepository investorRepository;
    private final OtpService otpService;
    private final ConsentRecordService consentRecordService;
    private final AuditService auditService;
    private final DistributorNotificationService notificationService;

    public ProfileChangeApprovalService(
            ProfileChangeChallengeRepository repository,
            InvestorAccountRepository accountRepository,
            InvestorRepository investorRepository,
            OtpService otpService,
            ConsentRecordService consentRecordService,
            AuditService auditService,
            DistributorNotificationService notificationService) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.investorRepository = investorRepository;
        this.otpService = otpService;
        this.consentRecordService = consentRecordService;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    /** T7/T9: distributor proposes a profile change; the live profile is NOT touched. */
    @Transactional
    public ProfileChangeChallenge createChallenge(
            UUID investorId, ProfileChangeType changeType, String proposedJson, UUID proposedByActorId) {
        Investor investor = investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found."));
        InvestorAccount account = accountRepository.findByInvestorId(investorId).orElse(null);

        repository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(investorId, LIVE)
                .ifPresent(c -> { c.setStatus(ProfileChangeStatus.SUPERSEDED); repository.save(c); });

        ProfileChangeChallenge c = new ProfileChangeChallenge();
        c.setInvestorId(investorId);
        c.setInvestorAccountId(account == null ? null : account.getId());
        c.setChangeType(changeType);
        c.setStatus(ProfileChangeStatus.PENDING);
        c.setSnapshotJson(proposedJson);
        c.setSnapshotSha256(ConsentRecordService.sha256(proposedJson));
        c.setConsentTemplateVersion(CONSENT_VERSION);
        c.setConsentRenderedText(consentText(c));
        c.setChannel("EMAIL");
        c.setMaskedDestination(account == null ? null : maskEmail(account.getEmail()));
        c.setOtpPurpose(OtpPurpose.PROFILE_CHANGE_APPROVAL.name());
        ProfileChangeChallenge saved = repository.save(c);

        investor.setLinkingStatus(InvestorLinkingStatus.PENDING_PROFILE_APPROVAL);
        investorRepository.save(investor);

        auditService.log("PROFILE_CHANGE", investorId, "PROFILE_CHANGE_CHALLENGE_CREATED", proposedByActorId,
                "{\"changeType\":\"" + changeType + "\",\"hash\":\"" + saved.getSnapshotSha256() + "\"}");
        return saved;
    }

    /** The live pending change for an investor (for the Approvals page), or null. */
    @Transactional(readOnly = true)
    public ProfileChangeChallenge pendingForInvestor(UUID investorId) {
        return repository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(investorId, LIVE).orElse(null);
    }

    /** Sends (or resends) the approval OTP to the investor's verified email, bound to this challenge. */
    @Transactional
    public OtpRequestResponse requestApprovalOtp(UUID investorId) {
        ProfileChangeChallenge c = liveOrThrow(investorId);
        if (c.getStatus() != ProfileChangeStatus.PENDING && c.getStatus() != ProfileChangeStatus.CHALLENGE_SENT) {
            throw new IllegalStateException("This change can no longer receive a code.");
        }
        if (c.getInvestorAccountId() == null) {
            throw new IllegalStateException("The investor hasn't signed up yet, so they cannot approve changes.");
        }
        InvestorAccount account = accountRepository.findById(c.getInvestorAccountId())
                .orElseThrow(() -> new EntityNotFoundException("Investor account not found."));
        OtpRequestResponse response = otpService.requestOtp(account.getEmail(), OtpPurpose.PROFILE_CHANGE_APPROVAL, c.getId());
        c.setStatus(ProfileChangeStatus.CHALLENGE_SENT);
        c.setDeliveryAttempts(c.getDeliveryAttempts() + 1);
        c.setExpiresAt(OffsetDateTime.now().plusSeconds(response.expiresInSeconds()));
        repository.save(c);
        return response;
    }

    /** Investor approves the exact change they reviewed: verify OTP+consent → apply → CONSUMED → notify. */
    @Transactional
    public ProfileChangeChallenge approve(
            UUID investorId, UUID investorAccountId, String otp, boolean consentAccepted, String ip, String ua, String sessionId) {
        ProfileChangeChallenge c = liveOrThrow(investorId);
        if (c.getStatus() != ProfileChangeStatus.CHALLENGE_SENT) {
            throw new IllegalStateException("This change is not awaiting a code. Request one first.");
        }
        if (c.getExpiresAt() != null && c.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalStateException("This approval has expired. Request a new code.");
        }
        if (!Objects.equals(c.getInvestorAccountId(), investorAccountId)) {
            throw new AccessDeniedException("This change does not belong to you.");
        }
        if (!consentAccepted) {
            throw new IllegalArgumentException("You must accept the consent to approve this change.");
        }
        InvestorAccount account = accountRepository.findById(investorAccountId)
                .orElseThrow(() -> new EntityNotFoundException("Investor account not found."));
        // Verifies + burns the code BOUND to this challenge (cross-consume safe); 401 on failure.
        otpService.verify(account.getEmail(), OtpPurpose.PROFILE_CHANGE_APPROVAL, otp, c.getId());

        ConsentRecord consent = consentRecordService.record(
                ConsentRecordService.SUBJECT_INVESTOR, investorAccountId, CONSENT_KEY, c.getConsentTemplateVersion(),
                c.getConsentRenderedText(), ip, ua);

        OffsetDateTime now = OffsetDateTime.now();
        c.setApprovedAt(now);
        c.setConsentRecordId(consent.getId());
        c.setIpAddress(truncate(ip, 64));
        c.setUserAgent(truncate(ua, 512));
        c.setSessionId(truncate(sessionId, 128));

        // Apply: only now is the proposed change made live (linking_status → READY).
        Investor investor = investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found."));
        investor.setLinkingStatus(InvestorLinkingStatus.READY);
        investorRepository.save(investor);

        c.setStatus(ProfileChangeStatus.CONSUMED);
        c.setConsumedAt(now);
        ProfileChangeChallenge saved = repository.save(c);

        notificationService.createForDistributor(investor.getDistributorId(), investorId,
                DistributorNotificationType.PROFILE_CHANGE_APPROVED, "Profile change approved",
                "The investor approved the proposed profile change.");
        auditService.log("PROFILE_CHANGE", investorId, "PROFILE_CHANGE_APPROVED", investorAccountId,
                "{\"challengeId\":\"" + c.getId() + "\",\"consentRecordId\":\"" + consent.getId() + "\"}");
        return saved;
    }

    /** Investor rejects: a PROFILE_EDIT keeps the old live profile (→READY); a first fill → INVESTOR_SKIPPED. */
    @Transactional
    public ProfileChangeChallenge reject(UUID investorId, UUID investorAccountId) {
        ProfileChangeChallenge c = liveOrThrow(investorId);
        if (!Objects.equals(c.getInvestorAccountId(), investorAccountId)) {
            throw new AccessDeniedException("This change does not belong to you.");
        }
        c.setStatus(ProfileChangeStatus.REJECTED);
        repository.save(c);
        Investor investor = investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found."));
        investor.setLinkingStatus(c.getChangeType() == ProfileChangeType.PROFILE_EDIT
                ? InvestorLinkingStatus.READY : InvestorLinkingStatus.INVESTOR_SKIPPED);
        investorRepository.save(investor);
        auditService.log("PROFILE_CHANGE", investorId, "PROFILE_CHANGE_REJECTED", investorAccountId,
                "{\"challengeId\":\"" + c.getId() + "\"}");
        return c;
    }

    private ProfileChangeChallenge liveOrThrow(UUID investorId) {
        return repository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(investorId, LIVE)
                .orElseThrow(() -> new EntityNotFoundException("No profile change is awaiting your approval."));
    }

    private String consentText(ProfileChangeChallenge c) {
        return "I, the investor, have reviewed the proposed change to my profile shown above and approve it. "
                + "This approval is recorded with a one-time passcode sent to my verified email. (template "
                + CONSENT_VERSION + ", snapshot " + c.getSnapshotSha256() + ")";
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return email;
        String[] p = email.split("@", 2);
        String n = p[0];
        return (n.length() <= 2 ? n.substring(0, 1) : n.substring(0, 2)) + "***@" + p[1];
    }

    private String truncate(String v, int max) {
        return v == null ? null : (v.length() <= max ? v : v.substring(0, max));
    }
}
