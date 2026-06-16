package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.dto.InvestorOnboardingResumeResponse;
import com.platizio.wealthtech.domain.InvestorDocument;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorDocumentRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class InvestorOnboardingResumeTest {

    @Test
    void onboardingResumePointsCompletedKycInvestorToBankCapture() {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setBankVerificationStatus(BankVerificationStatus.NOT_CAPTURED);
        investor.setExternalKycCheckId("pv_completed");

        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(bankAccountRepository.findByInvestorId(investorId)).thenReturn(List.of());

        InvestorOnboardingResumeResponse response = service(investorRepository, bankAccountRepository, actorId)
                .getOnboardingResume(investorId, actorId);

        assertThat(response.nextStep()).isEqualTo("ADD_BANK_ACCOUNT");
        assertThat(response.canReKyc()).isTrue();
        assertThat(response.canAddBankAccount()).isTrue();
        assertThat(response.readyForTransactions()).isFalse();
    }

    @Test
    void onboardingResumePointsPendingKycInvestorToRefresh() {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setKycStatus(KycStatus.IN_PROGRESS);
        investor.setExternalKycCheckId("pv_pending");

        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(bankAccountRepository.findByInvestorId(investorId)).thenReturn(List.of());

        InvestorOnboardingResumeResponse response = service(investorRepository, bankAccountRepository, actorId)
                .getOnboardingResume(investorId, actorId);

        assertThat(response.nextStep()).isEqualTo("REFRESH_KYC");
        assertThat(response.canRefreshKyc()).isTrue();
        assertThat(response.canApplyKyc()).isFalse();
    }

    @Test
    void onboardingResumePointsBankPendingInvestorToBankRefresh() {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        UUID bankAccountId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);

        InvestorBankAccount bankAccount = new InvestorBankAccount();
        ReflectionTestUtils.setField(bankAccount, "id", bankAccountId);
        bankAccount.setInvestorId(investorId);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);

        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(bankAccountRepository.findByInvestorId(investorId)).thenReturn(List.of(bankAccount));

        InvestorOnboardingResumeResponse response = service(investorRepository, bankAccountRepository, actorId)
                .getOnboardingResume(investorId, actorId);

        assertThat(response.nextStep()).isEqualTo("REFRESH_BANK_VERIFICATION");
        assertThat(response.canRefreshBankVerification()).isTrue();
        assertThat(response.bankAccounts()).containsExactly(bankAccount);
    }

    @Test
    void onboardingResumePointsVerifiedBankWithoutDocumentsToUploadStep() {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFIED);

        InvestorBankAccount bankAccount = new InvestorBankAccount();
        ReflectionTestUtils.setField(bankAccount, "id", UUID.randomUUID());
        bankAccount.setInvestorId(investorId);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);

        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        InvestorDocumentRepository documentRepository = mock(InvestorDocumentRepository.class);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(bankAccountRepository.findByInvestorId(investorId)).thenReturn(List.of(bankAccount));
        when(documentRepository.findAllByInvestorIdOrderByDocumentTypeAsc(investorId)).thenReturn(List.of());

        InvestorOnboardingResumeResponse response = service(
                investorRepository,
                bankAccountRepository,
                documentRepository,
                actorId
        ).getOnboardingResume(investorId, actorId);

        assertThat(response.nextStep()).isEqualTo("UPLOAD_DOCUMENTS");
        assertThat(response.documentsComplete()).isFalse();
        assertThat(response.missingDocumentTypes()).containsExactly("PAN", "ADDRESS", "SIGNATURE");
        assertThat(response.readyForTransactions()).isFalse();
    }

    @Test
    void onboardingResumeMarksReadyWhenKycBankAndDocumentsComplete() {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFIED);

        InvestorBankAccount bankAccount = new InvestorBankAccount();
        ReflectionTestUtils.setField(bankAccount, "id", UUID.randomUUID());
        bankAccount.setInvestorId(investorId);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFIED);

        InvestorDocument pan = document(investorId, "PAN");
        InvestorDocument address = document(investorId, "ADDRESS");
        InvestorDocument signature = document(investorId, "SIGNATURE");

        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        InvestorDocumentRepository documentRepository = mock(InvestorDocumentRepository.class);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(bankAccountRepository.findByInvestorId(investorId)).thenReturn(List.of(bankAccount));
        when(documentRepository.findAllByInvestorIdOrderByDocumentTypeAsc(investorId))
                .thenReturn(List.of(address, pan, signature));

        InvestorOnboardingResumeResponse response = service(
                investorRepository,
                bankAccountRepository,
                documentRepository,
                actorId
        ).getOnboardingResume(investorId, actorId);

        assertThat(response.nextStep()).isEqualTo("READY_FOR_TRANSACTIONS");
        assertThat(response.documentsComplete()).isTrue();
        assertThat(response.missingDocumentTypes()).isEmpty();
        assertThat(response.readyForTransactions()).isTrue();
    }

    private InvestorService service(
            InvestorRepository investorRepository,
            InvestorBankAccountRepository bankAccountRepository,
            UUID actorId
    ) {
        return service(investorRepository, bankAccountRepository, null, actorId);
    }

    private InvestorService service(
            InvestorRepository investorRepository,
            InvestorBankAccountRepository bankAccountRepository,
            InvestorDocumentRepository documentRepository,
            UUID actorId
    ) {
        RecordingDistributorService distributorService = new RecordingDistributorService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        return new InvestorService(
                investorRepository,
                bankAccountRepository,
                distributorService,
                new NoopAuditService(),
                null,
                null,
                900_000,
                documentRepository
        );
    }

    private InvestorDocument document(UUID investorId, String documentType) {
        InvestorDocument document = new InvestorDocument();
        document.setInvestorId(investorId);
        document.setDocumentType(documentType);
        document.setFileName(documentType.toLowerCase(Locale.ROOT) + ".pdf");
        document.setContentType("application/pdf");
        document.setSizeBytes(1024L);
        return document;
    }

    private Investor investor(UUID investorId, UUID distributorId) {
        Investor investor = new Investor();
        ReflectionTestUtils.setField(investor, "id", investorId);
        investor.setDistributorId(distributorId);
        investor.setFullName("Jane Investor");
        investor.setMobileNumber("9876543210");
        investor.setEmail("jane@example.com");
        investor.setPan("AAAPA3751A");
        investor.setHouseholdId(investorId);
        return investor;
    }

    private static class NoopAuditService extends AuditService {
        NoopAuditService() {
            super(null);
        }
    }

    private static class RecordingDistributorService extends DistributorService {
        private final Map<UUID, Distributor> distributors = new HashMap<>();

        RecordingDistributorService() {
            super(null, null, null);
        }

        void put(UUID distributorId, DistributorRole role) {
            Distributor distributor = new Distributor();
            distributor.setRole(role);
            distributors.put(distributorId, distributor);
        }

        @Override
        public Distributor getDistributor(UUID distributorId) {
            return Optional.ofNullable(distributors.get(distributorId))
                    .orElseThrow(() -> new AssertionError("Unexpected distributor lookup: " + distributorId));
        }
    }
}
