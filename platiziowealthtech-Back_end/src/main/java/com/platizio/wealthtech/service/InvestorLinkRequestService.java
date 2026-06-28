package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.InvestorLinkRequest;
import com.platizio.wealthtech.domain.InvestorLinkRequestStatus;
import com.platizio.wealthtech.domain.InvestorLinkingStatus;
import com.platizio.wealthtech.domain.NotificationType;
import com.platizio.wealthtech.domain.OnboardingSubmission;
import com.platizio.wealthtech.repository.DistributorRepository;
import com.platizio.wealthtech.repository.InvestorLinkRequestRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.validation.PanFormat;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives the investor email-approval link (investor.md §3 state machine, §6.1/§6.2, R3/R5).
 *
 * <p>A distributor "Sends to Investor" the Step-1 basic identity: a token-addressed
 * {@link InvestorLinkRequest} is minted and the investor is parked in
 * {@code PENDING_INVESTOR_APPROVAL} — {@code distributor_id} is NOT linked yet (R5). When the
 * investor approves from the emailed link, this is the moment the distributor is linked:
 * {@code distributor_id ← pending_distributor_id} and {@code linking_status = INVESTOR_APPROVED}.
 *
 * <p>The actual email send and the investor-portal attestation step are owned by later tracks;
 * this service only mints/returns the token and flips the linking state.
 */
@Service
public class InvestorLinkRequestService {

    /** Link expiry window (D10): 7 days. */
    private static final long LINK_TTL_DAYS = 7L;

    private static final Logger logger = LoggerFactory.getLogger(InvestorLinkRequestService.class);

    private final InvestorLinkRequestRepository linkRequestRepository;
    private final InvestorRepository investorRepository;
    private final OnboardingSubmissionService onboardingSubmissionService;
    private final InvestorAccountOwnershipGuard ownershipGuard;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final DistributorRepository distributorRepository;
    private final String frontendOrigin;

    public InvestorLinkRequestService(
            InvestorLinkRequestRepository linkRequestRepository,
            InvestorRepository investorRepository,
            OnboardingSubmissionService onboardingSubmissionService,
            InvestorAccountOwnershipGuard ownershipGuard,
            NotificationService notificationService,
            EmailService emailService,
            DistributorRepository distributorRepository,
            @Value("${app.frontend.origin:http://localhost:3000}") String frontendOrigin) {
        this.linkRequestRepository = linkRequestRepository;
        this.investorRepository = investorRepository;
        this.onboardingSubmissionService = onboardingSubmissionService;
        this.ownershipGuard = ownershipGuard;
        this.notificationService = notificationService;
        this.emailService = emailService;
        this.distributorRepository = distributorRepository;
        this.frontendOrigin = frontendOrigin;
    }

    /**
     * Distributor "Send to Investor" (T1): park the investor in {@code PENDING_INVESTOR_APPROVAL}
     * with {@code pending_distributor_id} set but {@code distributor_id} left unchanged (NOT linked
     * yet — R5), supersede any prior live request, freeze the Step-1 payload into an
     * {@link OnboardingSubmission} awaiting investor review, and mint a fresh token-addressed
     * link request (PENDING, 7-day expiry). Returns the request (carrying the raw token) so the
     * caller can build the email link — the email send itself is a later task.
     */
    @Transactional
    public InvestorLinkRequest sendToInvestor(UUID investorId, UUID distributorId, String payloadJson) {
        // SEC-3: take a pessimistic-write lock on the investor row up front so concurrent
        // "Send to Investor" calls for the same investor serialize (the partial-unique index
        // already blocks dual live PENDING rows; this makes the contention graceful).
        Investor investor = investorRepository.findForUpdateById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found: " + investorId));

        investor.setLinkingStatus(InvestorLinkingStatus.PENDING_INVESTOR_APPROVAL);
        investor.setPendingDistributorId(distributorId);
        // R5: do NOT link distributor_id yet — that happens only at approval.
        investorRepository.save(investor);

        supersedeLive(investorId);

        OnboardingSubmission submission =
                onboardingSubmissionService.submitForInvestorReview(investorId, payloadJson, distributorId);

        InvestorLinkRequest request = new InvestorLinkRequest();
        request.setInvestorId(investorId);
        request.setPan(investor.getPan());
        request.setPendingDistributorId(distributorId);
        request.setToken(UUID.randomUUID().toString().replace("-", ""));
        request.setStatus(InvestorLinkRequestStatus.PENDING);
        request.setOnboardingSubmissionId(submission.getId());
        request.setExpiresAt(OffsetDateTime.now().plusDays(LINK_TTL_DAYS));
        InvestorLinkRequest saved = linkRequestRepository.save(request);

        // R3: email the investor a link to review + approve. Best-effort — a mail failure must
        // never roll back the (already-saved) link request, and when SMTP is disabled sendHtml is
        // a no-op (the controller still logs the approval URL + returns the token for local dev).
        notifyInvestorByEmail(investor, distributorId, saved.getToken());
        return saved;
    }

    /** Best-effort approval-link email (R3). Never throws — the link request must persist regardless. */
    private void notifyInvestorByEmail(Investor investor, UUID distributorId, String token) {
        if (investor.getEmail() == null || investor.getEmail().isBlank()) {
            return;
        }
        String advisorName = distributorRepository.findById(distributorId)
                .map(d -> d.getFirmName() != null && !d.getFirmName().isBlank() ? d.getFirmName() : d.getFullName())
                .filter(n -> n != null && !n.isBlank())
                .orElse("your advisor");
        String approvalUrl = frontendOrigin + "/investor/approve?token=" + token;
        try {
            emailService.sendHtml(
                    investor.getEmail(),
                    "Approve your advisor on Platizio",
                    buildApprovalEmail(advisorName, approvalUrl));
        } catch (RuntimeException ex) {
            logger.warn("approval_email status='failed' investor_id='{}' reason='{}'",
                    investor.getId(), ex.getMessage());
        }
    }

    private String buildApprovalEmail(String advisorName, String approvalUrl) {
        return """
                <div style="font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:auto;color:#0B1B3E">
                  <h2 style="margin-bottom:4px">Platizio</h2>
                  <p style="color:#475569;font-size:14px">%s has invited you onto Platizio. Review the details they
                     entered and approve to link your account. This link expires in 7 days.</p>
                  <a href="%s" style="display:inline-block;background:#0B1B3E;color:#ffffff;text-decoration:none;
                     font-weight:600;border-radius:10px;padding:12px 22px;margin:16px 0">Review &amp; Approve</a>
                  <p style="color:#94a3b8;font-size:12px">Or paste this link into your browser:<br>%s</p>
                  <p style="color:#94a3b8;font-size:12px">If you didn't expect this, you can safely ignore this email.</p>
                </div>
                """.formatted(advisorName, approvalUrl, approvalUrl);
    }

    /**
     * Investor approves from the email link (T2, R5): assert the request is PENDING and not expired,
     * then link the distributor — {@code distributor_id ← pending_distributor_id},
     * {@code linking_status = INVESTOR_APPROVED} — and mark the request APPROVED. This is the moment
     * the investor↔distributor link is established by PAN.
     */
    @Transactional
    public InvestorLinkRequest approveByToken(UUID investorAccountId, String token) {
        InvestorLinkRequest request = linkRequestRepository.findByToken(token)
                .orElseThrow(() -> new EntityNotFoundException("Approval link not found or no longer valid."));
        if (request.getStatus() != InvestorLinkRequestStatus.PENDING) {
            throw new IllegalStateException("This approval link is not awaiting your approval.");
        }
        if (request.getExpiresAt().isBefore(OffsetDateTime.now())) {
            // Make the lifecycle honest: an expired PENDING link transitions to EXPIRED
            // (instead of lingering as PENDING forever) so a re-send mints a fresh one.
            request.setStatus(InvestorLinkRequestStatus.EXPIRED);
            linkRequestRepository.save(request);
            throw new IllegalStateException("This approval link has expired. Ask your distributor to re-send it.");
        }

        // SEC-1/SEC-2: bind the approval to the account that actually owns this investor.
        // approveByToken previously ignored investorAccountId and resolved the investor purely
        // from the token, so any account that knew a token could approve a link meant for a
        // DIFFERENT investor (IDOR). Prove ownership, then PAN-bind the approval to the account.
        InvestorAccount account = ownershipGuard.assertOwns(investorAccountId, request.getInvestorId());
        if (!Objects.equals(normalizePan(account.getPan()), normalizePan(request.getPan()))) {
            throw new AccessDeniedException("Approval does not match your account.");
        }

        Investor investor = investorRepository.findById(request.getInvestorId())
                .orElseThrow(() -> new EntityNotFoundException("Investor not found: " + request.getInvestorId()));
        // R5: linking happens here.
        investor.setDistributorId(request.getPendingDistributorId());
        investor.setLinkingStatus(InvestorLinkingStatus.INVESTOR_APPROVED);
        investorRepository.save(investor);

        request.setStatus(InvestorLinkRequestStatus.APPROVED);
        request.setApprovedAt(OffsetDateTime.now());
        InvestorLinkRequest approved = linkRequestRepository.save(request);

        // R8 (receive-half): notify the (now-linked) distributor in-app that the investor
        // approved the link. Same @Transactional boundary, so the notification commits
        // atomically with the APPROVED transition (or rolls back with it).
        notificationService.createForDistributor(
                approved.getPendingDistributorId(),
                approved.getInvestorId(),
                NotificationType.INVESTOR_LINK_APPROVED,
                "Investor approved your link",
                "The investor approved your onboarding link. They are now linked to you.");

        return approved;
    }

    /**
     * Investor declines the link (T2'), bound to the approving account: assert the account
     * owns this link (same PAN-bound ownership rule as {@link #approveByToken}) so an investor
     * cannot reject a link meant for a DIFFERENT investor (IDOR — SEC-1/SEC-2), then mark the
     * PENDING request REJECTED and set the investor's {@code linking_status = REJECTED}.
     * {@code distributor_id} stays unlinked.
     */
    @Transactional
    public void reject(UUID investorAccountId, String token) {
        InvestorLinkRequest request = linkRequestRepository.findByToken(token)
                .orElseThrow(() -> new EntityNotFoundException("Approval link not found or no longer valid."));
        if (request.getStatus() != InvestorLinkRequestStatus.PENDING) {
            throw new IllegalStateException("This approval link is not awaiting your approval.");
        }

        // Bind the rejection to the account that actually owns this investor (parity with
        // approveByToken): prove ownership, then PAN-bind, before flipping any state.
        InvestorAccount account = ownershipGuard.assertOwns(investorAccountId, request.getInvestorId());
        if (!Objects.equals(normalizePan(account.getPan()), normalizePan(request.getPan()))) {
            throw new AccessDeniedException("Approval does not match your account.");
        }

        request.setStatus(InvestorLinkRequestStatus.REJECTED);
        linkRequestRepository.save(request);

        Investor investor = investorRepository.findById(request.getInvestorId())
                .orElseThrow(() -> new EntityNotFoundException("Investor not found: " + request.getInvestorId()));
        investor.setLinkingStatus(InvestorLinkingStatus.REJECTED);
        investorRepository.save(investor);
    }

    /**
     * Normalizes a PAN for equality comparison using the same rule the rest of the code uses
     * ({@link PanFormat#normalize}: trim + uppercase, null on blank), so the approval PAN-bind
     * is case/whitespace insensitive and consistent with how PANs are stored/compared elsewhere.
     */
    private static String normalizePan(String value) {
        return PanFormat.normalize(value);
    }

    /** Supersede any existing live (PENDING) link request for the investor (one live request — D10). */
    private void supersedeLive(UUID investorId) {
        linkRequestRepository
                .findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(investorId, InvestorLinkRequestStatus.PENDING)
                .ifPresent(existing -> {
                    existing.setStatus(InvestorLinkRequestStatus.SUPERSEDED);
                    linkRequestRepository.save(existing);
                });
    }
}
