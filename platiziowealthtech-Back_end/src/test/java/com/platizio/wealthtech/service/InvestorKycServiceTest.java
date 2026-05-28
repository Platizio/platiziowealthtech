package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorStatus;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.dto.IdentityDocumentCreateRequest;
import com.platizio.wealthtech.dto.InvestorExternalKycResponse;
import com.platizio.wealthtech.dto.InvestorKycCheckRequest;
import com.platizio.wealthtech.dto.InvestorKycRequestCreateRequest;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

class InvestorKycServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void createKycCheckStoresExternalIdAndMarksCompleted() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFIED);
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.createKycCheck(eq("AAAPA3751A"), eq(investor.getDateOfBirth())))
                .thenReturn(json("{\"id\":\"kyc-check-1\",\"status\":true}"));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);

        InvestorExternalKycResponse response = service.createKycCheck(investorId, new InvestorKycCheckRequest(null), actorId);

        assertThat(response.investor().getExternalKycCheckId()).isEqualTo("kyc-check-1");
        assertThat(response.investor().getExternalKycStatus()).isEqualTo("true");
        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.COMPLETED);
        assertThat(response.investor().getInvestorStatus()).isEqualTo(InvestorStatus.READY_FOR_TRANSACTIONS);
        assertThat(auditService.actionType).isEqualTo("KYC_CHECK_CREATED");
    }

    @Test
    void createKycRequestMergesInvestorDefaultsAndAdditionalFields() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.createKycRequest(any())).thenReturn(json("""
                {"id":"kycr_1","status":"pending","object":"kyc_request"}
                """));
        InvestorKycRequestCreateRequest request = new InvestorKycRequestCreateRequest(
                null,
                null,
                null,
                null,
                null,
                Map.of("father_name", "Rajesh Gupta", "gender", "female")
        );

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.createKycRequest(investorId, request, actorId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(cybrillaClient).createKycRequest(payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload)
                .containsEntry("name", "Jane Investor")
                .containsEntry("pan", "AAAPA3751A")
                .containsEntry("email", "jane@example.com")
                .containsEntry("mobile", "9876543210")
                .containsEntry("date_of_birth", "1980-10-19")
                .containsEntry("father_name", "Rajesh Gupta");
        assertThat(response.investor().getExternalKycRequestId()).isEqualTo("kycr_1");
        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.IN_PROGRESS);
    }

    @Test
    void createKycCheckRejectsAnotherDistributorInvestor() {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor(investorId, UUID.randomUUID())));
        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);

        assertThatThrownBy(() -> service.createKycCheck(investorId, null, actorId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Cannot manage KYC for another distributor's investor");
        verify(cybrillaClient, never()).createKycCheck(any(), any());
    }

    @Test
    void createIdentityDocumentUsesSavedKycRequestId() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setExternalKycRequestId("kycr_saved");
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.createIdentityDocument(any())).thenReturn(json("""
                {"id":"iddoc_1","object":"identity_document","fetch":{"status":"pending"}}
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        service.createIdentityDocument(
                investorId,
                new IdentityDocumentCreateRequest(null, null, "https://app.example/kyc/callback", null),
                actorId
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(cybrillaClient).createIdentityDocument(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("kyc_request", "kycr_saved")
                .containsEntry("type", "aadhaar")
                .containsEntry("postback_url", "https://app.example/kyc/callback");
    }

    private InvestorKycService service(
            InvestorRepository investorRepository,
            RecordingDistributorService distributorService,
            RecordingAuditService auditService,
            CybrillaClient cybrillaClient
    ) {
        return new InvestorKycService(investorRepository, distributorService, auditService, cybrillaClient);
    }

    private JsonNode json(String value) throws Exception {
        return OBJECT_MAPPER.readTree(value);
    }

    private Investor investor(UUID investorId, UUID distributorId) {
        Investor investor = new Investor();
        ReflectionTestUtils.setField(investor, "id", investorId);
        investor.setDistributorId(distributorId);
        investor.setFullName("Jane Investor");
        investor.setMobileNumber("9876543210");
        investor.setEmail("jane@example.com");
        investor.setPan("AAAPA3751A");
        investor.setDateOfBirth(LocalDate.of(1980, 10, 19));
        investor.setHouseholdId(investorId);
        return investor;
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

    private static class RecordingAuditService extends AuditService {
        private String actionType;

        RecordingAuditService() {
            super(null);
        }

        @Override
        public void log(String entityType, UUID entityId, String actionType, UUID actorId, String detailsJson) {
            this.actionType = actionType;
        }
    }
}
