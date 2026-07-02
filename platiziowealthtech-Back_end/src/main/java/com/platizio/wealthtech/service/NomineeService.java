package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorNominee;
import com.platizio.wealthtech.dto.NomineeRequest;
import com.platizio.wealthtech.repository.InvestorNomineeRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nomination capability (REQUIREMENT #4). An investor either adds one or more
 * nominees (allocations summing to 100) or explicitly opts out of nominating.
 *
 * <p>Operates on the canonical rich {@link InvestorNominee} — the SAME entity/rows
 * the distributor/IRIS onboarding path writes via
 * {@code InvestorService.replaceNominees} — so nominee data captured by the
 * distributor carries forward to the investor's own nominee step with no
 * duplication or loss.
 *
 * <p>The {@code *AsInvestor} variants take only an {@code investorId}: the caller
 * (the investor portal controller) has already resolved that id from the
 * authenticated session, so the session itself proves ownership and no
 * distributor-ownership check is performed (mirrors the contact-verify /
 * KYC self-service pattern). Unlike KYC, nomination is purely local data, so we
 * just resolve the investor — no owning-distributor context is needed.
 */
@Service
public class NomineeService {

    /** Consent key recorded when an investor declines to nominate. */
    public static final String CONSENT_NOMINATION_OPT_OUT = "nomination_opt_out";

    private static final String OPT_OUT_CONSENT_TEXT =
            "I choose not to nominate anyone for my investment account at this time. "
                    + "I understand I can add a nominee later.";

    private static final BigDecimal FULL_ALLOCATION = new BigDecimal("100");

    private final InvestorNomineeRepository nomineeRepository;
    private final InvestorRepository investorRepository;
    private final ConsentRecordService consentRecordService;

    public NomineeService(
            InvestorNomineeRepository nomineeRepository,
            InvestorRepository investorRepository,
            ConsentRecordService consentRecordService) {
        this.nomineeRepository = nomineeRepository;
        this.investorRepository = investorRepository;
        this.consentRecordService = consentRecordService;
    }

    @Transactional(readOnly = true)
    public List<InvestorNominee> listNominees(UUID investorId) {
        return nomineeRepository.findByInvestorIdOrderByNomineeIndexAsc(investorId);
    }

    @Transactional(readOnly = true)
    public boolean isOptedOut(UUID investorId) {
        return Boolean.TRUE.equals(requireInvestor(investorId).getNominationOptedOut());
    }

    /**
     * Adds a nominee. Re-clears any prior opt-out (adding a nominee is itself a
     * decision to nominate) and validates that allocations across all live
     * nominees — including this new one — sum to at most 100.
     */
    @Transactional
    public InvestorNominee addNominee(UUID investorId, NomineeRequest request) {
        Investor investor = requireInvestor(investorId);

        InvestorNominee nominee = new InvestorNominee();
        nominee.setInvestorId(investorId);
        nominee.setFullName(request.fullName());
        nominee.setRelationship(request.relationship());
        nominee.setDateOfBirth(request.dateOfBirth());
        nominee.setSharePercent(request.sharePercent());
        nominee.setMobileNumber(request.mobileNumber());
        nominee.setEmail(request.email());
        nominee.setIdType(request.idType());
        nominee.setIdNumber(request.idNumber());
        nominee.setAddressLine1(request.addressLine1());
        nominee.setAddressLine2(request.addressLine2());
        nominee.setAddressLine3(request.addressLine3());
        nominee.setCity(request.city());
        nominee.setState(request.state());
        nominee.setPostalCode(request.postalCode());
        nominee.setCountry(request.country());
        nominee.setSameAsApplicant(Boolean.TRUE.equals(request.sameAsApplicant()));
        nominee.setGuardianName(request.guardianName());

        List<InvestorNominee> existing = nomineeRepository.findByInvestorIdOrderByNomineeIndexAsc(investorId);

        // Assign the next free position on the shared canonical investor_nominees table
        // (NOT NULL nominee_index, UNIQUE per investor) so self-service rows never collide
        // with IRIS-onboarding rows.
        int nextIndex = existing.stream()
                .map(InvestorNominee::getNomineeIndex)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(-1) + 1;
        nominee.setNomineeIndex(nextIndex);

        BigDecimal total = shareOf(nominee);
        for (InvestorNominee n : existing) {
            total = total.add(shareOf(n));
        }
        if (total.compareTo(FULL_ALLOCATION) > 0) {
            throw new IllegalArgumentException(
                    "Allocation percentages across nominees cannot exceed 100% (would be " + total + "%).");
        }

        InvestorNominee saved = nomineeRepository.save(nominee);

        // Adding a nominee supersedes any earlier opt-out.
        if (Boolean.TRUE.equals(investor.getNominationOptedOut())) {
            investor.setNominationOptedOut(Boolean.FALSE);
            investorRepository.save(investor);
        }
        return saved;
    }

    /**
     * Records an explicit decision NOT to nominate: flips
     * {@code investors.nomination_opted_out=true} and records an immutable consent
     * (SUBJECT_INVESTOR, key {@code nomination_opt_out}). Rejected when nominees
     * already exist — the investor must remove them first.
     */
    @Transactional
    public Investor optOut(UUID investorId, UUID subjectId, String ip, String userAgent) {
        Investor investor = requireInvestor(investorId);
        if (!nomineeRepository.findByInvestorIdOrderByNomineeIndexAsc(investorId).isEmpty()) {
            throw new IllegalArgumentException(
                    "You already have nominees on file. Remove them before opting out of nomination.");
        }
        investor.setNominationOptedOut(Boolean.TRUE);
        investorRepository.save(investor);
        consentRecordService.record(
                ConsentRecordService.SUBJECT_INVESTOR, subjectId, CONSENT_NOMINATION_OPT_OUT,
                "v1", OPT_OUT_CONSENT_TEXT, ip, userAgent);
        return investor;
    }

    /**
     * Validates that the investor's live nominees collectively allocate exactly
     * 100% (no-op when there are no nominees). Useful as a finalization gate.
     */
    @Transactional(readOnly = true)
    public void validateAllocationsComplete(UUID investorId) {
        List<InvestorNominee> nominees = nomineeRepository.findByInvestorIdOrderByNomineeIndexAsc(investorId);
        if (nominees.isEmpty()) {
            return;
        }
        BigDecimal total = nominees.stream()
                .map(NomineeService::shareOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(FULL_ALLOCATION) != 0) {
            throw new IllegalArgumentException(
                    "Allocation percentages across nominees must sum to 100% (currently " + total + "%).");
        }
    }

    // ── investor-self variants (the session already proves ownership) ──────────

    @Transactional(readOnly = true)
    public List<InvestorNominee> listNomineesAsInvestor(UUID investorId) {
        return listNominees(investorId);
    }

    @Transactional(readOnly = true)
    public boolean isOptedOutAsInvestor(UUID investorId) {
        return isOptedOut(investorId);
    }

    @Transactional
    public InvestorNominee addNomineeAsInvestor(UUID investorId, NomineeRequest request) {
        return addNominee(investorId, request);
    }

    @Transactional
    public Investor optOutAsInvestor(UUID investorId, UUID subjectId, String ip, String userAgent) {
        return optOut(investorId, subjectId, ip, userAgent);
    }

    private static BigDecimal shareOf(InvestorNominee n) {
        return n.getSharePercent() == null ? BigDecimal.ZERO : n.getSharePercent();
    }

    private Investor requireInvestor(UUID investorId) {
        return investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor profile not found"));
    }
}
