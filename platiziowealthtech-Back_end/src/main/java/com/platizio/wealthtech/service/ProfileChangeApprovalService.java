package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.ConsentRecord;
import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.OtpPurpose;
import com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge;
import com.platizio.wealthtech.domain.ProfileChangeApprovalStatus;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.repository.ProfileChangeApprovalChallengeRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The profile-change-2FA engine (Person-A R9/R10). MIRRORS
 * {@link TransactionApprovalService} method-for-method: it freezes an immutable,
 * hashed snapshot of a distributor-filled investor profile change, drives the
 * investor through email-OTP + consent approval, and exposes the atomic hard gate
 * {@link #assertApprovedAndConsume} that the profile-apply must pass.
 *
 * <p>No profile write may be applied unless an {@code APPROVED} challenge for that
 * exact investor exists AND its {@code profileChangeSha256} still equals a
 * freshly-recomputed snapshot hash (nothing changed since the investor approved).
 * The single atomic gate {@link #assertApprovedAndConsume} both checks that
 * condition and flips the challenge {@code APPROVED → CONSUMED} in the same call,
 * placed immediately before the apply inside the same {@code @Transactional} method:
 * an apply exception rolls back the {@code CONSUMED} flip (retry-safe), and a replay
 * finds {@code CONSUMED} (not {@code APPROVED}) → blocked (exactly-once). At most one
 * live ({@code PENDING | CHALLENGE_SENT | APPROVED}) challenge may exist per investor
 * at a time: creating a new one supersedes any live predecessor.
 *
 * <p>The "acting account owns this investor" check is delegated to
 * {@link InvestorAccountOwnershipGuard#assertOwns} (the shared linking-backbone
 * guard), which throws {@link EntityNotFoundException} (404) /
 * {@link AccessDeniedException} (403).
 *
 * <p>Exception → HTTP mapping (via {@code GlobalExceptionHandler}):
 * {@link EntityNotFoundException} → 404, {@link AccessDeniedException} → 403,
 * {@link org.springframework.security.authentication.BadCredentialsException} (thrown
 * by {@link OtpService#verify}) → 401, {@link IllegalArgumentException} /
 * {@link IllegalStateException} → 400.
 */
@Service
public class ProfileChangeApprovalService {

    /** Consent template version captured on every approval (bump on copy change). */
    static final String CONSENT_TEMPLATE_VERSION = "v1.0";
    static final String CHANNEL_EMAIL = "EMAIL";
    static final String CONSENT_KEY_PROFILE = "profile_change_approval";

    /** The live (non-terminal) states; at most one per investor at a time. */
    private static final Set<ProfileChangeApprovalStatus> LIVE_STATUSES = EnumSet.of(
            ProfileChangeApprovalStatus.PENDING,
            ProfileChangeApprovalStatus.CHALLENGE_SENT,
            ProfileChangeApprovalStatus.APPROVED);

    private final ProfileChangeApprovalChallengeRepository challengeRepository;
    private final ConsentRecordService consentRecordService;
    private final OtpService otpService;
    private final InvestorAccountOwnershipGuard ownershipGuard;
    private final AuditService auditService;

    public ProfileChangeApprovalService(
            ProfileChangeApprovalChallengeRepository challengeRepository,
            ConsentRecordService consentRecordService,
            OtpService otpService,
            InvestorAccountOwnershipGuard ownershipGuard,
            AuditService auditService) {
        this.challengeRepository = challengeRepository;
        this.consentRecordService = consentRecordService;
        this.otpService = otpService;
        this.ownershipGuard = ownershipGuard;
        this.auditService = auditService;
    }

    // ---- createChallenge ----------------------------------------------------

    /**
     * Freezes a distributor-filled pending profile into a new {@code PENDING}
     * challenge (snapshot + hash + rendered consent text). Enforces one live
     * challenge per investor: any existing live challenge is superseded first.
     *
     * @param investorId         the investor whose profile is being changed
     * @param pendingProfileJson the canonical pending-profile JSON to be approved
     * @param distributorActorId the distributor who filled the profile (audit actor)
     */
    @Transactional
    public ProfileChangeApprovalChallenge createChallenge(
            UUID investorId, String pendingProfileJson, UUID distributorActorId) {
        // One live challenge per investor: supersede any live predecessor.
        challengeRepository
                .findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(investorId, LIVE_STATUSES)
                .filter(c -> LIVE_STATUSES.contains(c.getStatus()))
                .ifPresent(existing -> {
                    existing.setStatus(ProfileChangeApprovalStatus.SUPERSEDED);
                    challengeRepository.save(existing);
                });

        ProfileChangeApprovalChallenge challenge = new ProfileChangeApprovalChallenge();
        challenge.setInvestorId(investorId);
        challenge.setStatus(ProfileChangeApprovalStatus.PENDING);
        challenge.setPendingProfileJson(pendingProfileJson);
        challenge.setProfileChangeSha256(ConsentRecordService.sha256(pendingProfileJson));
        challenge.setConsentTemplateVersion(CONSENT_TEMPLATE_VERSION);
        challenge.setConsentRenderedText(renderConsentText(challenge));
        challenge.setChannel(CHANNEL_EMAIL);
        challenge.setOtpPurpose(OtpPurpose.PROFILE_APPROVAL);
        challenge.setDeliveryAttempts(0);
        ProfileChangeApprovalChallenge saved = challengeRepository.save(challenge);

        auditService.log("PROFILE_APPROVAL", investorId, "PROFILE_APPROVAL_CHALLENGE_CREATED",
                distributorActorId,
                "{\"hash\":\"" + saved.getProfileChangeSha256() + "\"}");
        return saved;
    }

    // ---- renderConsentText --------------------------------------------------

    /**
     * The exact consent text frozen into the challenge as immutable evidence (the
     * rendered text is stored, not the placeholder source). Reuses the snapshot hash
     * so the consent and snapshot hashes cannot diverge.
     */
    public String renderConsentText(ProfileChangeApprovalChallenge challenge) {
        return "I, the investor, authorise the changes my distributor has made to my profile. "
                + "I have reviewed the profile details shown above and approve their application. "
                + "This approval is recorded with a one-time passcode sent to my verified email as a "
                + "second factor of authentication. (template " + CONSENT_TEMPLATE_VERSION
                + ", snapshot " + challenge.getProfileChangeSha256() + ")";
    }

    // ---- requestApprovalOtp -------------------------------------------------

    /**
     * Sends (or resends) the approval OTP to the investor's verified email, moving
     * {@code PENDING → CHALLENGE_SENT}, incrementing {@code deliveryAttempts} and
     * setting {@code expiresAt}. Asserts the acting account owns the investor via the
     * ownership guard. Returns an {@link OtpRequestResponse}, which never carries the
     * live code — so a distributor resend cannot leak it.
     *
     * @throws EntityNotFoundException if the challenge or account is missing
     * @throws AccessDeniedException   if the account does not own the investor
     * @throws IllegalStateException   if the challenge is terminal (not live)
     */
    @Transactional
    public OtpRequestResponse requestApprovalOtp(
            UUID challengeId, UUID investorAccountId, boolean isDistributorResend) {
        ProfileChangeApprovalChallenge challenge = loadChallenge(challengeId);
        if (challenge.getStatus() != ProfileChangeApprovalStatus.PENDING
                && challenge.getStatus() != ProfileChangeApprovalStatus.CHALLENGE_SENT) {
            throw new IllegalStateException("This approval challenge can no longer receive a code.");
        }
        // Acting account must own the investor (403/404 via the shared guard).
        InvestorAccount account = ownershipGuard.assertOwns(investorAccountId, challenge.getInvestorId());

        // Bind the code to THIS challenge so a sibling challenge (same email+purpose)
        // cannot cross-consume it.
        OtpRequestResponse response = otpService.requestOtp(
                account.getEmail(), OtpPurpose.PROFILE_APPROVAL, challenge.getId());

        challenge.setStatus(ProfileChangeApprovalStatus.CHALLENGE_SENT);
        challenge.setMaskedDestination(maskEmail(account.getEmail()));
        challenge.setDeliveryAttempts(
                (challenge.getDeliveryAttempts() == null ? 0 : challenge.getDeliveryAttempts()) + 1);
        challenge.setExpiresAt(OffsetDateTime.now().plusSeconds(response.expiresInSeconds()));
        challengeRepository.save(challenge);

        auditService.log("PROFILE_APPROVAL", challenge.getInvestorId(),
                isDistributorResend ? "PROFILE_APPROVAL_OTP_RESENT" : "PROFILE_APPROVAL_OTP_REQUESTED",
                investorAccountId,
                "{\"challengeId\":\"" + challenge.getId() + "\",\"attempt\":"
                        + challenge.getDeliveryAttempts() + ",\"distributorResend\":"
                        + isDistributorResend + "}");
        // Returned as-is: OtpRequestResponse hides the code in deployed/demo envs.
        return response;
    }

    // ---- approve ------------------------------------------------------------

    /**
     * The investor approves the exact profile change they reviewed. Verifies (in
     * order): challenge is {@code CHALLENGE_SENT} and not expired; the account owns
     * the investor (via the ownership guard); consent was accepted; the OTP is valid
     * (burned by {@link OtpService#verify}). On success records the consent evidence,
     * links it, captures ip/ua/session, and moves {@code → APPROVED}.
     *
     * @throws EntityNotFoundException if the challenge/account is missing
     * @throws IllegalStateException   if the challenge is not awaiting OTP or expired
     * @throws AccessDeniedException   if the account does not own the investor
     * @throws IllegalArgumentException if consent was not accepted
     * @throws org.springframework.security.authentication.BadCredentialsException on bad/expired OTP
     */
    @Transactional
    public ProfileChangeApprovalChallenge approve(
            UUID challengeId, UUID investorAccountId, String otpCode, boolean consentAccepted,
            String ip, String ua, String sessionId) {
        ProfileChangeApprovalChallenge challenge = loadChallenge(challengeId);

        if (challenge.getStatus() != ProfileChangeApprovalStatus.CHALLENGE_SENT) {
            throw new IllegalStateException("This approval challenge is not awaiting a code.");
        }
        if (challenge.getExpiresAt() != null && challenge.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalStateException("This approval has expired. Request a new one.");
        }

        // Acting account must own the investor (403/404 via the shared guard).
        InvestorAccount account = ownershipGuard.assertOwns(investorAccountId, challenge.getInvestorId());

        if (!consentAccepted) {
            throw new IllegalArgumentException("You must accept the consent to approve this profile change.");
        }

        // Verifies + burns the code BOUND to this challenge; throws BadCredentialsException
        // (401) on failure. The challengeId binding prevents accepting a sibling
        // challenge's code (cross-consume).
        otpService.verify(account.getEmail(), OtpPurpose.PROFILE_APPROVAL, otpCode, challenge.getId());

        ConsentRecord consent = consentRecordService.record(
                ConsentRecordService.SUBJECT_INVESTOR, investorAccountId,
                CONSENT_KEY_PROFILE, challenge.getConsentTemplateVersion(),
                challenge.getConsentRenderedText(), ip, ua);

        challenge.setStatus(ProfileChangeApprovalStatus.APPROVED);
        challenge.setApprovedAt(OffsetDateTime.now());
        challenge.setConsentRecordId(consent.getId());
        challenge.setIpAddress(truncate(ip, 64));
        challenge.setUserAgent(truncate(ua, 512));
        challenge.setSessionId(truncate(sessionId, 128));
        ProfileChangeApprovalChallenge saved = challengeRepository.save(challenge);

        auditService.log("PROFILE_APPROVAL", challenge.getInvestorId(), "PROFILE_APPROVAL_APPROVED",
                investorAccountId,
                "{\"challengeId\":\"" + challenge.getId() + "\",\"consentRecordId\":\""
                        + consent.getId() + "\",\"hash\":\"" + challenge.getProfileChangeSha256() + "\"}");
        return saved;
    }

    // ---- assertApprovedAndConsume (the atomic hard gate) --------------------

    /**
     * The single atomic gate planted as the FIRST profile-touching statement inside
     * the profile-apply {@code @Transactional} method. In one call it BOTH verifies
     * that an {@code APPROVED} challenge exists for the investor whose frozen
     * {@code profileChangeSha256} still equals the freshly-recomputed hash (nothing
     * changed since the investor approved), AND flips that challenge
     * {@code APPROVED → CONSUMED}.
     *
     * <p>Atomicity is what makes the gate both exactly-once and retry-safe:
     * <ul>
     *   <li><b>Exactly-once</b> — only an {@code APPROVED} challenge can pass; a
     *       {@code SUPERSEDED}/{@code EXPIRED}/already-{@code CONSUMED} challenge is not
     *       found, so a replay throws.</li>
     *   <li><b>Retry-safe</b> — because the {@code CONSUMED} flip happens inside the
     *       caller's transaction immediately before the apply, an apply exception rolls
     *       the flip back, leaving the challenge {@code APPROVED} for a genuine retry. A
     *       successful apply commits the {@code CONSUMED} flip, so any later replay finds
     *       {@code CONSUMED} (not {@code APPROVED}) → blocked.</li>
     * </ul>
     *
     * @throws IllegalStateException if no {@code APPROVED} challenge exists or the hash drifted
     */
    @Transactional
    public void assertApprovedAndConsume(UUID investorId, String recomputedSha256) {
        ProfileChangeApprovalChallenge approved = challengeRepository
                .findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(
                        investorId, ProfileChangeApprovalStatus.APPROVED)
                .orElseThrow(() -> new IllegalStateException(
                        "Investor 2FA approval required: no approved profile change exists for this investor."));
        if (!Objects.equals(approved.getProfileChangeSha256(), recomputedSha256)) {
            throw new IllegalStateException(
                    "This profile changed after the investor approved it; re-request approval.");
        }
        approved.setStatus(ProfileChangeApprovalStatus.CONSUMED);
        approved.setConsumedAt(OffsetDateTime.now());
        challengeRepository.save(approved);
        auditService.log("PROFILE_APPROVAL", investorId, "PROFILE_APPROVAL_CONSUMED",
                approved.getInvestorId(),
                "{\"challengeId\":\"" + approved.getId() + "\"}");
    }

    // ---- supersedeOnEdit ----------------------------------------------------

    /**
     * Invalidates a live challenge after a distributor edits the pending profile,
     * forcing re-authorisation. No-op when nothing is live.
     */
    @Transactional
    public void supersedeOnEdit(UUID investorId, UUID distributorActorId) {
        challengeRepository
                .findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(investorId, LIVE_STATUSES)
                .filter(c -> LIVE_STATUSES.contains(c.getStatus()))
                .ifPresent(c -> {
                    c.setStatus(ProfileChangeApprovalStatus.SUPERSEDED);
                    challengeRepository.save(c);
                    auditService.log("PROFILE_APPROVAL", investorId, "PROFILE_APPROVAL_SUPERSEDED_ON_EDIT",
                            distributorActorId,
                            "{\"challengeId\":\"" + c.getId() + "\"}");
                });
    }

    // ---- reject -------------------------------------------------------------

    /**
     * Records the investor declining a distributor-filled profile change: marks the
     * named challenge {@code REJECTED} so it can never authorise an apply. The acting
     * account must own the investor (403/404 via the shared guard). No-op-safe only
     * for live challenges; a terminal challenge cannot be re-rejected.
     *
     * @throws EntityNotFoundException if the challenge or account is missing
     * @throws AccessDeniedException   if the account does not own the investor
     * @throws IllegalStateException   if the challenge is already terminal
     */
    @Transactional
    public ProfileChangeApprovalChallenge reject(
            UUID challengeId, UUID investorAccountId, String reason) {
        ProfileChangeApprovalChallenge challenge = loadChallenge(challengeId);
        // Acting account must own the investor (403/404 via the shared guard).
        ownershipGuard.assertOwns(investorAccountId, challenge.getInvestorId());
        if (!LIVE_STATUSES.contains(challenge.getStatus())) {
            throw new IllegalStateException("This approval challenge can no longer be rejected.");
        }
        challenge.setStatus(ProfileChangeApprovalStatus.REJECTED);
        ProfileChangeApprovalChallenge saved = challengeRepository.save(challenge);
        auditService.log("PROFILE_APPROVAL", challenge.getInvestorId(), "PROFILE_APPROVAL_REJECTED",
                investorAccountId,
                "{\"challengeId\":\"" + challenge.getId() + "\",\"reason\":\""
                        + (reason == null ? "" : reason.replace("\"", "'")) + "\"}");
        return saved;
    }

    // ---- reads --------------------------------------------------------------

    /** All challenges for an investor (any status). */
    @Transactional(readOnly = true)
    public List<ProfileChangeApprovalChallenge> listForInvestor(UUID investorId) {
        return challengeRepository.findByInvestorId(investorId);
    }

    /**
     * Reads a single challenge by id without throwing (the caller decides 404 vs 403).
     */
    @Transactional(readOnly = true)
    public Optional<ProfileChangeApprovalChallenge> getChallenge(UUID challengeId) {
        return challengeRepository.findById(challengeId);
    }

    // ---- helpers ------------------------------------------------------------

    private ProfileChangeApprovalChallenge loadChallenge(UUID challengeId) {
        return challengeRepository.findById(challengeId)
                .orElseThrow(() -> new EntityNotFoundException("Approval challenge not found."));
    }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
