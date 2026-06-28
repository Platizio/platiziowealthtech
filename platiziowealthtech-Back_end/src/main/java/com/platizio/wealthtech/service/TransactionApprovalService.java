package com.platizio.wealthtech.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.ConsentRecord;
import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.OtpPurpose;
import com.platizio.wealthtech.domain.RedemptionRecord;
import com.platizio.wealthtech.domain.TransactionApprovalChallenge;
import com.platizio.wealthtech.domain.TransactionApprovalStatus;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.repository.InvestorAccountRepository;
import com.platizio.wealthtech.repository.RedemptionRecordRepository;
import com.platizio.wealthtech.repository.TransactionApprovalChallengeRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The KEYSTONE transaction-2FA engine (Phase-2 plan §"TransactionApprovalService API",
 * "Gate principle"). Mirrors {@link OnboardingSubmissionService}: it freezes an
 * immutable, hashed snapshot of a transaction (purchase / SIP / redemption), drives
 * the investor through email-OTP + consent approval, and exposes the atomic hard gate
 * {@link #assertApprovedAndConsume} that every provider write must pass.
 *
 * <p>No Cybrilla/FP write may fire unless an {@code APPROVED} challenge for that exact
 * transaction exists AND its {@code snapshotSha256} still equals a freshly-recomputed
 * snapshot hash. The single atomic gate {@link #assertApprovedAndConsume} both checks
 * that condition and flips the challenge {@code APPROVED → CONSUMED} in the same call,
 * placed immediately before the provider write inside the same {@code @Transactional}
 * method: a provider exception rolls back the {@code CONSUMED} flip (retry-safe), and a
 * replay finds {@code CONSUMED} (not {@code APPROVED}) → blocked (exactly-once). At most
 * one live ({@code PENDING | CHALLENGE_SENT | APPROVED}) challenge may exist per
 * transaction at a time (locked decision #3): creating a new one supersedes any live
 * predecessor.
 *
 * <p>Exception → HTTP mapping (via {@code GlobalExceptionHandler}):
 * {@link EntityNotFoundException} → 404, {@link AccessDeniedException} → 403,
 * {@link org.springframework.security.authentication.BadCredentialsException} (thrown
 * by {@link OtpService#verify}) → 401, {@link IllegalArgumentException} /
 * {@link IllegalStateException} → 400.
 */
@Service
public class TransactionApprovalService {

    /** Consent template version captured on every approval (bump on copy change). */
    static final String CONSENT_TEMPLATE_VERSION = "v1.0";
    static final String CHANNEL_EMAIL = "EMAIL";

    /** The live (non-terminal) states; at most one per transaction at a time. */
    private static final Set<TransactionApprovalStatus> LIVE_STATUSES = EnumSet.of(
            TransactionApprovalStatus.PENDING,
            TransactionApprovalStatus.CHALLENGE_SENT,
            TransactionApprovalStatus.APPROVED);

    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

    private final TransactionApprovalChallengeRepository challengeRepository;
    private final TransactionOrderRepository orderRepository;
    private final RedemptionRecordRepository redemptionRepository;
    private final InvestorAccountRepository accountRepository;
    private final ConsentRecordService consentRecordService;
    private final OtpService otpService;
    private final AuditService auditService;

    public TransactionApprovalService(
            TransactionApprovalChallengeRepository challengeRepository,
            TransactionOrderRepository orderRepository,
            RedemptionRecordRepository redemptionRepository,
            InvestorAccountRepository accountRepository,
            ConsentRecordService consentRecordService,
            OtpService otpService,
            AuditService auditService) {
        this.challengeRepository = challengeRepository;
        this.orderRepository = orderRepository;
        this.redemptionRepository = redemptionRepository;
        this.accountRepository = accountRepository;
        this.consentRecordService = consentRecordService;
        this.otpService = otpService;
        this.auditService = auditService;
    }

    // ---- createChallenge ----------------------------------------------------

    /**
     * Freezes the current state of a transaction into a new {@code PENDING}
     * challenge (snapshot + hash + rendered consent text). Enforces one live
     * challenge per transaction: any existing live challenge is superseded first.
     *
     * @throws EntityNotFoundException if the transaction or account is missing
     * @throws AccessDeniedException   if the account does not own the transaction
     */
    @Transactional
    public TransactionApprovalChallenge createChallenge(
            UUID transactionId, TransactionType type, UUID investorAccountId) {
        InvestorAccount account = accountRepository.findById(investorAccountId)
                .orElseThrow(() -> new EntityNotFoundException("Investor account not found."));

        String snapshotJson;
        UUID transactionInvestorId;
        if (type == TransactionType.REDEMPTION) {
            RedemptionRecord redemption = redemptionRepository.findById(transactionId)
                    .orElseThrow(() -> new EntityNotFoundException("Redemption not found."));
            transactionInvestorId = redemption.getInvestorId();
            snapshotJson = renderRedemptionSnapshot(redemption);
        } else if (type == TransactionType.SIP) {
            TransactionOrder order = loadOrder(transactionId);
            transactionInvestorId = order.getInvestorId();
            snapshotJson = renderSipSnapshot(order);
        } else {
            TransactionOrder order = loadOrder(transactionId);
            transactionInvestorId = order.getInvestorId();
            snapshotJson = renderPurchaseSnapshot(order);
        }

        requireOwnership(account, transactionInvestorId);

        // One live challenge per transaction: supersede any live predecessor.
        challengeRepository
                .findFirstByTransactionIdAndStatusInOrderByCreatedAtDesc(transactionId, LIVE_STATUSES)
                .filter(c -> LIVE_STATUSES.contains(c.getStatus()))
                .ifPresent(existing -> {
                    existing.setStatus(TransactionApprovalStatus.SUPERSEDED);
                    challengeRepository.save(existing);
                });

        TransactionApprovalChallenge challenge = new TransactionApprovalChallenge();
        challenge.setTransactionId(transactionId);
        challenge.setTransactionType(type);
        challenge.setInvestorId(transactionInvestorId);
        challenge.setInvestorAccountId(investorAccountId);
        challenge.setStatus(TransactionApprovalStatus.PENDING);
        challenge.setSnapshotJson(snapshotJson);
        challenge.setSnapshotSha256(ConsentRecordService.sha256(snapshotJson));
        challenge.setConsentTemplateVersion(CONSENT_TEMPLATE_VERSION);
        challenge.setConsentRenderedText(renderConsentText(challenge));
        challenge.setChannel(CHANNEL_EMAIL);
        challenge.setMaskedDestination(maskEmail(account.getEmail()));
        challenge.setOtpPurpose(OtpPurpose.TRANSACTION_APPROVAL);
        challenge.setDeliveryAttempts(0);
        TransactionApprovalChallenge saved = challengeRepository.save(challenge);

        auditService.log("TRANSACTION_APPROVAL", transactionId, "APPROVAL_CHALLENGE_CREATED",
                investorAccountId,
                "{\"type\":\"" + type + "\",\"hash\":\"" + saved.getSnapshotSha256() + "\"}");
        return saved;
    }

    private TransactionOrder loadOrder(UUID transactionId) {
        return orderRepository.findById(transactionId)
                .orElseThrow(() -> new EntityNotFoundException("Transaction order not found."));
    }

    // ---- render* (stable-key snapshots; mirror OnboardingSubmissionService) --

    /** Stable-key snapshot of a lumpsum/purchase order. */
    public String renderPurchaseSnapshot(TransactionOrder order) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("transactionType", "PURCHASE");
        snap.put("orderId", str(order.getId()));
        snap.put("investorId", str(order.getInvestorId()));
        snap.put("productSchemeId", str(order.getProductSchemeId()));
        snap.put("amount", plain(order.getAmount()));
        snap.put("units", plain(order.getUnits()));
        snap.put("paymentMode", order.getPaymentMode());
        snap.put("productCategory",
                order.getProductCategory() == null ? null : order.getProductCategory().name());
        return toJson(snap);
    }

    /** Stable-key snapshot of a SIP order (mandate + schedule terms). */
    public String renderSipSnapshot(TransactionOrder order) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("transactionType", "SIP");
        snap.put("orderId", str(order.getId()));
        snap.put("investorId", str(order.getInvestorId()));
        snap.put("productSchemeId", str(order.getProductSchemeId()));
        snap.put("amount", plain(order.getAmount()));
        snap.put("sipFrequency", order.getSipFrequency());
        snap.put("sipStartDate", order.getSipStartDate() == null ? null : order.getSipStartDate().toString());
        snap.put("sipInstalments", order.getSipInstalments());
        snap.put("mandateMode", order.getMandateMode());
        snap.put("paymentMode", order.getPaymentMode());
        return toJson(snap);
    }

    /** Stable-key snapshot of a redemption (amount and/or units). */
    public String renderRedemptionSnapshot(RedemptionRecord redemption) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("transactionType", "REDEMPTION");
        snap.put("redemptionId", str(redemption.getId()));
        snap.put("orderId", str(redemption.getOrderId()));
        snap.put("investorId", str(redemption.getInvestorId()));
        snap.put("units", plain(redemption.getUnits()));
        snap.put("amount", plain(redemption.getAmount()));
        return toJson(snap);
    }

    /**
     * The exact consent text frozen into the challenge as immutable evidence
     * (Phase-2 plan §"Locked decisions" #6: the rendered text is stored, not the
     * placeholder source). Reuses the snapshot so the consent and snapshot hashes
     * cannot diverge.
     */
    public String renderConsentText(TransactionApprovalChallenge challenge) {
        return "I, the investor, authorise this " + challenge.getTransactionType()
                + " transaction. I have reviewed the transaction details shown above and approve "
                + "their execution. This approval is recorded with a one-time passcode sent to my "
                + "verified email as a second factor of authentication. (template "
                + CONSENT_TEMPLATE_VERSION + ", snapshot " + challenge.getSnapshotSha256() + ")";
    }

    // ---- requestApprovalOtp -------------------------------------------------

    /**
     * Sends (or resends) the approval OTP to the investor's verified email,
     * moving {@code PENDING → CHALLENGE_SENT}, incrementing {@code deliveryAttempts}
     * and setting {@code expiresAt}. Returns an {@link OtpRequestResponse}, which
     * never carries the live code — so a distributor resend cannot leak it.
     *
     * @throws EntityNotFoundException if the challenge or account is missing
     * @throws IllegalStateException   if the challenge is terminal (not live)
     */
    @Transactional
    public OtpRequestResponse requestApprovalOtp(
            UUID challengeId, UUID actorAccountId, boolean isDistributorResend) {
        TransactionApprovalChallenge challenge = loadChallenge(challengeId);
        if (challenge.getStatus() != TransactionApprovalStatus.PENDING
                && challenge.getStatus() != TransactionApprovalStatus.CHALLENGE_SENT) {
            throw new IllegalStateException("This approval challenge can no longer receive a code.");
        }
        InvestorAccount account = accountRepository.findById(challenge.getInvestorAccountId())
                .orElseThrow(() -> new EntityNotFoundException("Investor account not found."));

        // Bind the code to THIS challenge so a sibling challenge (same email+purpose)
        // cannot cross-consume it (see TransactionApprovalServiceTest cross-consume case).
        OtpRequestResponse response = otpService.requestOtp(
                account.getEmail(), OtpPurpose.TRANSACTION_APPROVAL, challenge.getId());

        challenge.setStatus(TransactionApprovalStatus.CHALLENGE_SENT);
        challenge.setDeliveryAttempts(
                (challenge.getDeliveryAttempts() == null ? 0 : challenge.getDeliveryAttempts()) + 1);
        challenge.setExpiresAt(OffsetDateTime.now().plusSeconds(response.expiresInSeconds()));
        challengeRepository.save(challenge);

        auditService.log("TRANSACTION_APPROVAL", challenge.getTransactionId(),
                isDistributorResend ? "APPROVAL_OTP_RESENT" : "APPROVAL_OTP_REQUESTED",
                actorAccountId,
                "{\"challengeId\":\"" + challenge.getId() + "\",\"attempt\":"
                        + challenge.getDeliveryAttempts() + ",\"distributorResend\":"
                        + isDistributorResend + "}");
        // Returned as-is: OtpRequestResponse hides the code in deployed/demo envs.
        return response;
    }

    // ---- approve ------------------------------------------------------------

    /**
     * The investor approves the exact transaction they reviewed. Verifies (in
     * order): challenge is {@code CHALLENGE_SENT} and not expired; the account owns
     * the transaction; consent was accepted; the OTP is valid (burned by
     * {@link OtpService#verify}). On success records the consent evidence, links it,
     * captures ip/ua/session, and moves {@code → APPROVED}.
     *
     * @throws EntityNotFoundException if the challenge/account is missing
     * @throws IllegalStateException   if the challenge is not awaiting OTP or expired
     * @throws AccessDeniedException   if the account does not own the transaction
     * @throws IllegalArgumentException if consent was not accepted
     * @throws org.springframework.security.authentication.BadCredentialsException on bad/expired OTP
     */
    @Transactional
    public TransactionApprovalChallenge approve(
            UUID challengeId, UUID investorAccountId, String otpCode, boolean consentAccepted,
            String ip, String ua, String sessionId) {
        TransactionApprovalChallenge challenge = loadChallenge(challengeId);

        if (challenge.getStatus() != TransactionApprovalStatus.CHALLENGE_SENT) {
            throw new IllegalStateException("This approval challenge is not awaiting a code.");
        }
        if (challenge.getExpiresAt() != null && challenge.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalStateException("This approval has expired. Request a new one.");
        }

        InvestorAccount account = accountRepository.findById(investorAccountId)
                .orElseThrow(() -> new EntityNotFoundException("Investor account not found."));
        requireOwnership(account, challenge.getInvestorId());

        if (!consentAccepted) {
            throw new IllegalArgumentException("You must accept the consent to approve this transaction.");
        }

        // Verifies + burns the code BOUND to this challenge; throws BadCredentialsException
        // (401) on failure. The challengeId binding prevents accepting a sibling
        // challenge's code (cross-consume).
        otpService.verify(account.getEmail(), OtpPurpose.TRANSACTION_APPROVAL, otpCode, challenge.getId());

        ConsentRecord consent = consentRecordService.record(
                ConsentRecordService.SUBJECT_INVESTOR, investorAccountId,
                consentKeyFor(challenge.getTransactionType()), challenge.getConsentTemplateVersion(),
                challenge.getConsentRenderedText(), ip, ua);

        challenge.setStatus(TransactionApprovalStatus.APPROVED);
        challenge.setApprovedAt(OffsetDateTime.now());
        challenge.setConsentRecordId(consent.getId());
        challenge.setIpAddress(truncate(ip, 64));
        challenge.setUserAgent(truncate(ua, 512));
        challenge.setSessionId(truncate(sessionId, 128));
        TransactionApprovalChallenge saved = challengeRepository.save(challenge);

        auditService.log("TRANSACTION_APPROVAL", challenge.getTransactionId(), "APPROVAL_APPROVED",
                investorAccountId,
                "{\"challengeId\":\"" + challenge.getId() + "\",\"consentRecordId\":\""
                        + consent.getId() + "\",\"hash\":\"" + challenge.getSnapshotSha256() + "\"}");
        return saved;
    }

    // ---- assertApprovedAndConsume (the atomic hard gate) --------------------

    /**
     * The single atomic gate planted as the FIRST provider-touching statement inside
     * each provider-write {@code @Transactional} method. In one call it BOTH verifies
     * that an {@code APPROVED} challenge exists for the transaction whose frozen
     * {@code snapshotSha256} still equals the freshly-recomputed hash (nothing changed
     * since the investor approved), AND flips that challenge {@code APPROVED → CONSUMED}.
     *
     * <p>Atomicity is what makes the gate both exactly-once and retry-safe:
     * <ul>
     *   <li><b>Exactly-once</b> — only an {@code APPROVED} challenge can pass; a
     *       {@code SUPERSEDED}/{@code EXPIRED}/already-{@code CONSUMED} challenge is not
     *       found, so a replay throws.</li>
     *   <li><b>Retry-safe</b> — because the {@code CONSUMED} flip happens inside the
     *       caller's transaction immediately before the provider call, a provider
     *       exception rolls the flip back, leaving the challenge {@code APPROVED} for a
     *       genuine retry. A successful provider call commits the {@code CONSUMED} flip,
     *       so any later replay finds {@code CONSUMED} (not {@code APPROVED}) → blocked.</li>
     * </ul>
     *
     * @throws IllegalStateException if no {@code APPROVED} challenge exists or the hash drifted
     */
    @Transactional
    public void assertApprovedAndConsume(UUID transactionId, String recomputedSnapshotSha256) {
        TransactionApprovalChallenge approved = challengeRepository
                .findFirstByTransactionIdAndStatusOrderByCreatedAtDesc(
                        transactionId, TransactionApprovalStatus.APPROVED)
                .orElseThrow(() -> new IllegalStateException(
                        "Investor 2FA approval required: no approved approval exists for this transaction."));
        if (!Objects.equals(approved.getSnapshotSha256(), recomputedSnapshotSha256)) {
            throw new IllegalStateException(
                    "This transaction changed after the investor approved it; re-request approval.");
        }
        approved.setStatus(TransactionApprovalStatus.CONSUMED);
        approved.setConsumedAt(OffsetDateTime.now());
        challengeRepository.save(approved);
        auditService.log("TRANSACTION_APPROVAL", transactionId, "APPROVAL_CONSUMED",
                approved.getInvestorAccountId(),
                "{\"challengeId\":\"" + approved.getId() + "\"}");
    }

    // ---- supersedeOnEdit ----------------------------------------------------

    /**
     * Invalidates a live challenge after a distributor edits the underlying
     * transaction, forcing re-authorisation. No-op when nothing is live.
     */
    @Transactional
    public void supersedeOnEdit(UUID transactionId, UUID distributorActorId) {
        challengeRepository
                .findFirstByTransactionIdAndStatusInOrderByCreatedAtDesc(transactionId, LIVE_STATUSES)
                .filter(c -> LIVE_STATUSES.contains(c.getStatus()))
                .ifPresent(c -> {
                    c.setStatus(TransactionApprovalStatus.SUPERSEDED);
                    challengeRepository.save(c);
                    auditService.log("TRANSACTION_APPROVAL", transactionId, "APPROVAL_SUPERSEDED_ON_EDIT",
                            distributorActorId,
                            "{\"challengeId\":\"" + c.getId() + "\"}");
                });
    }

    // ---- listPendingForInvestor ---------------------------------------------

    /** All live (PENDING | CHALLENGE_SENT | APPROVED) challenges for an account. */
    @Transactional(readOnly = true)
    public List<TransactionApprovalChallenge> listPendingForInvestor(UUID investorAccountId) {
        return challengeRepository.findByInvestorAccountIdAndStatusIn(investorAccountId, LIVE_STATUSES);
    }

    /**
     * Reads a single challenge by id without throwing (the caller decides 404 vs 403).
     * Used by the investor Approval Center detail/otp/approve endpoints, which must
     * additionally enforce that the challenge belongs to the acting account.
     */
    @Transactional(readOnly = true)
    public Optional<TransactionApprovalChallenge> getChallenge(UUID challengeId) {
        return challengeRepository.findById(challengeId);
    }

    // ---- helpers ------------------------------------------------------------

    private TransactionApprovalChallenge loadChallenge(UUID challengeId) {
        return challengeRepository.findById(challengeId)
                .orElseThrow(() -> new EntityNotFoundException("Approval challenge not found."));
    }

    /** {@code account.investorId} must equal the transaction's investorId. */
    private void requireOwnership(InvestorAccount account, UUID transactionInvestorId) {
        if (account.getInvestorId() == null
                || transactionInvestorId == null
                || !account.getInvestorId().equals(transactionInvestorId)) {
            throw new AccessDeniedException("This transaction does not belong to your account.");
        }
    }

    private static String consentKeyFor(TransactionType type) {
        return switch (type) {
            case REDEMPTION -> "redemption_approval";
            case SIP -> "sip_approval";
            default -> "purchase_approval";
        };
    }

    private static String toJson(Map<String, Object> snapshot) {
        try {
            return SNAPSHOT_MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize the transaction snapshot.", ex);
        }
    }

    private static String str(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
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
