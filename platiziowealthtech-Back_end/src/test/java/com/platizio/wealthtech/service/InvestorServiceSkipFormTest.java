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
                null,
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
