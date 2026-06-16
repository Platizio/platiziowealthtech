package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.dto.PincodeLookupResponse;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.PincodeLookupResult;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvestorServicePincodeTest {

    @Test
    void lookupPincodeMapsFinprimResponse() {
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        when(cybrillaClient.fetchPincodeDetails("560102")).thenReturn(new PincodeLookupResult(
                "560102",
                "Bangalore South",
                "Bangalore",
                "Karnataka",
                "IN",
                List.of("Bangalore South")
        ));
        InvestorService service = new InvestorService(
                mock(InvestorRepository.class),
                mock(InvestorBankAccountRepository.class),
                mock(DistributorService.class),
                new NoopAuditService(),
                cybrillaClient
        );

        PincodeLookupResponse response = service.lookupPincode("560102");

        assertThat(response.code()).isEqualTo("560102");
        assertThat(response.city()).isEqualTo("Bangalore South");
        assertThat(response.stateName()).isEqualTo("Karnataka");
        assertThat(response.cities()).containsExactly("Bangalore South");
        verify(cybrillaClient).fetchPincodeDetails("560102");
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
