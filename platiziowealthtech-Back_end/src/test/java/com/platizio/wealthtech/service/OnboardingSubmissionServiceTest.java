package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.OnboardingSubmission;
import com.platizio.wealthtech.domain.OnboardingSubmissionStatus;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.OnboardingSubmissionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OnboardingSubmissionServiceTest {

    @Mock private OnboardingSubmissionRepository repository;
    @Mock private AuditService auditService;
    @Mock private InvestorRepository investorRepository;
    @Mock private InvestorBankAccountRepository bankAccountRepository;
    private OnboardingSubmissionService service;

    private final UUID investorId = UUID.randomUUID();
    private final UUID distributorId = UUID.randomUUID();
    private final UUID investorAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OnboardingSubmissionService(
                repository, auditService, investorRepository, bankAccountRepository);
        lenient().when(repository.save(any(OnboardingSubmission.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private OnboardingSubmission live(OnboardingSubmissionStatus status, int rev, String hash) {
        OnboardingSubmission s = new OnboardingSubmission();
        s.setInvestorId(investorId);
        s.setRevisionNo(rev);
        s.setStatus(status);
        s.setContentSha256(hash);
        return s;
    }

    @Test
    void submitFirstRevisionFreezesHashAwaitingInvestor() {
        when(repository.findFirstByInvestorIdAndStatusNotOrderByRevisionNoDesc(eq(investorId), any()))
                .thenReturn(Optional.empty());
        when(repository.findFirstByInvestorIdOrderByRevisionNoDesc(investorId)).thenReturn(Optional.empty());

        OnboardingSubmission saved = service.submitForInvestorReview(investorId, "{\"a\":1}", distributorId);

        assertThat(saved.getRevisionNo()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(OnboardingSubmissionStatus.DRAFT_AWAITING_INVESTOR);
        // The stored payload is WIDENED (bank/FATCA appended); the frozen hash is of the widened
        // payload. Nominees are investor-owned and intentionally NOT widened in.
        assertThat(saved.getContentSha256()).isEqualTo(ConsentRecordService.sha256(saved.getPayloadJson()));
        assertThat(saved.getPayloadJson()).contains("\"a\":1").contains("bankAccounts").doesNotContain("nominees");
        verify(auditService).log(eq("INVESTOR"), eq(investorId), eq("ONBOARDING_SUBMITTED_FOR_REVIEW"), eq(distributorId), any());
    }

    @Test
    void resubmitSupersedesPriorAndIncrementsRevision() {
        OnboardingSubmission prior = live(OnboardingSubmissionStatus.ATTESTED, 1, "oldhash");
        when(repository.findFirstByInvestorIdAndStatusNotOrderByRevisionNoDesc(eq(investorId), any()))
                .thenReturn(Optional.of(prior));
        when(repository.findFirstByInvestorIdOrderByRevisionNoDesc(investorId)).thenReturn(Optional.of(prior));

        OnboardingSubmission saved = service.submitForInvestorReview(investorId, "{\"a\":2}", distributorId);

        assertThat(prior.getStatus()).isEqualTo(OnboardingSubmissionStatus.SUPERSEDED);
        assertThat(saved.getRevisionNo()).isEqualTo(2);
        assertThat(saved.getStatus()).isEqualTo(OnboardingSubmissionStatus.DRAFT_AWAITING_INVESTOR);
    }

    @Test
    void attestWithMatchingHashMarksAttested() {
        String hash = ConsentRecordService.sha256("{\"a\":1}");
        OnboardingSubmission draft = live(OnboardingSubmissionStatus.DRAFT_AWAITING_INVESTOR, 1, hash);
        when(repository.findFirstByInvestorIdAndStatusNotOrderByRevisionNoDesc(eq(investorId), any()))
                .thenReturn(Optional.of(draft));

        service.attest(investorId, investorAccountId, hash, "203.0.113.7", "Mozilla/5.0");

        assertThat(draft.getStatus()).isEqualTo(OnboardingSubmissionStatus.ATTESTED);
        assertThat(draft.getAttestedBy()).isEqualTo(investorAccountId);
        assertThat(draft.getAttestedAt()).isNotNull();
        verify(auditService).log(eq("INVESTOR"), eq(investorId), eq("ONBOARDING_ATTESTED"), eq(investorAccountId), any());
    }

    @Test
    void attestWithStaleHashIsRejected() {
        OnboardingSubmission draft = live(OnboardingSubmissionStatus.DRAFT_AWAITING_INVESTOR, 1, "currenthash");
        when(repository.findFirstByInvestorIdAndStatusNotOrderByRevisionNoDesc(eq(investorId), any()))
                .thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.attest(investorId, investorAccountId, "STALEHASH", null, null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(draft.getStatus()).isEqualTo(OnboardingSubmissionStatus.DRAFT_AWAITING_INVESTOR);
    }

    @Test
    void assertFinalizablePassesOnlyWhenAttestedAndUnchanged() {
        // Submit freezes a WIDENED snapshot (bank/FATCA/nominee from live data) + its hash;
        // assertFinalizable recomputes the widened hash from live data and compares.
        when(repository.findFirstByInvestorIdAndStatusNotOrderByRevisionNoDesc(eq(investorId), any()))
                .thenReturn(Optional.empty());
        when(repository.findFirstByInvestorIdOrderByRevisionNoDesc(investorId)).thenReturn(Optional.empty());
        OnboardingSubmission saved = service.submitForInvestorReview(investorId, "{\"a\":1}", distributorId);
        saved.setStatus(OnboardingSubmissionStatus.ATTESTED);
        when(repository.findFirstByInvestorIdAndStatusNotOrderByRevisionNoDesc(eq(investorId), any()))
                .thenReturn(Optional.of(saved));

        // Unchanged live data → re-widened hash matches the attested hash → finalize passes
        // (the caller-supplied hash arg is intentionally ignored by the live-recompute gate).
        assertThatCode(() -> service.assertFinalizable(investorId, "ignored")).doesNotThrowAnyException();

        // A post-attestation change to live data (a bank account added) → hash differs → blocked.
        InvestorBankAccount bank = new InvestorBankAccount();
        bank.setInvestorId(investorId);
        bank.setAccountHolderName("Anita");
        bank.setAccountNumber("000123456");
        bank.setIfscCode("HDFC0000001");
        bank.setBankName("HDFC Bank");
        when(bankAccountRepository.findByInvestorId(investorId)).thenReturn(List.of(bank));
        assertThatThrownBy(() -> service.assertFinalizable(investorId, "ignored"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assertFinalizableBlocksWhenNotAttested() {
        OnboardingSubmission draft = live(OnboardingSubmissionStatus.DRAFT_AWAITING_INVESTOR, 1, "H");
        when(repository.findFirstByInvestorIdAndStatusNotOrderByRevisionNoDesc(eq(investorId), any()))
                .thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.assertFinalizable(investorId, "H"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assertFinalizableBlocksWhenNothingSubmitted() {
        when(repository.findFirstByInvestorIdAndStatusNotOrderByRevisionNoDesc(eq(investorId), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertFinalizable(investorId, "H"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidateAttestationOnEditSupersedesAttestedRevision() {
        OnboardingSubmission attested = live(OnboardingSubmissionStatus.ATTESTED, 1, "H");
        when(repository.findFirstByInvestorIdAndStatusNotOrderByRevisionNoDesc(eq(investorId), any()))
                .thenReturn(Optional.of(attested));

        service.invalidateAttestationOnEdit(investorId, distributorId);

        assertThat(attested.getStatus()).isEqualTo(OnboardingSubmissionStatus.SUPERSEDED);
        verify(auditService).log(eq("INVESTOR"), eq(investorId), eq("ONBOARDING_ATTESTATION_INVALIDATED"), eq(distributorId), any());
    }
}
