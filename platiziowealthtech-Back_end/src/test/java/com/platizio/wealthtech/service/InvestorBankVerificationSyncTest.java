package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class InvestorBankVerificationSyncTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void syncOutstandingBankVerificationStatusesFetchesPendingVerificationAndUpdatesInvestor() throws Exception {
        UUID investorId = UUID.randomUUID();
        UUID distributorId = UUID.randomUUID();
        UUID bankAccountId = UUID.randomUUID();

        Investor investor = new Investor();
        ReflectionTestUtils.setField(investor, "id", investorId);
        investor.setDistributorId(distributorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
        investor.setCybrillaInvestorId("invp_1");
        investor.setExternalMfInvestmentAccountId("mfia_1");

        InvestorBankAccount bankAccount = new InvestorBankAccount();
        ReflectionTestUtils.setField(bankAccount, "id", bankAccountId);
        bankAccount.setInvestorId(investorId);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
        bankAccount.setCybrillaBankVerificationId("bav_1");

        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);

        when(bankAccountRepository.findBankVerificationSyncCandidates(
                eq(List.of(BankVerificationStatus.VERIFICATION_PENDING, BankVerificationStatus.CAPTURED)),
                any(Pageable.class)
        )).thenReturn(List.of(bankAccount));
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(bankAccountRepository.save(any(InvestorBankAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.fetchBankAccountVerification("bav_1")).thenReturn(json("""
                {"id":"bav_1","status":"completed","confidence":"very_high"}
                """));
        when(cybrillaClient.fetchBankAccountVerificationWithPayloadSnapshot(eq("bav_1"), any(Map.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = invocation.getArgument(1);
            snapshot.put("operation", "fetch_bank_account_verification");
            snapshot.put("bank_account_verification_id", "bav_1");
            return json("""
                    {"id":"bav_1","status":"completed","confidence":"very_high"}
                    """);
        });

        InvestorService service = new InvestorService(
                investorRepository,
                bankAccountRepository,
                null,
                new NoopAuditService(),
                cybrillaClient
        );

        int synced = service.syncOutstandingBankVerificationStatuses(10);

        assertThat(synced).isEqualTo(1);
        assertThat(bankAccount.getVerificationStatus()).isEqualTo(BankVerificationStatus.VERIFIED);
        assertThat(investor.getBankVerificationStatus()).isEqualTo(BankVerificationStatus.VERIFIED);
        verify(cybrillaClient).fetchBankAccountVerificationWithPayloadSnapshot(eq("bav_1"), any(Map.class));
        verify(bankAccountRepository).save(bankAccount);
        verify(investorRepository).save(investor);
        assertThat(bankAccount.getExternalVerificationRequestJson()).contains("bav_1");
        assertThat(bankAccount.getExternalVerificationResponseJson()).contains("\"status\":\"completed\"");
    }

    @Test
    void syncOutstandingBankVerificationStatusesPreservesPendingStateWhenVerificationCannotStart() {
        UUID investorId = UUID.randomUUID();
        UUID distributorId = UUID.randomUUID();
        UUID bankAccountId = UUID.randomUUID();

        Investor investor = new Investor();
        ReflectionTestUtils.setField(investor, "id", investorId);
        investor.setDistributorId(distributorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
        investor.setCybrillaInvestorId("invp_1");
        investor.setExternalMfInvestmentAccountId("mfia_1");

        InvestorBankAccount bankAccount = new InvestorBankAccount();
        ReflectionTestUtils.setField(bankAccount, "id", bankAccountId);
        bankAccount.setInvestorId(investorId);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);

        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);

        when(bankAccountRepository.findBankVerificationSyncCandidates(
                eq(List.of(BankVerificationStatus.VERIFICATION_PENDING, BankVerificationStatus.CAPTURED)),
                any(Pageable.class)
        )).thenReturn(List.of(bankAccount));
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(bankAccountRepository.save(any(InvestorBankAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            InvestorBankAccount captured = invocation.getArgument(1);
            captured.setCybrillaBankId("bac_1");
            captured.setExternalSyncPending(true);
            captured.setExternalSyncMessage("verification disabled");
            return null;
        }).when(cybrillaClient).captureBankAccount(eq(investor), eq(bankAccount));

        InvestorService service = new InvestorService(
                investorRepository,
                bankAccountRepository,
                null,
                new NoopAuditService(),
                cybrillaClient
        );

        int synced = service.syncOutstandingBankVerificationStatuses(10);

        assertThat(synced).isEqualTo(1);
        assertThat(bankAccount.getCybrillaBankId()).isEqualTo("bac_1");
        assertThat(bankAccount.getCybrillaBankVerificationId()).isNull();
        assertThat(bankAccount.getVerificationStatus()).isEqualTo(BankVerificationStatus.VERIFICATION_PENDING);
        assertThat(bankAccount.getExternalSyncPending()).isTrue();
        assertThat(bankAccount.getExternalSyncMessage()).isEqualTo("verification disabled");
        verify(cybrillaClient, never()).fetchBankAccountVerification(any());
        verify(bankAccountRepository).save(bankAccount);
        verify(investorRepository).save(investor);
    }

    @Test
    void startBankVerificationAfterKycCompletionCapturesAndVerifiesPendingBankAccount() throws Exception {
        UUID distributorId = UUID.randomUUID();
        Investor investor = investor(UUID.randomUUID(), distributorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setCybrillaInvestorId(null);
        investor.setExternalMfInvestmentAccountId(null);
        InvestorBankAccount bankAccount = bankAccount(UUID.randomUUID(), investor.getId(), null);

        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);

        when(bankAccountRepository.findByInvestorId(investor.getId())).thenReturn(List.of(bankAccount));
        when(bankAccountRepository.save(any(InvestorBankAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.createInvestorProfile(investor)).thenReturn("invp_after_kyc");
        when(cybrillaClient.createMfInvestmentAccount(investor)).thenReturn("mfia_after_kyc");
        doAnswer(invocation -> {
            InvestorBankAccount captured = invocation.getArgument(1);
            captured.setCybrillaBankId("bac_1");
            captured.setCybrillaBankVerificationId("pv_bank_1");
            captured.setCybrillaBankVerificationStatus("accepted");
            return null;
        }).when(cybrillaClient).captureBankAccount(eq(investor), eq(bankAccount));
        when(cybrillaClient.fetchBankAccountVerification("pv_bank_1")).thenReturn(json("""
                {
                  "object":"pre_verification",
                  "id":"pv_bank_1",
                  "status":"completed",
                  "bank_accounts":[{"status":"verified","code":null,"reason":null}]
                }
                """));
        when(cybrillaClient.fetchBankAccountVerificationWithPayloadSnapshot(eq("pv_bank_1"), any(Map.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = invocation.getArgument(1);
            snapshot.put("operation", "fetch_bank_account_verification");
            snapshot.put("bank_account_verification_id", "pv_bank_1");
            return json("""
                    {
                      "object":"pre_verification",
                      "id":"pv_bank_1",
                      "status":"completed",
                      "bank_accounts":[{"status":"verified","code":null,"reason":null}]
                    }
                    """);
        });

        InvestorService service = new InvestorService(
                investorRepository,
                bankAccountRepository,
                null,
                new NoopAuditService(),
                cybrillaClient
        );

        Investor result = service.startBankVerificationAfterKycCompletion(investor, distributorId, "kyc_completed");

        assertThat(result.getBankVerificationStatus()).isEqualTo(BankVerificationStatus.VERIFIED);
        assertThat(result.getInvestorStatus()).isEqualTo(com.platizio.wealthtech.domain.InvestorStatus.READY_FOR_TRANSACTIONS);
        assertThat(result.getCybrillaInvestorId()).isEqualTo("invp_after_kyc");
        assertThat(result.getExternalMfInvestmentAccountId()).isEqualTo("mfia_after_kyc");
        assertThat(bankAccount.getVerificationStatus()).isEqualTo(BankVerificationStatus.VERIFIED);
        verify(cybrillaClient).createInvestorProfile(investor);
        verify(cybrillaClient).createMfInvestmentAccount(investor);
        verify(cybrillaClient).captureBankAccount(investor, bankAccount);
        verify(cybrillaClient).fetchBankAccountVerificationWithPayloadSnapshot(eq("pv_bank_1"), any(Map.class));
    }

    @Test
    void startBankVerificationAfterKycCompletionCreatesExternalInvestorEvenWithoutBankAccounts() {
        UUID distributorId = UUID.randomUUID();
        Investor investor = investor(UUID.randomUUID(), distributorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setCybrillaInvestorId(null);
        investor.setExternalMfInvestmentAccountId(null);

        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);

        when(bankAccountRepository.findByInvestorId(investor.getId())).thenReturn(List.of());
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.createInvestorProfile(investor)).thenReturn("invp_after_kyc");
        when(cybrillaClient.createMfInvestmentAccount(investor)).thenReturn("mfia_after_kyc");

        InvestorService service = new InvestorService(
                investorRepository,
                bankAccountRepository,
                null,
                new NoopAuditService(),
                cybrillaClient
        );

        Investor result = service.startBankVerificationAfterKycCompletion(investor, distributorId, "kyc_completed");

        assertThat(result.getCybrillaInvestorId()).isEqualTo("invp_after_kyc");
        assertThat(result.getExternalMfInvestmentAccountId()).isEqualTo("mfia_after_kyc");
        verify(cybrillaClient).createInvestorProfile(investor);
        verify(cybrillaClient).createMfInvestmentAccount(investor);
        verify(cybrillaClient, never()).captureBankAccount(any(), any());
        verify(bankAccountRepository).findByInvestorId(investor.getId());
    }

    @Test
    void startBankVerificationAfterKycCompletionRetriesVerificationWithoutDuplicatingBankAccount() throws Exception {
        UUID distributorId = UUID.randomUUID();
        Investor investor = investor(UUID.randomUUID(), distributorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        InvestorBankAccount bankAccount = bankAccount(UUID.randomUUID(), investor.getId(), null);
        bankAccount.setCybrillaBankId("bac_existing");
        bankAccount.setExternalSyncPending(true);

        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);

        when(bankAccountRepository.findByInvestorId(investor.getId())).thenReturn(List.of(bankAccount));
        when(bankAccountRepository.save(any(InvestorBankAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            InvestorBankAccount verified = invocation.getArgument(1);
            verified.setCybrillaBankVerificationId("pv_retry");
            verified.setCybrillaBankVerificationStatus("accepted");
            return null;
        }).when(cybrillaClient).startBankAccountVerification(eq(investor), eq(bankAccount));
        when(cybrillaClient.fetchBankAccountVerification("pv_retry")).thenReturn(json("""
                {"object":"pre_verification","id":"pv_retry","status":"accepted","bank_accounts":[]}
                """));
        when(cybrillaClient.fetchBankAccountVerificationWithPayloadSnapshot(eq("pv_retry"), any(Map.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = invocation.getArgument(1);
            snapshot.put("operation", "fetch_bank_account_verification");
            snapshot.put("bank_account_verification_id", "pv_retry");
            return json("""
                    {"object":"pre_verification","id":"pv_retry","status":"accepted","bank_accounts":[]}
                    """);
        });

        InvestorService service = new InvestorService(
                investorRepository,
                bankAccountRepository,
                null,
                new NoopAuditService(),
                cybrillaClient
        );

        service.startBankVerificationAfterKycCompletion(investor, distributorId, "kyc_completed");

        verify(cybrillaClient, never()).captureBankAccount(any(), any());
        verify(cybrillaClient).startBankAccountVerification(investor, bankAccount);
        assertThat(bankAccount.getCybrillaBankId()).isEqualTo("bac_existing");
        assertThat(bankAccount.getCybrillaBankVerificationId()).isEqualTo("pv_retry");
    }

    @Test
    void syncOutstandingBankVerificationStatusesStopsBatchAndBacksOffAfterExternalFailure() {
        UUID distributorId = UUID.randomUUID();
        Investor firstInvestor = investor(UUID.randomUUID(), distributorId);
        Investor secondInvestor = investor(UUID.randomUUID(), distributorId);
        InvestorBankAccount firstBank = bankAccount(UUID.randomUUID(), firstInvestor.getId(), "bav_first");
        InvestorBankAccount secondBank = bankAccount(UUID.randomUUID(), secondInvestor.getId(), "bav_second");

        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);

        when(bankAccountRepository.findBankVerificationSyncCandidates(
                eq(List.of(BankVerificationStatus.VERIFICATION_PENDING, BankVerificationStatus.CAPTURED)),
                any(Pageable.class)
        )).thenReturn(List.of(firstBank, secondBank));
        when(investorRepository.findById(firstInvestor.getId())).thenReturn(Optional.of(firstInvestor));
        when(investorRepository.findById(secondInvestor.getId())).thenReturn(Optional.of(secondInvestor));
        when(bankAccountRepository.save(any(InvestorBankAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.fetchBankAccountVerificationWithPayloadSnapshot(eq("bav_first"), any(Map.class)))
                .thenThrow(new CybrillaApiException("Too Many Requests"));

        InvestorService service = new InvestorService(
                investorRepository,
                bankAccountRepository,
                null,
                new NoopAuditService(),
                cybrillaClient,
                60_000
        );

        assertThat(service.syncOutstandingBankVerificationStatuses(10)).isEqualTo(1);
        assertThat(service.syncOutstandingBankVerificationStatuses(10)).isZero();

        verify(cybrillaClient, times(1)).fetchBankAccountVerificationWithPayloadSnapshot(eq("bav_first"), any(Map.class));
        verify(cybrillaClient, never()).fetchBankAccountVerificationWithPayloadSnapshot(eq("bav_second"), any(Map.class));
        verify(bankAccountRepository, times(1)).findBankVerificationSyncCandidates(any(), any());
    }

    private JsonNode json(String value) throws Exception {
        return OBJECT_MAPPER.readTree(value);
    }

    private Investor investor(UUID investorId, UUID distributorId) {
        Investor investor = new Investor();
        ReflectionTestUtils.setField(investor, "id", investorId);
        investor.setDistributorId(distributorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
        investor.setCybrillaInvestorId("invp_existing");
        investor.setExternalMfInvestmentAccountId("mfia_existing");
        return investor;
    }

    private InvestorBankAccount bankAccount(UUID bankAccountId, UUID investorId, String verificationId) {
        InvestorBankAccount bankAccount = new InvestorBankAccount();
        ReflectionTestUtils.setField(bankAccount, "id", bankAccountId);
        bankAccount.setInvestorId(investorId);
        bankAccount.setVerificationStatus(BankVerificationStatus.VERIFICATION_PENDING);
        bankAccount.setCybrillaBankVerificationId(verificationId);
        return bankAccount;
    }

    private static class NoopAuditService extends AuditService {
        NoopAuditService() {
            super(null);
        }

        @Override
        public void log(String entityType, UUID entityId, String actionType, UUID actorId, String detailsJson) {
            // no-op for unit tests
        }
    }
}
