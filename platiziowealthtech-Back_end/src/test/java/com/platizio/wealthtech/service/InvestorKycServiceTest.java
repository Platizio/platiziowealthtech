package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.platizio.wealthtech.dto.ExternalKycSyncResponse;
import com.platizio.wealthtech.dto.IdentityDocumentCreateRequest;
import com.platizio.wealthtech.dto.InvestorExternalKycResponse;
import com.platizio.wealthtech.dto.InvestorKycCheckRequest;
import com.platizio.wealthtech.dto.InvestorKycRequestCreateRequest;
import com.platizio.wealthtech.dto.InvestorPreVerificationRequest;
import com.platizio.wealthtech.dto.InvestorPreVerificationResponse;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.auth.CybrillaPreVerificationProperties;
import com.platizio.wealthtech.repository.InvestorRepository;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
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
    void createPreVerificationDoesNotRequireLocalInvestor() throws Exception {
        UUID actorId = UUID.randomUUID();
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        when(cybrillaClient.createPreVerification(any())).thenReturn(json("""
                {"object":"pre_verification","id":"pv_payload","status":"accepted"}
                """));
        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);

        InvestorPreVerificationResponse response = service.createPreVerification(
                new InvestorPreVerificationRequest("Rani Gupta", "aaapa3751a", LocalDate.of(1955, 10, 25)),
                actorId
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(cybrillaClient).createPreVerification(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("investor_identifier", "AAAPA3751A")
                .containsEntry("pan", Map.of("value", "AAAPA3751A"))
                .containsEntry("name", Map.of("value", "Rani Gupta"))
                .containsEntry("date_of_birth", Map.of("value", "1955-10-25"));
        verify(investorRepository, never()).findById(any());
        assertThat(response.externalResponse().path("id").asText()).isEqualTo("pv_payload");
    }

    @Test
    void createPreVerificationRejectsUnsupportedSandboxPanBeforeExternalCall() {
        UUID actorId = UUID.randomUUID();
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);

        assertThatThrownBy(() -> service.createPreVerification(
                new InvestorPreVerificationRequest("Rani Gupta", "ABCDE1234F", LocalDate.of(1955, 10, 25)),
                actorId
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cybrilla sandbox only accepts simulator PANs");

        verify(cybrillaClient, never()).createPreVerification(any());
    }

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
        when(cybrillaClient.createKycCheck(any(Investor.class)))
                .thenReturn(json("""
                        {
                          "object": "pre_verification",
                          "id": "pv_1",
                          "status": "completed",
                          "readiness": { "status": "verified", "code": null, "reason": null }
                        }
                        """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);

        InvestorExternalKycResponse response = service.createKycCheck(investorId, new InvestorKycCheckRequest(null, null), actorId);

        assertThat(response.investor().getExternalKycCheckId()).isEqualTo("pv_1");
        assertThat(response.investor().getExternalKycStatus()).isEqualTo("completed");
        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.COMPLETED);
        assertThat(response.investor().getInvestorStatus()).isEqualTo(InvestorStatus.READY_FOR_TRANSACTIONS);
        assertThat(auditService.actionType).isEqualTo("KYC_CHECK_CREATED");
    }

    @Test
    void createKycCheckReusesSavedExternalCheckInsteadOfCreatingDuplicate() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setExternalKycCheckId("pv_saved");
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.fetchKycCheck("pv_saved")).thenReturn(json("""
                {
                  "object": "pre_verification",
                  "id": "pv_saved",
                  "status": "accepted"
                }
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.createKycCheck(investorId, null, actorId);

        verify(cybrillaClient).fetchKycCheck("pv_saved");
        verify(cybrillaClient, never()).createKycCheck(any(Investor.class));
        assertThat(response.externalResponse().path("id").asText()).isEqualTo("pv_saved");
        assertThat(auditService.actionType).isEqualTo("KYC_CHECK_REUSED");
    }

    @Test
    void createKycCheckCreatesNewWhenSavedCheckReferenceIsStale() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setExternalKycCheckId("pv_stale");
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.fetchKycCheck("pv_stale"))
                .thenThrow(new CybrillaApiException("Unable to fetch POA pre verification with Cybrilla POA: 404"));
        when(cybrillaClient.createKycCheck(any(Investor.class))).thenReturn(json("""
                {
                  "object": "pre_verification",
                  "id": "pv_new",
                  "status": "accepted"
                }
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.createKycCheck(investorId, null, actorId);

        verify(cybrillaClient).fetchKycCheck("pv_stale");
        verify(cybrillaClient).createKycCheck(investor);
        assertThat(response.investor().getExternalKycCheckId()).isEqualTo("pv_new");
        assertThat(response.externalResponse().path("id").asText()).isEqualTo("pv_new");
        assertThat(auditService.actionType).isEqualTo("KYC_CHECK_CREATED");
    }

    @Test
    void createKycCheckWithForceFlagStartsNewPoaCheck() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setExternalKycCheckId("pv_old");
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.createKycCheck(any(Investor.class))).thenReturn(json("""
                {"object":"pre_verification","id":"pv_new","status":"accepted"}
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.createKycCheck(
                investorId,
                new InvestorKycCheckRequest(null, true),
                actorId
        );

        verify(cybrillaClient, never()).fetchKycCheck("pv_old");
        verify(cybrillaClient).createKycCheck(investor);
        assertThat(response.investor().getExternalKycCheckId()).isEqualTo("pv_new");
        assertThat(auditService.actionType).isEqualTo("KYC_CHECK_CREATED");
    }

    @Test
    void createKycCheckStartsBankVerificationWhenKycCompletes() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingBankVerificationStarter bankVerificationStarter = new RecordingBankVerificationStarter();
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.createKycCheck(any(Investor.class)))
                .thenReturn(json("""
                        {
                          "object": "pre_verification",
                          "id": "pv_1",
                          "status": "completed",
                          "readiness": { "status": "verified", "code": null, "reason": null }
                        }
                        """));

        InvestorKycService service = service(
                investorRepository,
                distributorService,
                auditService,
                cybrillaClient,
                bankVerificationStarter
        );
        service.createKycCheck(investorId, new InvestorKycCheckRequest(null, null), actorId);

        assertThat(bankVerificationStarter.investor).isSameAs(investor);
        assertThat(bankVerificationStarter.actorId).isEqualTo(actorId);
        assertThat(bankVerificationStarter.trigger).isEqualTo("kyc_check_created");
    }

    @Test
    void createKycCheckCreatesExternalProfileWhenMissingBeforePoaCall() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setCybrillaInvestorId(null);
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.createInvestorProfile(any(Investor.class))).thenReturn("fp_profile_1");
        when(cybrillaClient.createKycCheck(any(Investor.class)))
                .thenReturn(json("""
                        {
                          "object": "pre_verification",
                          "id": "pv_1",
                          "status": "accepted"
                        }
                        """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.createKycCheck(investorId, null, actorId);

        verify(cybrillaClient).createInvestorProfile(investor);
        verify(cybrillaClient).createKycCheck(investor);
        assertThat(response.investor().getCybrillaInvestorId()).isEqualTo("fp_profile_1");
        assertThat(response.investor().getExternalKycCheckId()).isEqualTo("pv_1");
    }

    @Test
    void applyInvestorKycStartsFreshKycRequestWhenPoaSaysKycUnavailable() throws Exception {
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
        when(cybrillaClient.createKycCheck(any(Investor.class))).thenReturn(json("""
                {
                  "object": "pre_verification",
                  "id": "pv_needs_kyc",
                  "status": "completed",
                  "readiness": {
                    "status": "failed",
                    "code": "kyc_unavailable",
                    "reason": "Fresh KYC is required"
                  }
                }
                """));
        when(cybrillaClient.createKycRequest(any())).thenReturn(json("""
                {"object":"kyc_request","id":"kycr_1","status":"pending"}
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.applyInvestorKyc(investorId, null, actorId);

        verify(cybrillaClient).createKycCheck(investor);
        verify(cybrillaClient).createKycRequest(any());
        assertThat(response.investor().getExternalKycCheckId()).isEqualTo("pv_needs_kyc");
        assertThat(response.investor().getExternalKycRequestId()).isEqualTo("kycr_1");
        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.IN_PROGRESS);
        assertThat(response.externalResponse().path("id").asText()).isEqualTo("kycr_1");
        assertThat(auditService.actionType).isEqualTo("KYC_APPLIED");
    }

    @Test
    void applyInvestorKycRefreshesSavedExternalReferencesWithoutCreatingDuplicates() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setExternalKycCheckId("pv_saved");
        investor.setExternalKycRequestId("kycr_saved");
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.fetchKycCheck("pv_saved")).thenReturn(json("""
                {
                  "object": "pre_verification",
                  "id": "pv_saved",
                  "status": "completed",
                  "readiness": {
                    "status": "failed",
                    "code": "kyc_unavailable",
                    "reason": "Fresh KYC is required"
                  }
                }
                """));
        when(cybrillaClient.fetchKycRequest("kycr_saved")).thenReturn(json("""
                {"object":"kyc_request","id":"kycr_saved","status":"successful"}
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.applyInvestorKyc(investorId, null, actorId);

        verify(cybrillaClient).fetchKycCheck("pv_saved");
        verify(cybrillaClient).fetchKycRequest("kycr_saved");
        verify(cybrillaClient, never()).createKycCheck(any(Investor.class));
        verify(cybrillaClient, never()).createKycRequest(any());
        assertThat(response.investor().getExternalKycRequestId()).isEqualTo("kycr_saved");
        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.COMPLETED);
        assertThat(response.externalResponse().path("id").asText()).isEqualTo("kycr_saved");
    }

    @Test
    void reapplyInvestorKycStartsNewPoaCheckInsteadOfRefreshingSavedReferences() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setInvestorStatus(InvestorStatus.READY_FOR_TRANSACTIONS);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setExternalKycCheckId("pv_old");
        investor.setExternalKycRequestId("kycr_old");
        investor.setKycReadinessStatus("verified");
        investor.setPanVerificationStatus("verified");
        investor.setPanAadhaarLinkStatus("LINKED");
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.createKycCheck(any(Investor.class))).thenReturn(json("""
                {
                  "object": "pre_verification",
                  "id": "pv_new",
                  "status": "accepted"
                }
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.reapplyInvestorKyc(investorId, null, actorId);

        verify(cybrillaClient, never()).fetchKycCheck("pv_old");
        verify(cybrillaClient, never()).fetchKycRequest("kycr_old");
        verify(cybrillaClient).createKycCheck(investor);
        assertThat(response.investor().getExternalKycCheckId()).isEqualTo("pv_new");
        assertThat(response.investor().getExternalKycRequestId()).isNull();
        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.IN_PROGRESS);
        assertThat(response.investor().getInvestorStatus()).isEqualTo(InvestorStatus.ONBOARDING);
        assertThat(response.investor().getKycReadinessStatus()).isNull();
        assertThat(response.investor().getPanAadhaarLinkStatus()).isNull();
        assertThat(auditService.actionType).isEqualTo("KYC_REAPPLIED");
    }

    @Test
    void applyInvestorKycWithForceFlagStartsNewPoaCheck() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setExternalKycCheckId("pv_old");
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.createKycCheck(any(Investor.class))).thenReturn(json("""
                {"object":"pre_verification","id":"pv_new","status":"accepted"}
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.applyInvestorKyc(
                investorId,
                new InvestorKycCheckRequest(null, true),
                actorId
        );

        verify(cybrillaClient, never()).fetchKycCheck("pv_old");
        verify(cybrillaClient).createKycCheck(investor);
        assertThat(response.investor().getExternalKycCheckId()).isEqualTo("pv_new");
        assertThat(auditService.actionType).isEqualTo("KYC_REAPPLIED");
    }

    @Test
    void applyInvestorKycRefreshesSavedKycRequestWhenNoPoaReferenceExists() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setExternalKycRequestId("kycr_only");
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.fetchKycRequest("kycr_only")).thenReturn(json("""
                {"object":"kyc_request","id":"kycr_only","status":"successful"}
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.applyInvestorKyc(investorId, null, actorId);

        verify(cybrillaClient).fetchKycRequest("kycr_only");
        verify(cybrillaClient, never()).createKycCheck(any(Investor.class));
        verify(cybrillaClient, never()).createKycRequest(any());
        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.COMPLETED);
        assertThat(response.externalResponse().path("id").asText()).isEqualTo("kycr_only");
    }

    @Test
    void createKycCheckStoresPanAadhaarLinkResult() throws Exception {
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
        when(cybrillaClient.createKycCheck(any(Investor.class)))
                .thenReturn(json("""
                        {
                          "object": "pre_verification",
                          "id": "pv_aadhaar",
                          "status": "completed",
                          "pan": {
                            "status": "failed",
                            "code": "aadhaar_not_linked",
                            "reason": "PAN is not seeded with Aadhaar"
                          }
                        }
                        """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.createKycCheck(investorId, null, actorId);

        assertThat(response.investor().getPanVerificationStatus()).isEqualTo("failed");
        assertThat(response.investor().getPanVerificationCode()).isEqualTo("aadhaar_not_linked");
        assertThat(response.investor().getPanAadhaarLinkStatus()).isEqualTo("NOT_LINKED");
        assertThat(response.investor().getPanAadhaarLinkReason()).isEqualTo("PAN is not seeded with Aadhaar");
        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.FAILED);
    }

    @Test
    void webhookFetchesPreVerificationAndUpdatesInvestorStatus() throws Exception {
        UUID distributorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, distributorId);
        investor.setExternalKycCheckId("pv_webhook");
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        when(investorRepository.findByExternalKycCheckId("pv_webhook")).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.fetchKycCheck("pv_webhook"))
                .thenReturn(json("""
                        {
                          "object": "pre_verification",
                          "id": "pv_webhook",
                          "status": "completed",
                          "readiness": { "status": "verified", "code": null, "reason": null },
                          "pan": { "status": "verified", "code": null, "reason": null }
                        }
                        """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        ExternalKycSyncResponse response = service.handleExternalKycWebhook(json("""
                {
                  "object": "event",
                  "type": "pre_verification.completed",
                  "data": {
                    "object": {
                      "object": "pre_verification",
                      "id": "pv_webhook",
                      "status": "completed"
                    }
                  }
                }
                """));

        assertThat(response.status()).isEqualTo("synced");
        assertThat(response.investorId()).isEqualTo(investorId);
        assertThat(response.kycStatus()).isEqualTo(KycStatus.COMPLETED);
        assertThat(investor.getKycStatus()).isEqualTo(KycStatus.COMPLETED);
        assertThat(investor.getPanAadhaarLinkStatus()).isEqualTo("LINKED");
        assertThat(auditService.actionType).isEqualTo("EXTERNAL_KYC_WEBHOOK_SYNCED");
    }

    @Test
    void fetchKycRequestSuccessfulMarksInvestorCompleted() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setExternalKycRequestId("kycr_success");
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(investorRepository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cybrillaClient.fetchKycRequest("kycr_success")).thenReturn(json("""
                {"id":"kycr_success","status":"successful","object":"kyc_request"}
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.fetchKycRequest(investorId, "kycr_success", actorId);

        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.COMPLETED);
        assertThat(response.investor().getExternalKycStatus()).isEqualTo("successful");
    }

    @Test
    void applyInvestorKycMarksAlreadyKycCompliantWhenReadinessVerifiedWithoutFreshKyc() throws Exception {
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
        when(cybrillaClient.createKycCheck(any(Investor.class))).thenReturn(json("""
                {
                  "object": "pre_verification",
                  "id": "pv_verified",
                  "status": "completed",
                  "readiness": { "status": "verified", "code": null, "reason": null }
                }
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.applyInvestorKyc(investorId, null, actorId);

        verify(cybrillaClient).createKycCheck(investor);
        verify(cybrillaClient, never()).createKycRequest(any());
        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.COMPLETED);
        assertThat(response.kyc().alreadyKycCompliant()).isTrue();
        assertThat(response.kyc().freshKycRequired()).isFalse();
        assertThat(response.kyc().reKycSkipped()).isTrue();
    }

    @Test
    void runKycComplianceCheckMarksCompletedWhenStatusTrue() throws Exception {
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
        when(cybrillaClient.createKycComplianceCheck(any(), any())).thenReturn(json("""
                {"id":"kyc_1","pan":"AAAPA3751A","status":true,"constraints":[],"reason":null,"action":null}
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.runKycComplianceCheck(investorId, false, actorId);

        verify(cybrillaClient).createKycComplianceCheck("AAAPA3751A", null);
        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.COMPLETED);
        assertThat(response.investor().getExternalKycComplianceId()).isEqualTo("kyc_1");
        assertThat(response.kyc().state())
                .isEqualTo(com.platizio.wealthtech.dto.InvestorExternalKycResponse.KycState.VERIFIED);
        assertThat(response.kyc().alreadyKycCompliant()).isTrue();
        assertThat(response.kyc().reKycSkipped()).isTrue();
        assertThat(auditService.actionType).isEqualTo("KYC_COMPLIANCE_CHECKED");
    }

    @Test
    void runKycComplianceCheckFlagsConstraintsWhenInvestmentLimitPresent() throws Exception {
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
        when(cybrillaClient.createKycComplianceCheck(any(), any())).thenReturn(json("""
                {"id":"kyc_2","status":true,"reason":null,"action":null,
                 "constraints":[{"type":"investment_limit","amount":{"value":50000,"currency":"inr"}}]}
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.runKycComplianceCheck(investorId, false, actorId);

        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.COMPLETED);
        assertThat(response.kyc().state())
                .isEqualTo(com.platizio.wealthtech.dto.InvestorExternalKycResponse.KycState.VERIFIED_WITH_CONSTRAINTS);
        assertThat(response.kyc().constraints()).contains("investment_limit");
        assertThat(response.kyc().alreadyKycCompliant()).isTrue();
    }

    @Test
    void runKycComplianceCheckSignalsModificationWhenRegisteredNotValidated() throws Exception {
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
        when(cybrillaClient.createKycComplianceCheck(any(), any())).thenReturn(json("""
                {"id":"kyc_3","status":false,"reason":"incomplete","action":"modify","constraints":[]}
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.runKycComplianceCheck(investorId, false, actorId);

        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.RETRY_REQUIRED);
        assertThat(response.kyc().state())
                .isEqualTo(com.platizio.wealthtech.dto.InvestorExternalKycResponse.KycState.NEEDS_MODIFICATION);
        assertThat(response.kyc().alreadyKycCompliant()).isFalse();
        assertThat(response.kyc().freshKycRequired()).isFalse();
        assertThat(response.kyc().complianceAction()).isEqualTo("modify");
    }

    @Test
    void runKycComplianceCheckSignalsFreshKycWhenUnavailable() throws Exception {
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
        when(cybrillaClient.createKycComplianceCheck(any(), any())).thenReturn(json("""
                {"id":"kyc_4","status":false,"reason":"unavailable","action":"create","constraints":[]}
                """));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.runKycComplianceCheck(investorId, false, actorId);

        assertThat(response.investor().getKycStatus()).isEqualTo(KycStatus.NOT_STARTED);
        assertThat(response.kyc().state())
                .isEqualTo(com.platizio.wealthtech.dto.InvestorExternalKycResponse.KycState.FRESH_KYC_REQUIRED);
        assertThat(response.kyc().freshKycRequired()).isTrue();
        assertThat(response.kyc().alreadyKycCompliant()).isFalse();
    }

    @Test
    void createKycRequestSkipsReKycWhenInvestorAlreadyKycCompliant() {
        UUID actorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = investor(investorId, actorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setKycReadinessStatus("verified");
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));

        InvestorKycService service = service(investorRepository, distributorService, auditService, cybrillaClient);
        InvestorExternalKycResponse response = service.createKycRequest(investorId, null, actorId);

        verify(cybrillaClient, never()).createKycRequest(any());
        verify(cybrillaClient, never()).createInvestorProfile(any());
        assertThat(response.kyc().reKycSkipped()).isTrue();
        assertThat(response.kyc().alreadyKycCompliant()).isTrue();
        assertThat(auditService.actionType).isEqualTo("KYC_REQUEST_SKIPPED_ALREADY_VERIFIED");
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
                .containsEntry("date_of_birth", "1980-10-19")
                .containsEntry("father_name", "Rajesh Gupta");
        assertThat(payload.get("mobile")).isEqualTo(Map.of("isd", "+91", "number", "9876543210"));
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
        verify(cybrillaClient, never()).createKycCheck(any(Investor.class));
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

    @Test
    void scheduledKycSyncStopsBatchAndBacksOffAfterExternalFailure() {
        UUID distributorId = UUID.randomUUID();
        Investor first = investor(UUID.randomUUID(), distributorId);
        first.setKycStatus(KycStatus.IN_PROGRESS);
        first.setExternalKycCheckId("pv_first");
        Investor second = investor(UUID.randomUUID(), distributorId);
        second.setKycStatus(KycStatus.IN_PROGRESS);
        second.setExternalKycCheckId("pv_second");
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        CybrillaPreVerificationProperties poaProperties = new CybrillaPreVerificationProperties();
        RecordingBankVerificationStarter bankVerificationStarter = new RecordingBankVerificationStarter();
        when(investorRepository.findKycSyncCandidates(any(), any())).thenReturn(List.of(first, second));
        when(cybrillaClient.fetchKycCheck("pv_first")).thenThrow(new CybrillaApiException("Too Many Requests"));

        InvestorKycService service = new InvestorKycService(
                investorRepository,
                distributorService,
                auditService,
                cybrillaClient,
                poaProperties,
                bankVerificationStarter,
                mock(PlatformTransactionManager.class),
                60_000
        );

        assertThat(service.syncOutstandingExternalKycStatuses(50)).isZero();
        assertThat(service.syncOutstandingExternalKycStatuses(50)).isZero();

        verify(cybrillaClient, times(1)).fetchKycCheck("pv_first");
        verify(cybrillaClient, never()).fetchKycCheck("pv_second");
        verify(investorRepository, times(1)).findKycSyncCandidates(any(), any());
    }

    private InvestorKycService service(
            InvestorRepository investorRepository,
            RecordingDistributorService distributorService,
            RecordingAuditService auditService,
            CybrillaClient cybrillaClient
    ) {
        return service(
                investorRepository,
                distributorService,
                auditService,
                cybrillaClient,
                new RecordingBankVerificationStarter()
        );
    }

    private InvestorKycService service(
            InvestorRepository investorRepository,
            RecordingDistributorService distributorService,
            RecordingAuditService auditService,
            CybrillaClient cybrillaClient,
            BankVerificationStarter bankVerificationStarter
    ) {
        CybrillaPreVerificationProperties poaProperties = new CybrillaPreVerificationProperties();
        poaProperties.setBaseUrl("https://api.sandbox.cybrilla.com");
        return new InvestorKycService(
                investorRepository,
                distributorService,
                auditService,
                cybrillaClient,
                poaProperties,
                bankVerificationStarter,
                mock(PlatformTransactionManager.class),
                900_000
        );
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
        investor.setCybrillaInvestorId("fp_profile_" + investorId);
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

    private static class RecordingBankVerificationStarter implements BankVerificationStarter {
        private Investor investor;
        private UUID actorId;
        private String trigger;

        @Override
        public Investor startBankVerificationAfterKycCompletion(Investor investor, UUID actorId, String trigger) {
            this.investor = investor;
            this.actorId = actorId;
            this.trigger = trigger;
            return investor;
        }
    }
}
