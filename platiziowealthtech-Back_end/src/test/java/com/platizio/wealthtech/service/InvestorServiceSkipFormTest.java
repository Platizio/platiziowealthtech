package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorLinkingStatus;
import com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge;
import com.platizio.wealthtech.domain.ProfileChangeApprovalStatus;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure-Mockito coverage of the InvestorService skip-form state machine (PA5b / R10):
 * INVESTOR_APPROVED → INVESTOR_SKIPPED → DISTRIBUTOR_FILLING → PENDING_PROFILE_APPROVAL → READY,
 * with the ProfileChangeApprovalService 2FA engine fully mocked. The apply path's
 * exactly-once hard gate (assertApprovedAndConsume FIRST) and replay-blocking are asserted.
 */
@ExtendWith(MockitoExtension.class)
class InvestorServiceSkipFormTest {

    @Mock private InvestorRepository investorRepository;
    @Mock private AuditService auditService;
    @Mock private ProfileChangeApprovalService profileChangeApprovalService;
    @Mock private com.platizio.wealthtech.repository.InvestorNomineeRepository investorNomineeRepository;
    @Mock private com.platizio.wealthtech.repository.InvestorBankAccountRepository investorBankAccountRepository;

    private InvestorService service;

    private final UUID investorId = UUID.randomUUID();
    private final UUID distributorId = UUID.randomUUID();
    private final UUID investorAccountId = UUID.randomUUID();
    private final UUID challengeId = UUID.randomUUID();
    // IRIS Phase 1: the frozen snapshot now carries the rich scalars + nominees array;
    // applyApprovedProfileFields must apply every one of them back (shared by Phase 2).
    private final String profileJson =
            "{\"dateOfBirth\":\"1990-01-01\",\"addressLine1\":\"12 MG Road\",\"city\":\"Pune\","
                    + "\"state\":\"MH\",\"postalCode\":\"411001\","
                    + "\"holdingMode\":\"single\",\"category\":\"resident_individual\",\"gender\":\"female\","
                    + "\"countryOfBirth\":\"India\",\"countryOfCitizenship\":\"India\","
                    + "\"taxResidentOtherCountry\":false,\"annualIncome\":\"upto_1lakh\","
                    + "\"occupation\":\"service\",\"sourceOfWealth\":\"salary\","
                    + "\"pep\":true,\"relativeOfPep\":false,\"displayNominees\":true,"
                    + "\"nominees\":[{\"nomineeIndex\":0,\"fullName\":\"Nom One\","
                    + "\"dateOfBirth\":\"2001-02-03\",\"relationship\":\"spouse\",\"sharePercent\":\"100.00\","
                    + "\"sameAsApplicant\":false}]}";
    private final String hash = ConsentRecordService.sha256(profileJson);

    @BeforeEach
    void setUp() {
        service = new InvestorService(
                investorRepository,
                investorBankAccountRepository,
                null,
                auditService,
                null,
                null,
                900_000L,
                null,
                null,
                null,
                profileChangeApprovalService);
        service.setInvestorNomineeRepository(investorNomineeRepository);
        lenient().when(investorRepository.save(any(Investor.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private Investor investor(InvestorLinkingStatus status, UUID linkedDistributorId) {
        Investor i = new Investor();
        ReflectionTestUtils.setField(i, "id", investorId);
        i.setLinkingStatus(status);
        i.setDistributorId(linkedDistributorId);
        return i;
    }

    private void stubInvestor(Investor i) {
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(i));
    }

    private ProfileChangeApprovalChallenge consumedChallenge() {
        ProfileChangeApprovalChallenge c = new ProfileChangeApprovalChallenge();
        ReflectionTestUtils.setField(c, "id", challengeId);
        c.setInvestorId(investorId);
        c.setStatus(ProfileChangeApprovalStatus.CONSUMED);
        c.setPendingProfileJson(profileJson);
        c.setProfileChangeSha256(hash);
        c.setConsumedAt(OffsetDateTime.now());
        return c;
    }

    // ---- approveAndSkipForm -------------------------------------------------

    @Test
    void approveAndSkipForm_fromInvestorApproved_movesToSkipped() {
        stubInvestor(investor(InvestorLinkingStatus.INVESTOR_APPROVED, distributorId));

        Investor result = service.approveAndSkipForm(investorId, investorAccountId);

        assertThat(result.getLinkingStatus()).isEqualTo(InvestorLinkingStatus.INVESTOR_SKIPPED);
        // distributor link from the link-approval is preserved.
        assertThat(result.getDistributorId()).isEqualTo(distributorId);
    }

    @Test
    void approveAndSkipForm_fromWrongState_throws() {
        stubInvestor(investor(InvestorLinkingStatus.READY, distributorId));

        assertThatThrownBy(() -> service.approveAndSkipForm(investorId, investorAccountId))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- submitProfileForInvestorReview -------------------------------------

    @Test
    void submitProfileForInvestorReview_happyPath_setsFillingAndCreatesChallenge() {
        stubInvestor(investor(InvestorLinkingStatus.INVESTOR_SKIPPED, distributorId));
        ProfileChangeApprovalChallenge challenge = mock(ProfileChangeApprovalChallenge.class);
        when(profileChangeApprovalService.createChallenge(investorId, profileJson, distributorId))
                .thenReturn(challenge);

        ProfileChangeApprovalChallenge result =
                service.submitProfileForInvestorReview(investorId, profileJson, distributorId);

        assertThat(result).isSameAs(challenge);
        verify(profileChangeApprovalService).createChallenge(investorId, profileJson, distributorId);
        ArgumentCaptorAssertLinkingStatus(InvestorLinkingStatus.DISTRIBUTOR_FILLING);
    }

    @Test
    void submitProfileForInvestorReview_fromWrongState_throwsAndCreatesNoChallenge() {
        stubInvestor(investor(InvestorLinkingStatus.INVESTOR_APPROVED, distributorId));

        assertThatThrownBy(() ->
                service.submitProfileForInvestorReview(investorId, profileJson, distributorId))
                .isInstanceOf(IllegalStateException.class);
        verify(profileChangeApprovalService, never()).createChallenge(any(), any(), any());
    }

    @Test
    void submitProfileForInvestorReview_wrongDistributor_throws403() {
        stubInvestor(investor(InvestorLinkingStatus.INVESTOR_SKIPPED, distributorId));
        UUID otherDistributor = UUID.randomUUID();

        assertThatThrownBy(() ->
                service.submitProfileForInvestorReview(investorId, profileJson, otherDistributor))
                .isInstanceOf(AccessDeniedException.class);
        verify(profileChangeApprovalService, never()).createChallenge(any(), any(), any());
    }

    // ---- updateProfile ------------------------------------------------------

    @Test
    void updateProfile_supersedesThenRecreatesChallenge() {
        stubInvestor(investor(InvestorLinkingStatus.DISTRIBUTOR_FILLING, distributorId));
        ProfileChangeApprovalChallenge challenge = mock(ProfileChangeApprovalChallenge.class);
        when(profileChangeApprovalService.createChallenge(investorId, profileJson, distributorId))
                .thenReturn(challenge);

        ProfileChangeApprovalChallenge result =
                service.updateProfile(investorId, profileJson, distributorId);

        assertThat(result).isSameAs(challenge);
        InOrder order = inOrder(profileChangeApprovalService);
        order.verify(profileChangeApprovalService).supersedeOnEdit(investorId, distributorId);
        order.verify(profileChangeApprovalService).createChallenge(investorId, profileJson, distributorId);
    }

    @Test
    void updateProfile_fromWrongState_throws() {
        stubInvestor(investor(InvestorLinkingStatus.INVESTOR_SKIPPED, distributorId));

        assertThatThrownBy(() -> service.updateProfile(investorId, profileJson, distributorId))
                .isInstanceOf(IllegalStateException.class);
        verify(profileChangeApprovalService, never()).supersedeOnEdit(any(), any());
    }

    // ---- approveProfileChange -----------------------------------------------

    @Test
    void approveProfileChange_delegatesAndMovesToPendingProfileApproval() {
        stubInvestor(investor(InvestorLinkingStatus.DISTRIBUTOR_FILLING, distributorId));

        Investor result = service.approveProfileChange(
                investorId, challengeId, investorAccountId, "123456", true, "1.2.3.4", "ua", "sess");

        assertThat(result.getLinkingStatus()).isEqualTo(InvestorLinkingStatus.PENDING_PROFILE_APPROVAL);
        verify(profileChangeApprovalService)
                .approve(challengeId, investorAccountId, "123456", true, "1.2.3.4", "ua", "sess");
    }

    // ---- applyProfileChange -------------------------------------------------

    @Test
    void applyProfileChange_happyPath_gateFirstThenCopiesFieldsAndReady() {
        stubInvestor(investor(InvestorLinkingStatus.PENDING_PROFILE_APPROVAL, distributorId));
        when(profileChangeApprovalService.listForInvestor(investorId))
                .thenReturn(List.of(consumedChallenge()));

        Investor result = service.applyProfileChange(investorId, hash);

        // Gate ran FIRST (before any listForInvestor read that copies fields).
        InOrder order = inOrder(profileChangeApprovalService);
        order.verify(profileChangeApprovalService).assertApprovedAndConsume(investorId, hash);
        order.verify(profileChangeApprovalService).listForInvestor(investorId);

        assertThat(result.getLinkingStatus()).isEqualTo(InvestorLinkingStatus.READY);
        // Approved profile fields copied onto the investor.
        assertThat(result.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(result.getAddressLine1()).isEqualTo("12 MG Road");
        assertThat(result.getCity()).isEqualTo("Pune");
        assertThat(result.getState()).isEqualTo("MH");
        assertThat(result.getPostalCode()).isEqualTo("411001");
        // IRIS Phase 1 scalars applied back from the same frozen snapshot.
        assertThat(result.getHoldingMode()).isEqualTo("single");
        assertThat(result.getCategory()).isEqualTo("resident_individual");
        assertThat(result.getGender()).isEqualTo("female");
        assertThat(result.getCountryOfBirth()).isEqualTo("India");
        assertThat(result.getCountryOfCitizenship()).isEqualTo("India");
        assertThat(result.getTaxResidentOtherCountry()).isFalse();
        assertThat(result.getAnnualIncome()).isEqualTo("upto_1lakh");
        assertThat(result.getOccupation()).isEqualTo("service");
        assertThat(result.getSourceOfWealth()).isEqualTo("salary");
        assertThat(result.getPep()).isTrue();
        assertThat(result.getRelativeOfPep()).isFalse();
        assertThat(result.getDisplayNominees()).isTrue();
        // Nominees upserted from the snapshot's "nominees" array (delete-then-insert).
        InOrder nomineeOrder = inOrder(investorNomineeRepository);
        nomineeOrder.verify(investorNomineeRepository).deleteByInvestorId(investorId);
        org.mockito.ArgumentCaptor<com.platizio.wealthtech.domain.InvestorNominee> nomineeCaptor =
                org.mockito.ArgumentCaptor.forClass(com.platizio.wealthtech.domain.InvestorNominee.class);
        nomineeOrder.verify(investorNomineeRepository).save(nomineeCaptor.capture());
        assertThat(nomineeCaptor.getValue().getFullName()).isEqualTo("Nom One");
        assertThat(nomineeCaptor.getValue().getInvestorId()).isEqualTo(investorId);
        assertThat(nomineeCaptor.getValue().getDateOfBirth()).isEqualTo(LocalDate.of(2001, 2, 3));
        assertThat(nomineeCaptor.getValue().getSharePercent()).isEqualByComparingTo("100.00");
    }

    /**
     * Phase 2: a distributor-filled profile carrying bank + contact persists those on approval —
     * the bank account is UPSERTED (not just frozen + shown in the diff), and mobile/email land on
     * the investor. Without applyBankAccount, distributor-entered bank details silently vanished.
     */
    @Test
    void applyProfileChange_distributorFillBank_upsertsBankAccountAndContact() {
        String bankJson = "{\"mobileNumber\":\"9876543210\",\"email\":\"new@x.com\","
                + "\"accountNumber\":\"123456789012\",\"ifsc\":\"hdfc0001234\",\"accountType\":\"Savings\"}";
        String bankHash = ConsentRecordService.sha256(bankJson);
        ProfileChangeApprovalChallenge ch = new ProfileChangeApprovalChallenge();
        ReflectionTestUtils.setField(ch, "id", challengeId);
        ch.setInvestorId(investorId);
        ch.setStatus(ProfileChangeApprovalStatus.CONSUMED);
        ch.setPendingProfileJson(bankJson);
        ch.setProfileChangeSha256(bankHash);
        ch.setConsumedAt(OffsetDateTime.now());

        stubInvestor(investor(InvestorLinkingStatus.PENDING_PROFILE_APPROVAL, distributorId));
        when(profileChangeApprovalService.listForInvestor(investorId)).thenReturn(List.of(ch));
        when(investorBankAccountRepository.findByInvestorId(investorId)).thenReturn(List.of());

        Investor result = service.applyProfileChange(investorId, bankHash);

        // contact applied onto the investor
        assertThat(result.getMobileNumber()).isEqualTo("9876543210");
        assertThat(result.getEmail()).isEqualTo("new@x.com");
        // bank account upserted (no existing → created), marked verification-pending
        org.mockito.ArgumentCaptor<com.platizio.wealthtech.domain.InvestorBankAccount> bankCaptor =
                org.mockito.ArgumentCaptor.forClass(com.platizio.wealthtech.domain.InvestorBankAccount.class);
        verify(investorBankAccountRepository).save(bankCaptor.capture());
        assertThat(bankCaptor.getValue().getInvestorId()).isEqualTo(investorId);
        assertThat(bankCaptor.getValue().getAccountNumber()).isEqualTo("123456789012");
        assertThat(bankCaptor.getValue().getIfscCode()).isEqualToIgnoringCase("HDFC0001234");
        assertThat(result.getBankVerificationStatus())
                .isEqualTo(com.platizio.wealthtech.domain.BankVerificationStatus.VERIFICATION_PENDING);
    }

    /**
     * Phase 2 confirming pass: the distributor-fill RICH payload (the 12 IRIS scalars +
     * a TWO-nominee array with a 60/40 share split) round-trips through the field-agnostic
     * apply path. Proves the distributor-fill parity — every scalar lands on the investor
     * AND both nominees are upserted (delete-then-insert) with the right per-index share —
     * with the same approval engine, no Phase-2-specific apply code. Non-vacuous: distinct
     * values, two saves, ordered index/share assertions.
     */
    @Test
    void applyProfileChange_distributorFillRichPayload_appliesAllScalarsAndUpsertsBothNominees() {
        String richProfileJson =
                "{\"dateOfBirth\":\"1985-12-25\",\"addressLine1\":\"7 Brigade Road\",\"city\":\"Bengaluru\","
                        + "\"state\":\"KA\",\"postalCode\":\"560001\","
                        + "\"holdingMode\":\"joint\",\"category\":\"nri\",\"gender\":\"male\","
                        + "\"countryOfBirth\":\"India\",\"countryOfCitizenship\":\"USA\","
                        + "\"taxResidentOtherCountry\":true,\"annualIncome\":\"above_1cr\","
                        + "\"occupation\":\"business\",\"sourceOfWealth\":\"business_income\","
                        + "\"pep\":false,\"relativeOfPep\":true,\"displayNominees\":true,"
                        + "\"nominees\":["
                        + "{\"nomineeIndex\":0,\"fullName\":\"Primary Nominee\",\"dateOfBirth\":\"1990-06-15\","
                        + "\"relationship\":\"spouse\",\"sharePercent\":\"60.00\",\"sameAsApplicant\":false},"
                        + "{\"nomineeIndex\":1,\"fullName\":\"Secondary Nominee\",\"dateOfBirth\":\"2010-03-09\","
                        + "\"relationship\":\"child\",\"sharePercent\":\"40.00\",\"sameAsApplicant\":false}"
                        + "]}";
        String richHash = ConsentRecordService.sha256(richProfileJson);

        ProfileChangeApprovalChallenge consumed = new ProfileChangeApprovalChallenge();
        ReflectionTestUtils.setField(consumed, "id", challengeId);
        consumed.setInvestorId(investorId);
        consumed.setStatus(ProfileChangeApprovalStatus.CONSUMED);
        consumed.setPendingProfileJson(richProfileJson);
        consumed.setProfileChangeSha256(richHash);
        consumed.setConsumedAt(OffsetDateTime.now());

        stubInvestor(investor(InvestorLinkingStatus.PENDING_PROFILE_APPROVAL, distributorId));
        when(profileChangeApprovalService.listForInvestor(investorId))
                .thenReturn(List.of(consumed));

        Investor result = service.applyProfileChange(investorId, richHash);

        // Gate ran FIRST (exactly-once), then the field copy read.
        InOrder order = inOrder(profileChangeApprovalService);
        order.verify(profileChangeApprovalService).assertApprovedAndConsume(investorId, richHash);
        order.verify(profileChangeApprovalService).listForInvestor(investorId);

        assertThat(result.getLinkingStatus()).isEqualTo(InvestorLinkingStatus.READY);
        // Every scalar from the distributor-filled snapshot landed on the investor.
        assertThat(result.getDateOfBirth()).isEqualTo(LocalDate.of(1985, 12, 25));
        assertThat(result.getAddressLine1()).isEqualTo("7 Brigade Road");
        assertThat(result.getCity()).isEqualTo("Bengaluru");
        assertThat(result.getState()).isEqualTo("KA");
        assertThat(result.getPostalCode()).isEqualTo("560001");
        assertThat(result.getHoldingMode()).isEqualTo("joint");
        assertThat(result.getCategory()).isEqualTo("nri");
        assertThat(result.getGender()).isEqualTo("male");
        assertThat(result.getCountryOfBirth()).isEqualTo("India");
        assertThat(result.getCountryOfCitizenship()).isEqualTo("USA");
        assertThat(result.getTaxResidentOtherCountry()).isTrue();
        assertThat(result.getAnnualIncome()).isEqualTo("above_1cr");
        assertThat(result.getOccupation()).isEqualTo("business");
        assertThat(result.getSourceOfWealth()).isEqualTo("business_income");
        assertThat(result.getPep()).isFalse();
        assertThat(result.getRelativeOfPep()).isTrue();
        assertThat(result.getDisplayNominees()).isTrue();

        // Both nominees upserted: delete-then-insert, then one save per nominee with the
        // right index/share, in array order.
        InOrder nomineeOrder = inOrder(investorNomineeRepository);
        nomineeOrder.verify(investorNomineeRepository).deleteByInvestorId(investorId);
        org.mockito.ArgumentCaptor<com.platizio.wealthtech.domain.InvestorNominee> nomineeCaptor =
                org.mockito.ArgumentCaptor.forClass(com.platizio.wealthtech.domain.InvestorNominee.class);
        nomineeOrder.verify(investorNomineeRepository, org.mockito.Mockito.times(2))
                .save(nomineeCaptor.capture());

        List<com.platizio.wealthtech.domain.InvestorNominee> saved = nomineeCaptor.getAllValues();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getInvestorId()).isEqualTo(investorId);
        assertThat(saved.get(0).getNomineeIndex()).isEqualTo(0);
        assertThat(saved.get(0).getFullName()).isEqualTo("Primary Nominee");
        assertThat(saved.get(0).getRelationship()).isEqualTo("spouse");
        assertThat(saved.get(0).getDateOfBirth()).isEqualTo(LocalDate.of(1990, 6, 15));
        assertThat(saved.get(0).getSharePercent()).isEqualByComparingTo("60.00");
        assertThat(saved.get(1).getInvestorId()).isEqualTo(investorId);
        assertThat(saved.get(1).getNomineeIndex()).isEqualTo(1);
        assertThat(saved.get(1).getFullName()).isEqualTo("Secondary Nominee");
        assertThat(saved.get(1).getRelationship()).isEqualTo("child");
        assertThat(saved.get(1).getDateOfBirth()).isEqualTo(LocalDate.of(2010, 3, 9));
        assertThat(saved.get(1).getSharePercent()).isEqualByComparingTo("40.00");
    }

    @Test
    void applyProfileChange_hashDrift_gateThrows_noProfileWrite() {
        // The gate is the first statement; a drift makes it throw before any read/copy.
        org.mockito.Mockito.doThrow(new IllegalStateException("hash drifted"))
                .when(profileChangeApprovalService).assertApprovedAndConsume(investorId, "different-hash");

        assertThatThrownBy(() -> service.applyProfileChange(investorId, "different-hash"))
                .isInstanceOf(IllegalStateException.class);
        // Never reached the field-copy read or saved the investor.
        verify(profileChangeApprovalService, never()).listForInvestor(any());
        verify(investorRepository, never()).findById(investorId);
        verify(investorRepository, never()).save(any(Investor.class));
    }

    @Test
    void applyProfileChange_replayAfterConsume_blockedByGate() {
        // A replay: the gate finds CONSUMED (not APPROVED) → throws; nothing is applied.
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "Investor 2FA approval required: no approved profile change exists for this investor."))
                .when(profileChangeApprovalService).assertApprovedAndConsume(investorId, hash);

        assertThatThrownBy(() -> service.applyProfileChange(investorId, hash))
                .isInstanceOf(IllegalStateException.class);
        verify(profileChangeApprovalService, never()).listForInvestor(any());
        verify(investorRepository, never()).save(any(Investor.class));
    }

    // ---- rejectProfileChange ------------------------------------------------

    @Test
    void rejectProfileChange_marksRejectedAndReturnsToSkipped() {
        stubInvestor(investor(InvestorLinkingStatus.PENDING_PROFILE_APPROVAL, distributorId));

        Investor result = service.rejectProfileChange(
                investorId, challengeId, "wrong city", investorAccountId);

        assertThat(result.getLinkingStatus()).isEqualTo(InvestorLinkingStatus.INVESTOR_SKIPPED);
        verify(profileChangeApprovalService).reject(challengeId, investorAccountId, "wrong city");
    }

    // ---- helper -------------------------------------------------------------

    private void ArgumentCaptorAssertLinkingStatus(InvestorLinkingStatus expected) {
        org.mockito.ArgumentCaptor<Investor> captor = org.mockito.ArgumentCaptor.forClass(Investor.class);
        verify(investorRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(saved -> saved.getLinkingStatus() == expected);
    }
}
