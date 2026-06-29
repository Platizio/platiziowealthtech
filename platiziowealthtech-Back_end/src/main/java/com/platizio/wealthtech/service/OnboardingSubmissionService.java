package com.platizio.wealthtech.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.Nominee;
import com.platizio.wealthtech.domain.OnboardingSubmission;
import com.platizio.wealthtech.domain.OnboardingSubmissionStatus;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.NomineeRepository;
import com.platizio.wealthtech.repository.OnboardingSubmissionRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the investor-approval gate on distributor-assembled onboarding
 * (SRS FR-ONB-002/003): a frozen, hashed revision must be investor-attested
 * before the distributor can finalize, and any post-attestation edit invalidates
 * the attestation.
 *
 * <p>Compliance: the investor attests <em>everything</em> the distributor entered,
 * not just identity. So before freezing/hashing a revision, the caller-supplied
 * payload (identity/address/contact) is widened here with the distributor-entered
 * bank account(s), FATCA declaration, and nominee details — see
 * {@link #widenSnapshot(UUID, String)}. The same widening is re-applied when the
 * finalize gate recomputes the live hash, so a post-attestation edit to ANY of
 * those fields (not only identity) invalidates the approval.
 */
@Service
public class OnboardingSubmissionService {

    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

    private final OnboardingSubmissionRepository repository;
    private final AuditService auditService;
    private final InvestorRepository investorRepository;
    private final InvestorBankAccountRepository bankAccountRepository;
    private final NomineeRepository nomineeRepository;

    public OnboardingSubmissionService(
            OnboardingSubmissionRepository repository,
            AuditService auditService,
            InvestorRepository investorRepository,
            InvestorBankAccountRepository bankAccountRepository,
            NomineeRepository nomineeRepository) {
        this.repository = repository;
        this.auditService = auditService;
        this.investorRepository = investorRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.nomineeRepository = nomineeRepository;
    }

    /** Distributor freezes the current payload into a new revision awaiting investor review. */
    @Transactional
    public OnboardingSubmission submitForInvestorReview(UUID investorId, String payloadJson, UUID distributorActorId) {
        supersedeLive(investorId);
        int nextRevision = repository.findFirstByInvestorIdOrderByRevisionNoDesc(investorId)
                .map(s -> s.getRevisionNo() + 1)
                .orElse(1);
        // Widen the attested payload to cover bank/FATCA/nominee, so the investor is
        // attesting EVERYTHING the distributor entered (not just identity/address/contact).
        String widenedPayload = widenSnapshot(investorId, payloadJson);
        OnboardingSubmission submission = new OnboardingSubmission();
        submission.setInvestorId(investorId);
        submission.setRevisionNo(nextRevision);
        submission.setStatus(OnboardingSubmissionStatus.DRAFT_AWAITING_INVESTOR);
        submission.setPayloadJson(widenedPayload);
        submission.setContentSha256(ConsentRecordService.sha256(widenedPayload));
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

    /** The exact frozen revision a link request points at (for the investor review screen). */
    @Transactional(readOnly = true)
    public Optional<OnboardingSubmission> findById(UUID submissionId) {
        return submissionId == null ? Optional.empty() : repository.findById(submissionId);
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
     * still equals the currently-rendered (widened) payload hash (i.e. nothing
     * changed since the investor approved).
     *
     * <p>The hash is recomputed here from the LIVE investor + bank/FATCA/nominee
     * data via {@link #widenSnapshot(UUID, String)} so the gate covers everything
     * the investor attested. The caller-supplied {@code currentRenderedHash} is the
     * narrow identity-only hash and is retained only for signature compatibility;
     * it is intentionally not trusted (it would never match the widened ATTESTED
     * hash). A post-attestation edit to identity, address, contact, bank, FATCA, or
     * a nominee now invalidates finalize.
     */
    @Transactional(readOnly = true)
    public void assertFinalizable(UUID investorId, String currentRenderedHash) {
        OnboardingSubmission latest = latestRevisionForInvestor(investorId).orElseThrow(() ->
                new IllegalStateException("Investor approval required: this onboarding has not been sent for investor review."));
        if (latest.getStatus() != OnboardingSubmissionStatus.ATTESTED) {
            throw new IllegalStateException("Investor approval required before this onboarding can be finalized.");
        }
        // Re-widen the live attested revision's frozen payload (re-fetching bank/FATCA/nominee)
        // and compare to the ATTESTED hash; any drift means the data changed post-approval.
        String liveWidened = widenSnapshot(investorId, stripWidenedKeys(latest.getPayloadJson()));
        if (!latest.getContentSha256().equals(ConsentRecordService.sha256(liveWidened))) {
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

    // ── snapshot widening (compliance: the investor attests EVERYTHING entered) ──

    /**
     * Augments the caller-supplied identity/address/contact payload object with the
     * distributor-entered bank account(s), FATCA declaration, and nominee details so
     * the attestation hash covers the full set of details the distributor entered.
     * Deterministic: keys are inserted in a stable order and lists are sorted, so the
     * same data always yields the same JSON (and therefore the same hash).
     *
     * <p>If the supplied payload is not a JSON object (defensive), it is returned
     * unchanged so hashing/attestation still works on the original content.
     */
    private String widenSnapshot(UUID investorId, String payloadJson) {
        try {
            var node = SNAPSHOT_MAPPER.readTree(payloadJson == null ? "{}" : payloadJson);
            if (!node.isObject()) {
                return payloadJson;
            }
            ObjectNode root = (ObjectNode) node;
            root.set("bankAccounts", bankAccountsNode(investorId));
            root.set("fatca", fatcaNode(investorId));
            root.set("nominees", nomineesNode(investorId));
            return SNAPSHOT_MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to widen onboarding snapshot", ex);
        }
    }

    /**
     * Removes the widened keys from a previously-widened payload so {@link #widenSnapshot}
     * can re-apply them from live data without double-nesting. Returns the input unchanged
     * if it is not a JSON object.
     */
    private String stripWidenedKeys(String payloadJson) {
        try {
            var node = SNAPSHOT_MAPPER.readTree(payloadJson == null ? "{}" : payloadJson);
            if (!node.isObject()) {
                return payloadJson;
            }
            ObjectNode root = (ObjectNode) node;
            root.remove("bankAccounts");
            root.remove("fatca");
            root.remove("nominees");
            return SNAPSHOT_MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to normalise onboarding snapshot", ex);
        }
    }

    /** Deterministic array of the investor's bank accounts (key fields only), sorted stably. */
    private com.fasterxml.jackson.databind.JsonNode bankAccountsNode(UUID investorId) {
        var array = SNAPSHOT_MAPPER.createArrayNode();
        List<InvestorBankAccount> accounts = new ArrayList<>(bankAccountRepository.findByInvestorId(investorId));
        accounts.sort(Comparator
                .comparing((InvestorBankAccount b) -> nullSafe(b.getIfscCode()))
                .thenComparing(b -> nullSafe(b.getAccountNumber())));
        for (InvestorBankAccount b : accounts) {
            ObjectNode o = SNAPSHOT_MAPPER.createObjectNode();
            o.put("accountHolderName", b.getAccountHolderName());
            o.put("accountNumber", b.getAccountNumber());
            o.put("ifscCode", b.getIfscCode());
            o.put("bankName", b.getBankName());
            o.put("branchName", b.getBranchName());
            o.put("accountType", b.getAccountType());
            array.add(o);
        }
        return array;
    }

    /**
     * Deterministic FATCA declaration node. The FATCA/tax-residency declaration is
     * captured on the investor's {@code onboardingNotes} as {@code key=value;...}
     * pairs (same encoding the Cybrilla client reads); the keys that make up the
     * declaration the investor attests are mirrored here.
     */
    private com.fasterxml.jackson.databind.JsonNode fatcaNode(UUID investorId) {
        ObjectNode o = SNAPSHOT_MAPPER.createObjectNode();
        Investor investor = investorRepository.findById(investorId).orElse(null);
        String notes = investor == null ? null : investor.getOnboardingNotes();
        o.put("taxResidencyOtherThanIndia", onboardingNoteValue(notes, "tax_residency"));
        o.put("pep", onboardingNoteValue(notes, "pep"));
        o.put("occupation", onboardingNoteValue(notes, "occupation"));
        o.put("income", onboardingNoteValue(notes, "income"));
        return o;
    }

    /** Deterministic array of the investor's nominees (or the opt-out flag), sorted stably. */
    private com.fasterxml.jackson.databind.JsonNode nomineesNode(UUID investorId) {
        ObjectNode wrapper = SNAPSHOT_MAPPER.createObjectNode();
        Investor investor = investorRepository.findById(investorId).orElse(null);
        boolean optedOut = investor != null && Boolean.TRUE.equals(investor.getNominationOptedOut());
        wrapper.put("nominationOptedOut", optedOut);
        var array = SNAPSHOT_MAPPER.createArrayNode();
        List<Nominee> nominees = new ArrayList<>(nomineeRepository.findByInvestorId(investorId));
        nominees.sort(Comparator
                .comparing((Nominee n) -> nullSafe(n.getFullName()))
                .thenComparing(n -> nullSafe(n.getRelationship())));
        for (Nominee n : nominees) {
            ObjectNode o = SNAPSHOT_MAPPER.createObjectNode();
            o.put("fullName", n.getFullName());
            o.put("relationship", n.getRelationship());
            o.put("dateOfBirth", n.getDateOfBirth() == null ? null : n.getDateOfBirth().toString());
            o.put("allocationPercentage", n.getAllocationPercentage());
            o.put("addressLine", n.getAddressLine());
            o.put("guardianName", n.getGuardianName());
            array.add(o);
        }
        wrapper.set("entries", array);
        return wrapper;
    }

    /** Reads one {@code key=value} from a {@code key=value;...} onboarding-notes string. */
    private String onboardingNoteValue(String notes, String key) {
        if (notes == null || notes.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        for (String part : notes.split(";")) {
            String trimmed = part.trim();
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            if (key.equalsIgnoreCase(trimmed.substring(0, separator).trim())) {
                return trimmed.substring(separator + 1).trim();
            }
        }
        return null;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
