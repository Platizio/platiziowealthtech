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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorKycForm;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.dto.ExternalKycSyncResponse;
import com.platizio.wealthtech.dto.InvestorKycFormResponse;
import com.platizio.wealthtech.dto.KycFormUpdateRequest;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.CybrillaIntegrationEnvironment;
import com.platizio.wealthtech.repository.InvestorKycFormRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class InvestorKycFormServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CALLBACK_BASE = "http://localhost:3000";

    private final InvestorService investorService = mock(InvestorService.class);
    private final InvestorKycFormRepository repository = mock(InvestorKycFormRepository.class);
    private final CybrillaClient cybrillaClient = mock(CybrillaClient.class);
    private final AuditService auditService = mock(AuditService.class);
    private final CybrillaIntegrationEnvironment integrationEnvironment = mock(CybrillaIntegrationEnvironment.class);

    private InvestorKycFormService service() {
        return service(false);
    }

    private InvestorKycFormService service(boolean mockFallbackOnAccessDenied) {
        when(repository.save(any(InvestorKycForm.class))).thenAnswer(inv -> inv.getArgument(0));
        when(integrationEnvironment.enforceSandboxPanPatterns()).thenReturn(true);
        return new InvestorKycFormService(
                investorService,
                repository,
                cybrillaClient,
                auditService,
                integrationEnvironment,
                CALLBACK_BASE,
                mockFallbackOnAccessDenied
        );
    }

    private Investor verifiedInvestor(UUID investorId, UUID actorId) {
        Investor investor = new Investor();
        investor.setDistributorId(actorId);
        investor.setFullName("John Doe");
        investor.setPan("AAAPA3751A");
        investor.setDateOfBirth(LocalDate.of(2000, 1, 2));
        investor.setKycStatus(KycStatus.COMPLETED);
        when(investorService.getInvestor(investorId, actorId)).thenReturn(investor);
        return investor;
    }

    @Test
    void createRejectsWhenKycNotVerified() {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Investor investor = verifiedInvestor(investorId, actorId);
        investor.setKycStatus(KycStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service().createModifyForm(investorId, actorId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already verified");
        verify(cybrillaClient, never()).createKycForm(any());
    }

    @Test
    void createRejectsInvalidSandboxPanBeforeCybrillaCall() {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Investor investor = verifiedInvestor(investorId, actorId);
        investor.setPan("ANIVM3003F");
        when(repository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(eq(investorId), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createModifyForm(investorId, actorId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("simulator PAN");
        verify(cybrillaClient, never()).createKycForm(any());
    }

    @Test
    void createResumesWhenOngoingFormExists() {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        verifiedInvestor(investorId, actorId);
        InvestorKycForm ongoing = new InvestorKycForm();
        ongoing.setStatus("created");
        ongoing.setExternalKycFormId("kycf_existing");
        when(repository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(eq(investorId), any()))
                .thenReturn(Optional.of(ongoing));

        InvestorKycFormResponse response = service().createModifyForm(investorId, actorId);

        assertThat(response.form().getExternalKycFormId()).isEqualTo("kycf_existing");
        assertThat(response.form().getStatus()).isEqualTo("created");
        verify(cybrillaClient, never()).createKycForm(any());
    }

    @Test
    void createUsesFrontendCallbackBaseWhenProvided() {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        verifiedInvestor(investorId, actorId);
        when(repository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(eq(investorId), any()))
                .thenReturn(Optional.empty());
        when(cybrillaClient.createKycForm(any())).thenAnswer(inv -> {
            Map<String, Object> payload = inv.getArgument(0);
            ObjectNode response = OBJECT_MAPPER.createObjectNode();
            response.put("object", "kyc_form");
            response.put("id", "kycf_custom");
            response.put("status", "created");
            response.put("proof_details_callback_url", String.valueOf(payload.get("proof_details_callback_url")));
            return response;
        });

        InvestorKycFormResponse response = service().createModifyForm(
                investorId,
                actorId,
                "http://localhost:3000");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(cybrillaClient).createKycForm(captor.capture());
        assertThat(captor.getValue().get("proof_details_callback_url"))
                .isEqualTo("http://localhost:3000/distributor/investors/" + investorId + "/kyc-modify/proof-callback");
        assertThat(response.form().getProofCallbackUrl())
                .isEqualTo("http://localhost:3000/distributor/investors/" + investorId + "/kyc-modify/proof-callback");
    }

    @Test
    void createFallsBackToMockWhenPartnerDeniedAndFallbackEnabled() {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        verifiedInvestor(investorId, actorId);
        when(repository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(eq(investorId), any()))
                .thenReturn(Optional.empty());
        when(cybrillaClient.createKycForm(any())).thenThrow(new CybrillaApiException(
                "Unable to create_kyc_form with Cybrilla POA: 403 FORBIDDEN response={\"error\":\"Partner not allowed to access kyc_forms.\"}"));

        InvestorKycFormResponse response = service(true).createModifyForm(investorId, actorId);

        assertThat(response.form().getExternalKycFormId()).startsWith("kycf_");
        assertThat(response.form().getStatus()).isEqualTo("created");
        assertThat(response.form().getProofFetchUrl())
                .isEqualTo(CALLBACK_BASE + "/distributor/investors/" + investorId + "/kyc-modify/proof-callback?sandbox=digilocker");
        assertThat(response.form().getProofStatus()).isEqualTo("pending");
    }

    @Test
    void refreshDoesNotAdvanceMockProofWhenPartnerDenied() {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        verifiedInvestor(investorId, actorId);
        InvestorKycForm existing = new InvestorKycForm();
        existing.setExternalKycFormId("kycf_existing");
        existing.setStatus("created");
        existing.setProofStatus("pending");
        existing.setProofFetchUrl(CALLBACK_BASE + "/distributor/investors/" + investorId + "/kyc-modify/proof-callback?sandbox=digilocker");
        existing.setExternalResponseJson("""
                {"object":"kyc_form","id":"kycf_existing","status":"created",
                 "proof_details":{"fetch_url":"%s","status":"pending"},
                 "requirements":{"fields_needed":["identity_proof","address","signature"]}}
                """.formatted(existing.getProofFetchUrl()));
        when(repository.findFirstByInvestorIdAndExternalKycFormId(investorId, "kycf_existing"))
                .thenReturn(Optional.of(existing));
        when(cybrillaClient.fetchKycForm("kycf_existing")).thenThrow(new CybrillaApiException(
                "Unable to fetch KYC form with Cybrilla POA: 403 FORBIDDEN response={\"error\":\"Partner not allowed to access kyc_forms.\"}"));

        InvestorKycFormResponse response = service(true).refreshForm(investorId, "kycf_existing", actorId);

        assertThat(response.form().getProofStatus()).isEqualTo("pending");
        assertThat(response.form().getStatus()).isEqualTo("created");
    }

    @Test
    void refreshUsesLocalMirrorWhenCybrillaReportsFormNotFound() throws Exception {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        verifiedInvestor(investorId, actorId);
        InvestorKycForm existing = new InvestorKycForm();
        existing.setExternalKycFormId("kycf_existing");
        existing.setStatus("created");
        existing.setProofStatus("fetched");
        existing.setExternalResponseJson("""
                {"object":"kyc_form","id":"kycf_existing","status":"created",
                 "proof_details":{"status":"fetched"},
                 "requirements":{"fields_needed":["signature"]}}
                """);
        when(repository.findFirstByInvestorIdAndExternalKycFormId(investorId, "kycf_existing"))
                .thenReturn(Optional.of(existing));
        when(cybrillaClient.fetchKycForm("kycf_existing")).thenThrow(new CybrillaApiException(
                "Unable to fetch KYC form with Cybrilla POA: 400 BAD_REQUEST response={\"errors\":[{\"message\":\"KYC form not found\"}]}"));

        InvestorKycFormResponse response = service(true).refreshForm(investorId, "kycf_existing", actorId);

        assertThat(response.form().getProofStatus()).isEqualTo("fetched");
        assertThat(response.form().getStatus()).isEqualTo("created");
    }

    @Test
    void simulateProofFetchMarksProofFetchedWhenSandboxEnabled() {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        verifiedInvestor(investorId, actorId);
        InvestorKycForm existing = new InvestorKycForm();
        existing.setExternalKycFormId("kycf_existing");
        existing.setStatus("created");
        existing.setProofFetchUrl("https://s.finprim.com/identity_documents/fetch_my_proof?form=kycf_existing");
        existing.setProofStatus("pending");
        existing.setExternalResponseJson("""
                {"object":"kyc_form","id":"kycf_existing","status":"created",
                 "proof_details":{"fetch_url":"https://s.finprim.com/identity_documents/fetch_my_proof?form=kycf_existing","status":"pending"},
                 "requirements":{"fields_needed":["identity_proof","address","signature"]}}
                """);
        when(repository.findFirstByInvestorIdAndExternalKycFormId(investorId, "kycf_existing"))
                .thenReturn(Optional.of(existing));

        InvestorKycFormResponse response = service(true).simulateProofFetch(investorId, "kycf_existing", actorId);

        assertThat(response.form().getProofStatus()).isEqualTo("fetched");
        assertThat(response.form().getFieldsNeededJson()).doesNotContain("identity_proof");
        verify(cybrillaClient, never()).fetchKycForm(any());
    }

    @Test
    void createBuildsModifyPayloadAndPersistsResponse() throws Exception {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        verifiedInvestor(investorId, actorId);
        when(repository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(eq(investorId), any()))
                .thenReturn(Optional.empty());
        when(cybrillaClient.createKycForm(any())).thenReturn(json("""
                {
                  "object":"kyc_form","id":"kycf_abc","type":"modify","status":"created","reason":null,
                  "proof_details":{"fetch_url":"https://s.finprim.com/fetch","status":"pending"},
                  "esign_details":{"esign_url":null,"status":null},
                  "signature_provided":false,
                  "requirements":{"fields_needed":["identity_proof","address","signature"]},
                  "expires_at":"2025-12-30T12:41:06.649Z"
                }
                """));

        InvestorKycFormResponse response = service().createModifyForm(investorId, actorId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(cybrillaClient).createKycForm(payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload)
                .containsEntry("type", "modify")
                .containsEntry("pan", "AAAPA3751A")
                .containsEntry("name", "John Doe")
                .containsEntry("date_of_birth", "2000-01-02");
        assertThat(String.valueOf(payload.get("proof_details_callback_url")))
                .isEqualTo(CALLBACK_BASE + "/distributor/investors/" + investorId + "/kyc-modify/proof-callback");
        assertThat(String.valueOf(payload.get("esign_callback_url")))
                .isEqualTo(CALLBACK_BASE + "/distributor/investors/" + investorId + "/kyc-modify/esign-callback");

        InvestorKycForm form = response.form();
        assertThat(form.getExternalKycFormId()).isEqualTo("kycf_abc");
        assertThat(form.getStatus()).isEqualTo("created");
        assertThat(form.getProofFetchUrl()).isEqualTo("https://s.finprim.com/fetch");
        assertThat(form.getProofStatus()).isEqualTo("pending");
        assertThat(form.getSignatureProvided()).isFalse();
        assertThat(form.getFieldsNeededJson()).contains("signature");
        assertThat(form.getExpiresAt()).isNotNull();
    }

    @Test
    void updateMapsNestedHashesAndArrays() throws Exception {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        verifiedInvestor(investorId, actorId);
        InvestorKycForm existing = new InvestorKycForm();
        existing.setExternalKycFormId("kycf_abc");
        existing.setStatus("created");
        when(repository.findFirstByInvestorIdAndExternalKycFormId(investorId, "kycf_abc"))
                .thenReturn(Optional.of(existing));
        when(cybrillaClient.updateKycForm(eq("kycf_abc"), any()))
                .thenReturn(json("{\"object\":\"kyc_form\",\"id\":\"kycf_abc\",\"status\":\"created\"}"));

        KycFormUpdateRequest request = new KycFormUpdateRequest(
                "joy@me.com", "+91", "9876543210", "resident", "male", "unmarried",
                "Davy Johns", null, "private_sector_service", "1210", "in", "Madurai",
                "above_10lakh_upto_25lakh", "no_exposure", List.of("in"), "in",
                false, 9.354, 77.453);

        service().updateForm(investorId, "kycf_abc", request, actorId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(cybrillaClient).updateKycForm(eq("kycf_abc"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload).containsEntry("email_address", "joy@me.com")
                .containsEntry("gender", "male")
                .containsEntry("phone_number", Map.of("isd", "+91", "number", "9876543210"))
                .containsEntry("geo_location", Map.of("latitude", 9.354, "longitude", 77.453))
                .containsEntry("citizenship_countries", List.of("in"))
                .containsEntry("tax_residency_other_than_india", false);
    }

    @Test
    void retryProofRejectedWhenNotFailed() {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        verifiedInvestor(investorId, actorId);
        InvestorKycForm existing = new InvestorKycForm();
        existing.setExternalKycFormId("kycf_abc");
        existing.setProofStatus("pending");
        when(repository.findFirstByInvestorIdAndExternalKycFormId(investorId, "kycf_abc"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service().retryProofDetailsFetch(investorId, "kycf_abc", actorId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("failed");
        verify(cybrillaClient, never()).retryKycFormProofDetailsFetch(any());
    }

    @Test
    void webhookIgnoredWhenNoMatchingForm() throws Exception {
        when(repository.findByExternalKycFormId("kycf_missing")).thenReturn(Optional.empty());

        ExternalKycSyncResponse result = service().handleKycFormWebhook(json("""
                {"type":"kyc_form.submitted","data":{"object":{"object":"kyc_form","id":"kycf_missing"}}}
                """));

        assertThat(result.status()).isEqualTo("ignored_no_matching_form");
        verify(cybrillaClient, never()).fetchKycForm(any());
    }

    @Test
    void webhookRefetchesAndAppliesCanonicalState() throws Exception {
        UUID investorId = UUID.randomUUID();
        InvestorKycForm existing = new InvestorKycForm();
        existing.setInvestorId(investorId);
        existing.setDistributorId(UUID.randomUUID());
        existing.setExternalKycFormId("kycf_abc");
        existing.setStatus("awaiting_esign");
        when(repository.findByExternalKycFormId("kycf_abc")).thenReturn(Optional.of(existing));
        when(cybrillaClient.fetchKycForm("kycf_abc")).thenReturn(json("""
                {"object":"kyc_form","id":"kycf_abc","status":"submitted","signature_provided":true}
                """));

        ExternalKycSyncResponse result = service().handleKycFormWebhook(json("""
                {"type":"kyc_form.submitted","data":{"object":{"object":"kyc_form","id":"kycf_abc"}}}
                """));

        assertThat(result.status()).isEqualTo("synced");
        assertThat(result.investorId()).isEqualTo(investorId);
        verify(cybrillaClient).fetchKycForm("kycf_abc");
        assertThat(existing.getStatus()).isEqualTo("submitted");
        assertThat(existing.getSignatureProvided()).isTrue();
    }

    private static JsonNode json(String raw) throws Exception {
        return OBJECT_MAPPER.readTree(raw);
    }
}
