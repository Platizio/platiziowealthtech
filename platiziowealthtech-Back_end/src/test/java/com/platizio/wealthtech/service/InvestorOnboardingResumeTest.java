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
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.util.HashMap;
import java.util.List;
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

    private InvestorService service(
            InvestorRepository investorRepository,
            InvestorBankAccountRepository bankAccountRepository,
            UUID actorId
    ) {
        RecordingDistributorService distributorService = new RecordingDistributorService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        return new InvestorService(
                investorRepository,
                bankAccountRepository,
                distributorService,
                new NoopAuditService(),
                null
        );
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
