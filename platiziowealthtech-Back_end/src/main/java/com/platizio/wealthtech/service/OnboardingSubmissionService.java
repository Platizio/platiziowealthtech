package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.OnboardingSubmission;
import com.platizio.wealthtech.domain.OnboardingSubmissionStatus;
import com.platizio.wealthtech.repository.OnboardingSubmissionRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the investor-approval gate on distributor-assembled onboarding
 * (SRS FR-ONB-002/003): a frozen, hashed revision must be investor-attested
 * before the distributor can finalize, and any post-attestation edit invalidates
 * the attestation.
 */
@Service
public class OnboardingSubmissionService {

    private final OnboardingSubmissionRepository repository;
    private final AuditService auditService;

    public OnboardingSubmissionService(OnboardingSubmissionRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /** Distributor freezes the current payload into a new revision awaiting investor review. */
    @Transactional
    public OnboardingSubmission submitForInvestorReview(UUID investorId, String payloadJson, UUID distributorActorId) {
        supersedeLive(investorId);
        int nextRevision = repository.findFirstByInvestorIdOrderByRevisionNoDesc(investorId)
                .map(s -> s.getRevisionNo() + 1)
                .orElse(1);
        OnboardingSubmission submission = new OnboardingSubmission();
        submission.setInvestorId(investorId);
        submission.setRevisionNo(nextRevision);
        submission.setStatus(OnboardingSubmissionStatus.DRAFT_AWAITING_INVESTOR);
        submission.setPayloadJson(payloadJson);
        submission.setContentSha256(ConsentRecordService.sha256(payloadJson));
        submission.setSubmittedBy(distributorActorId);
        submission.setSubmittedAt(OffsetDateTime.now());
        OnboardingSubmission saved = repository.save(submission);
        auditService.log("INVESTOR", investorId, "ONBOARDING_SUBMITTED_FOR_REVIEW", distributorActorId,
                "{\"revision\":" + nextRevision + ",\"hash\":\"" + saved.getContentSha256() + "\"}");
        return saved;
    }

    /** The current live (non-superseded) revision, if any. */
    @Transactional(readOnly = true)
    public Optional<OnboardingSubmission> latestRevisionForInvestor(UUID investorId) {
        return repository.findFirstByInvestorIdAndStatusNotOrderByRevisionNoDesc(
                investorId, OnboardingSubmissionStatus.SUPERSEDED);
    }

    /** Investor attests the exact revision they reviewed (rejects a stale hash). */
    @Transactional
    public OnboardingSubmission attest(
            UUID investorId, UUID investorAccountId, String attestedHash, String ip, String userAgent) {
        OnboardingSubmission latest = latestRevisionForInvestor(investorId)
                .orElseThrow(() -> new EntityNotFoundException("No onboarding submission is awaiting your approval."));
        if (latest.getStatus() != OnboardingSubmissionStatus.DRAFT_AWAITING_INVESTOR) {
            throw new IllegalStateException("This submission is not awaiting investor approval.");
        }
        if (!latest.getContentSha256().equals(attestedHash)) {
            throw new IllegalStateException(
                    "The submission changed since you reviewed it. Reload and approve the latest version.");
        }
        latest.setStatus(OnboardingSubmissionStatus.ATTESTED);
        latest.setAttestedAt(OffsetDateTime.now());
        latest.setAttestedBy(investorAccountId);
        latest.setAttestationIp(truncate(ip, 64));
        latest.setAttestationUa(truncate(userAgent, 512));
        repository.save(latest);
        auditService.log("INVESTOR", investorId, "ONBOARDING_ATTESTED", investorAccountId,
                "{\"revision\":" + latest.getRevisionNo() + ",\"hash\":\"" + attestedHash + "\"}");
        return latest;
    }

    /**
     * The hard gate: throws unless the latest revision is ATTESTED and its hash
     * still equals the currently-rendered payload hash (i.e. nothing changed
     * since the investor approved).
     */
    @Transactional(readOnly = true)
    public void assertFinalizable(UUID investorId, String currentRenderedHash) {
        OnboardingSubmission latest = latestRevisionForInvestor(investorId).orElseThrow(() ->
                new IllegalStateException("Investor approval required: this onboarding has not been sent for investor review."));
        if (latest.getStatus() != OnboardingSubmissionStatus.ATTESTED) {
            throw new IllegalStateException("Investor approval required before this onboarding can be finalized.");
        }
        if (!latest.getContentSha256().equals(currentRenderedHash)) {
            throw new IllegalStateException(
                    "This onboarding changed after the investor approved it; re-submit it for investor approval.");
        }
    }

    /** Invalidate an ATTESTED revision after a distributor edits KYC-material data. */
    @Transactional
    public void invalidateAttestationOnEdit(UUID investorId, UUID distributorActorId) {
        latestRevisionForInvestor(investorId)
                .filter(s -> s.getStatus() == OnboardingSubmissionStatus.ATTESTED)
                .ifPresent(s -> {
                    s.setStatus(OnboardingSubmissionStatus.SUPERSEDED);
                    repository.save(s);
                    auditService.log("INVESTOR", investorId, "ONBOARDING_ATTESTATION_INVALIDATED", distributorActorId,
                            "{\"revision\":" + s.getRevisionNo() + "}");
                });
    }

    private void supersedeLive(UUID investorId) {
        latestRevisionForInvestor(investorId).ifPresent(s -> {
            s.setStatus(OnboardingSubmissionStatus.SUPERSEDED);
            repository.save(s);
        });
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
