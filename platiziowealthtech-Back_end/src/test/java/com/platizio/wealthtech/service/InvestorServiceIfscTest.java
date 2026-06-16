package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.dto.IfscLookupResponse;
import com.platizio.wealthtech.dto.InvestorBankRequest;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.IfscLookupResult;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class InvestorServiceIfscTest {

    @Test
    void lookupIfscMapsFinprimResponse() {
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        when(cybrillaClient.fetchIfscDetails("HDFC0001330")).thenReturn(new IfscLookupResult(
                "HDFC0001330",
                "HDFC Bank",
                "MG Road",
                "MG Road Branch",
                "Bengaluru",
                "Bengaluru Urban",
                "Karnataka",
                "560240002"
        ));
        InvestorService service = service(cybrillaClient);

        IfscLookupResponse response = service.lookupIfsc("hdfc0001330");

        assertThat(response.ifscCode()).isEqualTo("HDFC0001330");
        assertThat(response.bankName()).isEqualTo("HDFC Bank");
        assertThat(response.branchName()).isEqualTo("MG Road");
        verify(cybrillaClient).fetchIfscDetails("HDFC0001330");
    }

    @Test
    void addBankAccountEnrichesMissingBankNameFromIfscLookup() {
        UUID investorId = UUID.randomUUID();
        UUID distributorId = UUID.randomUUID();
        Investor investor = new Investor();
        ReflectionTestUtils.setField(investor, "id", investorId);
        investor.setDistributorId(distributorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setCybrillaInvestorId("invp_1");
        investor.setExternalMfInvestmentAccountId("mfia_1");

        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorBankAccountRepository bankAccountRepository = mock(InvestorBankAccountRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);

        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(bankAccountRepository.save(any(InvestorBankAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.fetchIfscDetails("HDFC0001330")).thenReturn(new IfscLookupResult(
                "HDFC0001330",
                "HDFC Bank",
                "MG Road",
                null,
                null,
                null,
                null,
                null
        ));
        doAnswer(invocation -> {
            InvestorBankAccount bankAccount = invocation.getArgument(1);
            bankAccount.setCybrillaBankId("bac_1");
            bankAccount.setCybrillaBankVerificationId("pv_1");
            return null;
        }).when(cybrillaClient).captureBankAccount(any(Investor.class), any(InvestorBankAccount.class));
        when(cybrillaClient.fetchBankAccountVerificationWithPayloadSnapshot(any(), any())).thenReturn(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("object", "pre_verification")
                        .put("status", "accepted")
        );

        DistributorService distributorService = mock(DistributorService.class);
        Distributor distributor = new Distributor();
        distributor.setRole(DistributorRole.SUB_DISTRIBUTOR);
        when(distributorService.getDistributor(distributorId)).thenReturn(distributor);
        when(investorRepository.findByDistributorId(distributorId)).thenReturn(List.of(investor));

        InvestorService service = service(cybrillaClient, investorRepository, bankAccountRepository, distributorService);

        InvestorBankAccount saved = service.addBankAccount(
                investorId,
                new InvestorBankRequest("Anita Verma", "98123459193", "HDFC0001330", null, null, "savings"),
                distributorId
        );

        assertThat(saved.getIfscCode()).isEqualTo("HDFC0001330");
        assertThat(saved.getBankName()).isEqualTo("HDFC Bank");
        assertThat(saved.getBranchName()).isEqualTo("MG Road");
        assertThat(saved.getAccountType()).isEqualTo("savings");
        assertThat(saved.getVerificationStatus()).isEqualTo(BankVerificationStatus.VERIFICATION_PENDING);
    }

    private InvestorService service(CybrillaClient cybrillaClient) {
        return service(
                cybrillaClient,
                mock(InvestorRepository.class),
                mock(InvestorBankAccountRepository.class),
                mock(DistributorService.class)
        );
    }

    private InvestorService service(
            CybrillaClient cybrillaClient,
            InvestorRepository investorRepository,
            InvestorBankAccountRepository bankAccountRepository,
            DistributorService distributorService
    ) {
        return new InvestorService(
                investorRepository,
                bankAccountRepository,
                distributorService,
                new NoopAuditService(),
                cybrillaClient
        );
    }

    private static class NoopAuditService extends AuditService {
        NoopAuditService() {
            super(null);
        }

        @Override
        public void log(String entityType, UUID entityId, String actionType, UUID actorId, String detailsJson) {
            // no-op
        }
    }
}
