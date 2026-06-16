package com.platizio.wealthtech.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import com.platizio.wealthtech.validation.PanFormat;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Drives the Cybrilla POA KYC Forms "modify" workflow on behalf of the
 * distributor frontend. Every outbound request and the latest inbound
 * {@code kyc_form} object are mirrored locally ({@link InvestorKycForm}) so the
 * Digilocker + eSign journey can be resumed and reconciled via webhook/polling.
 */
@Service
public class InvestorKycFormService {

    private static final Logger logger = LoggerFactory.getLogger(InvestorKycFormService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String FINPRIM_SANDBOX_BASE = "https://s.finprim.com";

    /** States in which a kyc_form is still "on-going" (blocks creating another). */
    static final List<String> ACTIVE_STATES =
            List.of("under_review", "created", "awaiting_esign", "awaiting_submission");

    private static final Set<String> ALLOWED_SIGNATURE_CONTENT_TYPES = Set.of(
            "image/png", "image/jpg", "image/jpeg", "application/pdf"
    );
    private static final long MAX_SIGNATURE_BYTES = 5L * 1024 * 1024;

    private final InvestorService investorService;
    private final InvestorKycFormRepository repository;
    private final CybrillaClient cybrillaClient;
    private final AuditService auditService;
    private final CybrillaIntegrationEnvironment integrationEnvironment;
    private final String callbackBaseUrl;
    private final boolean mockFallbackOnAccessDenied;

    public InvestorKycFormService(
            InvestorService investorService,
            InvestorKycFormRepository repository,
            CybrillaClient cybrillaClient,
            AuditService auditService,
            CybrillaIntegrationEnvironment integrationEnvironment,
            @Value("${cybrilla.kyc-form.callback-base-url:http://localhost:3000}") String callbackBaseUrl,
            @Value("${cybrilla.kyc-form.mock-fallback-on-access-denied:false}") boolean mockFallbackOnAccessDenied
    ) {
        this.investorService = investorService;
        this.repository = repository;
        this.cybrillaClient = cybrillaClient;
        this.auditService = auditService;
        this.integrationEnvironment = integrationEnvironment;
        this.callbackBaseUrl = trimTrailingSlash(callbackBaseUrl);
        this.mockFallbackOnAccessDenied = mockFallbackOnAccessDenied;
    }

    @Transactional(readOnly = true)
    public InvestorKycFormResponse getLatestForm(UUID investorId, UUID actorId) {
        investorService.getInvestor(investorId, actorId);
        InvestorKycForm form = resolveLatestForm(investorId);
        return toResponse(form);
    }

    @Transactional
    public InvestorKycFormResponse createModifyForm(UUID investorId, UUID actorId) {
        return createModifyForm(investorId, actorId, null);
    }

    @Transactional
    public InvestorKycFormResponse createModifyForm(UUID investorId, UUID actorId, String callbackBaseOverride) {
        Investor investor = investorService.getInvestor(investorId, actorId);
        if (investor.getKycStatus() != KycStatus.COMPLETED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "KYC modify is only available for investors whose KYC is already verified. Current KYC status: "
                            + investor.getKycStatus());
        }
        if (investor.getDateOfBirth() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Investor date of birth is required (as per PAN/ITD records) to start a KYC modify form.");
        }
        validatePanForKycForm(investor);
        Optional<InvestorKycForm> ongoing =
                repository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(investorId, ACTIVE_STATES);
        if (ongoing.isPresent()) {
            InvestorKycForm existing = ongoing.get();
            logger.info(
                    "kyc_form_workflow operation='create' action='resume_existing' investor_id='{}' kyc_form_id='{}' status='{}'",
                    investorId,
                    existing.getExternalKycFormId(),
                    existing.getStatus());
            return toResponse(existing);
        }

        String callbackBase = resolveCallbackBaseUrl(callbackBaseOverride);
        String proofCallback = buildCallbackUrl(investorId, "proof-callback", callbackBase);
        String esignCallback = buildCallbackUrl(investorId, "esign-callback", callbackBase);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "modify");
        payload.put("pan", investor.getPan());
        payload.put("name", investor.getFullName());
        payload.put("date_of_birth", investor.getDateOfBirth().toString());
        payload.put("proof_details_callback_url", proofCallback);
        payload.put("esign_callback_url", esignCallback);

        JsonNode response = createKycForm(payload);

        InvestorKycForm form = new InvestorKycForm();
        form.setInvestorId(investorId);
        form.setDistributorId(investor.getDistributorId());
        form.setType("modify");
        form.setPan(investor.getPan());
        form.setName(investor.getFullName());
        form.setDateOfBirth(investor.getDateOfBirth());
        form.setProofCallbackUrl(proofCallback);
        form.setEsignCallbackUrl(esignCallback);
        form.setExternalRequestJson(writeJson(payload));
        applyExternalResponse(form, response);

        InvestorKycForm saved = repository.save(form);
        auditService.log("INVESTOR", investorId, "KYC_FORM_CREATED", actorId, auditDetails(saved));
        logger.info("kyc_form_workflow operation='create' investor_id='{}' kyc_form_id='{}' status='{}'",
                investorId, saved.getExternalKycFormId(), saved.getStatus());
        return new InvestorKycFormResponse(saved, response);
    }

    @Transactional
    public InvestorKycFormResponse updateForm(UUID investorId, String kycFormId, KycFormUpdateRequest request, UUID actorId) {
        investorService.getInvestor(investorId, actorId);
        InvestorKycForm form = loadForm(investorId, kycFormId);

        Map<String, Object> payload = buildUpdatePayload(request);
        if (payload.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No KYC form fields supplied to update.");
        }
        form.setExternalRequestJson(writeJson(payload));

        JsonNode response = updateKycForm(form.getExternalKycFormId(), payload, form);
        applyExternalResponse(form, response);

        InvestorKycForm saved = repository.save(form);
        auditService.log("INVESTOR", investorId, "KYC_FORM_UPDATED", actorId, auditDetails(saved));
        return new InvestorKycFormResponse(saved, response);
    }

    @Transactional
    public InvestorKycFormResponse refreshForm(UUID investorId, String kycFormId, UUID actorId) {
        investorService.getInvestor(investorId, actorId);
        InvestorKycForm form = loadForm(investorId, kycFormId);
        JsonNode response = fetchKycForm(form.getExternalKycFormId(), form);
        applyExternalResponse(form, response);
        InvestorKycForm saved = repository.save(form);
        auditService.log("INVESTOR", investorId, "KYC_FORM_REFRESHED", actorId, auditDetails(saved));
        return new InvestorKycFormResponse(saved, response);
    }

    @Transactional
    public InvestorKycFormResponse uploadSignature(UUID investorId, String kycFormId, MultipartFile file, UUID actorId) {
        investorService.getInvestor(investorId, actorId);
        InvestorKycForm form = loadForm(investorId, kycFormId);
        validateSignatureFile(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read the uploaded signature file.", ex);
        }
        String filename = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "signature";
        String contentType = StringUtils.hasText(file.getContentType()) ? file.getContentType() : "application/octet-stream";

        JsonNode response = uploadKycFormSignature(form.getExternalKycFormId(), bytes, filename, contentType, form);
        applyExternalResponse(form, response);
        InvestorKycForm saved = repository.save(form);
        auditService.log("INVESTOR", investorId, "KYC_FORM_SIGNATURE_UPLOADED", actorId, auditDetails(saved));
        return new InvestorKycFormResponse(saved, response);
    }

    /**
     * Local sandbox only: simulates a successful Digilocker proof fetch when Cybrilla
     * {@code kyc_forms} is unavailable and the app is using mock POA responses.
     * Real Digilocker URLs are issued only by Cybrilla after partner {@code kyc_forms} access.
     */
    @Transactional
    public InvestorKycFormResponse simulateProofFetch(UUID investorId, String kycFormId, UUID actorId) {
        requireSandboxSimulationEnabled();
        investorService.getInvestor(investorId, actorId);
        InvestorKycForm form = loadForm(investorId, kycFormId);
        JsonNode response = mockProofFetchedResponse(form);
        applyExternalResponse(form, response);
        InvestorKycForm saved = repository.save(form);
        auditService.log("INVESTOR", investorId, "KYC_FORM_PROOF_SIMULATED", actorId, auditDetails(saved));
        logger.info(
                "kyc_form_workflow operation='simulate_proof_fetch' investor_id='{}' kyc_form_id='{}'",
                investorId,
                saved.getExternalKycFormId());
        return toResponse(saved);
    }

    /**
     * Local sandbox only: simulates eSign completion for mock {@code kyc_form} flows.
     */
    @Transactional
    public InvestorKycFormResponse simulateEsign(UUID investorId, String kycFormId, UUID actorId) {
        requireSandboxSimulationEnabled();
        investorService.getInvestor(investorId, actorId);
        InvestorKycForm form = loadForm(investorId, kycFormId);
        JsonNode response = mockEsignCompletedResponse(form);
        applyExternalResponse(form, response);
        InvestorKycForm saved = repository.save(form);
        auditService.log("INVESTOR", investorId, "KYC_FORM_ESIGN_SIMULATED", actorId, auditDetails(saved));
        logger.info(
                "kyc_form_workflow operation='simulate_esign' investor_id='{}' kyc_form_id='{}'",
                investorId,
                saved.getExternalKycFormId());
        return toResponse(saved);
    }

    @Transactional
    public InvestorKycFormResponse retryProofDetailsFetch(UUID investorId, String kycFormId, UUID actorId) {
        investorService.getInvestor(investorId, actorId);
        InvestorKycForm form = loadForm(investorId, kycFormId);
        if (form.getProofStatus() != null && !"failed".equalsIgnoreCase(form.getProofStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Proof details fetch can only be retried when its status is 'failed' (current: " + form.getProofStatus() + ").");
        }
        JsonNode response = retryKycFormProofDetailsFetch(form.getExternalKycFormId(), form);
        applyExternalResponse(form, response);
        InvestorKycForm saved = repository.save(form);
        auditService.log("INVESTOR", investorId, "KYC_FORM_PROOF_RETRIED", actorId, auditDetails(saved));
        return new InvestorKycFormResponse(saved, response);
    }

    /**
     * Reconciles a {@code kyc_form.*} webhook event against the local mirror.
     * Idempotent: it always re-fetches the canonical object from Cybrilla so
     * out-of-order or duplicate deliveries converge on the same final state.
     */
    @Transactional
    public ExternalKycSyncResponse handleKycFormWebhook(JsonNode payload) {
        JsonNode dataObject = webhookDataObject(payload);
        String eventType = textField(payload, "type");
        String externalId = textField(dataObject, "id");
        if (!StringUtils.hasText(externalId)) {
            return new ExternalKycSyncResponse("ignored_missing_external_id", eventType, null, null, null);
        }
        Optional<InvestorKycForm> existing = repository.findByExternalKycFormId(externalId.trim());
        if (existing.isEmpty()) {
            return new ExternalKycSyncResponse("ignored_no_matching_form", eventType, externalId, null, null);
        }
        InvestorKycForm form = existing.get();
        JsonNode canonical;
        try {
            canonical = fetchKycForm(externalId.trim(), form);
        } catch (RuntimeException ex) {
            logger.warn("kyc_form_webhook refetch_failed kyc_form_id='{}' reason='{}' falling_back_to_payload", externalId, ex.getMessage());
            canonical = dataObject;
        }
        applyExternalResponse(form, canonical);
        InvestorKycForm saved = repository.save(form);
        auditService.log("INVESTOR", saved.getInvestorId(), "KYC_FORM_WEBHOOK_SYNCED", saved.getDistributorId(), auditDetails(saved));
        return new ExternalKycSyncResponse("synced", eventType, externalId, saved.getInvestorId(), null);
    }

    /** Polling fallback: refresh every still-active kyc_form. Best-effort per form. */
    @Transactional
    public int syncActiveForms(int limit) {
        List<InvestorKycForm> active = repository.findByStatusIn(ACTIVE_STATES);
        int synced = 0;
        for (InvestorKycForm form : active) {
            if (limit > 0 && synced >= limit) {
                break;
            }
            if (!StringUtils.hasText(form.getExternalKycFormId())) {
                continue;
            }
            try {
                JsonNode response = fetchKycForm(form.getExternalKycFormId(), form);
                applyExternalResponse(form, response);
                repository.save(form);
                synced++;
            } catch (RuntimeException ex) {
                logger.warn("kyc_form_sync form_id='{}' status='failed' reason='{}'", form.getExternalKycFormId(), ex.getMessage());
            }
        }
        return synced;
    }

    private InvestorKycForm loadForm(UUID investorId, String kycFormId) {
        return repository.findFirstByInvestorIdAndExternalKycFormId(investorId, kycFormId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No KYC form '" + kycFormId + "' found for this investor."));
    }

    private Map<String, Object> buildUpdatePayload(KycFormUpdateRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (request == null) {
            return payload;
        }
        putIfText(payload, "email_address", request.emailAddress());
        if (StringUtils.hasText(request.phoneNumber())) {
            Map<String, Object> phone = new LinkedHashMap<>();
            phone.put("isd", StringUtils.hasText(request.phoneIsd()) ? request.phoneIsd() : "+91");
            phone.put("number", request.phoneNumber());
            payload.put("phone_number", phone);
        }
        putIfText(payload, "residential_status", request.residentialStatus());
        putIfText(payload, "gender", request.gender());
        putIfText(payload, "marital_status", request.maritalStatus());
        putIfText(payload, "father_name", request.fatherName());
        putIfText(payload, "spouse_name", request.spouseName());
        putIfText(payload, "occupation_type", request.occupationType());
        putIfText(payload, "aadhaar_number", request.aadhaarNumber());
        putIfText(payload, "country_of_birth", request.countryOfBirth());
        putIfText(payload, "place_of_birth", request.placeOfBirth());
        putIfText(payload, "income_slab", request.incomeSlab());
        putIfText(payload, "pep_details", request.pepDetails());
        if (request.citizenshipCountries() != null && !request.citizenshipCountries().isEmpty()) {
            payload.put("citizenship_countries", request.citizenshipCountries());
        }
        putIfText(payload, "nationality_country", request.nationalityCountry());
        if (request.taxResidencyOtherThanIndia() != null) {
            payload.put("tax_residency_other_than_india", request.taxResidencyOtherThanIndia());
        }
        if (request.geoLatitude() != null && request.geoLongitude() != null) {
            Map<String, Object> geo = new LinkedHashMap<>();
            geo.put("latitude", request.geoLatitude());
            geo.put("longitude", request.geoLongitude());
            payload.put("geo_location", geo);
        }
        return payload;
    }

    private void applyExternalResponse(InvestorKycForm form, JsonNode response) {
        if (response == null || response.isNull()) {
            return;
        }
        String id = textField(response, "id");
        if (StringUtils.hasText(id)) {
            form.setExternalKycFormId(id);
        }
        String type = textField(response, "type");
        if (StringUtils.hasText(type)) {
            form.setType(type);
        }
        form.setStatus(textField(response, "status"));
        form.setReason(textField(response, "reason"));

        String pan = textField(response, "pan");
        if (StringUtils.hasText(pan)) {
            form.setPan(pan);
        }
        String name = textField(response, "name");
        if (StringUtils.hasText(name)) {
            form.setName(name);
        }

        JsonNode proof = response.path("proof_details");
        form.setProofFetchUrl(textField(proof, "fetch_url"));
        form.setProofStatus(textField(proof, "status"));

        JsonNode esign = response.path("esign_details");
        form.setEsignUrl(textField(esign, "esign_url"));
        form.setEsignStatus(textField(esign, "status"));

        JsonNode signatureProvided = response.path("signature_provided");
        if (signatureProvided.isBoolean()) {
            form.setSignatureProvided(signatureProvided.asBoolean());
        }

        JsonNode fieldsNeeded = response.path("requirements").path("fields_needed");
        if (fieldsNeeded.isArray() && fieldsNeeded.size() > 0) {
            form.setFieldsNeededJson(fieldsNeeded.toString());
        } else {
            form.setFieldsNeededJson(null);
        }

        form.setExpiresAt(parseTimestamp(textField(response, "expires_at")));
        form.setExternalResponseJson(response.toString());
        form.setLastSyncedAt(OffsetDateTime.now());
    }

    private JsonNode webhookDataObject(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        JsonNode dataObject = payload.path("data").path("object");
        if (!dataObject.isMissingNode() && !dataObject.isNull()) {
            return dataObject;
        }
        JsonNode data = payload.path("data");
        if (!data.isMissingNode() && !data.isNull()) {
            return data;
        }
        return payload;
    }

    private void validateSignatureFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A signature file is required.");
        }
        if (file.getSize() > MAX_SIGNATURE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Signature file must be 5 MB or smaller.");
        }
        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_SIGNATURE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported signature file type '" + contentType + "'. Allowed: png, jpg, jpeg, pdf.");
        }
    }

    private String resolveCallbackBaseUrl(String callbackBaseOverride) {
        if (!StringUtils.hasText(callbackBaseOverride)) {
            return callbackBaseUrl;
        }
        String normalized = trimTrailingSlash(callbackBaseOverride.trim());
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "callbackBaseUrl must start with http:// or https://");
        }
        if (normalized.contains("/distributor/")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "callbackBaseUrl must be the frontend origin only (e.g. http://localhost:3000), not a full path.");
        }
        return normalized;
    }

    private String buildCallbackUrl(UUID investorId, String suffix) {
        return buildCallbackUrl(investorId, suffix, callbackBaseUrl);
    }

    private String buildCallbackUrl(UUID investorId, String suffix, String base) {
        return base + "/distributor/investors/" + investorId + "/kyc-modify/" + suffix;
    }

    private static void putIfText(Map<String, Object> payload, String key, String value) {
        if (StringUtils.hasText(value)) {
            payload.put(key, value);
        }
    }

    private static String textField(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || !StringUtils.hasText(value.asText())) {
            return null;
        }
        return value.asText().trim();
    }

    private static OffsetDateTime parseTimestamp(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static JsonNode parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (IOException ex) {
            return null;
        }
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (IOException ex) {
            return null;
        }
    }

    private void validatePanForKycForm(Investor investor) {
        String pan = PanFormat.normalize(investor.getPan());
        if (!StringUtils.hasText(pan)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Investor PAN is required to start a KYC modify form.");
        }
        try {
            if (integrationEnvironment.enforceSandboxPanPatterns()) {
                PanFormat.validateBeforePoaApi(pan, true);
            } else {
                PanFormat.validateIndianPan(pan);
            }
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "http://localhost:3000";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String auditDetails(InvestorKycForm form) {
        return "{\"kycFormId\":\"" + safe(form.getExternalKycFormId())
                + "\",\"status\":\"" + safe(form.getStatus())
                + "\",\"proofStatus\":\"" + safe(form.getProofStatus())
                + "\",\"esignStatus\":\"" + safe(form.getEsignStatus())
                + "\",\"signatureProvided\":" + Boolean.TRUE.equals(form.getSignatureProvided())
                + "}";
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }

    private JsonNode createKycForm(Map<String, Object> payload) {
        try {
            return cybrillaClient.createKycForm(payload);
        } catch (CybrillaApiException ex) {
            return mockKycFormResponseOrThrow("create", ex, null, () -> mockCreateKycFormResponse(payload));
        }
    }

    private JsonNode updateKycForm(String kycFormId, Map<String, Object> payload, InvestorKycForm localMirror) {
        try {
            return cybrillaClient.updateKycForm(kycFormId, payload);
        } catch (CybrillaApiException ex) {
            return mockKycFormResponseOrThrow(
                    "update",
                    ex,
                    localMirror,
                    () -> mockUpdateKycFormResponse(kycFormId, localMirror));
        }
    }

    private JsonNode fetchKycForm(String kycFormId, InvestorKycForm localMirror) {
        try {
            return cybrillaClient.fetchKycForm(kycFormId);
        } catch (CybrillaApiException ex) {
            return mockKycFormResponseOrThrow(
                    "fetch",
                    ex,
                    localMirror,
                    () -> mockFetchKycFormResponse(kycFormId, localMirror));
        }
    }

    private JsonNode uploadKycFormSignature(
            String kycFormId,
            byte[] bytes,
            String filename,
            String contentType,
            InvestorKycForm localMirror
    ) {
        try {
            return cybrillaClient.uploadKycFormSignature(kycFormId, bytes, filename, contentType);
        } catch (CybrillaApiException ex) {
            return mockKycFormResponseOrThrow(
                    "upload_signature",
                    ex,
                    localMirror,
                    () -> mockSignatureKycFormResponse(kycFormId));
        }
    }

    private JsonNode retryKycFormProofDetailsFetch(String kycFormId, InvestorKycForm localMirror) {
        try {
            return cybrillaClient.retryKycFormProofDetailsFetch(kycFormId);
        } catch (CybrillaApiException ex) {
            return mockKycFormResponseOrThrow(
                    "retry_proof_details_fetch",
                    ex,
                    localMirror,
                    () -> mockRetryProofKycFormResponse(kycFormId, localMirror));
        }
    }

    private JsonNode mockKycFormResponseOrThrow(
            String operation,
            CybrillaApiException ex,
            InvestorKycForm localMirror,
            java.util.function.Supplier<JsonNode> mockResponse
    ) {
        if (!mockFallbackOnAccessDenied) {
            throw ex;
        }
        if (isKycFormAccessDenied(ex)) {
            logger.warn("kyc_form operation='{}' fallback='mock' reason='partner_access_denied'", operation);
            if (localMirror != null) {
                return localMirrorResponse(localMirror, mockResponse);
            }
            return mockResponse.get();
        }
        if (isKycFormNotFound(ex) && localMirror != null) {
            logger.warn(
                    "kyc_form operation='{}' fallback='local_mirror' kyc_form_id='{}'",
                    operation,
                    localMirror.getExternalKycFormId());
            return localMirrorResponse(localMirror, mockResponse);
        }
        throw ex;
    }

    private JsonNode localMirrorResponse(InvestorKycForm form, java.util.function.Supplier<JsonNode> mockResponse) {
        JsonNode existing = parseJson(form.getExternalResponseJson());
        if (existing != null && !existing.isNull()) {
            return existing;
        }
        return mockResponse.get();
    }

    private static boolean isKycFormAccessDenied(CybrillaApiException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return (lower.contains("403") || lower.contains("forbidden"))
                && lower.contains("kyc_form");
    }

    private static boolean isKycFormNotFound(CybrillaApiException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return (lower.contains("400") || lower.contains("not found"))
                && (lower.contains("kyc form not found") || lower.contains("get.id"));
    }

    private JsonNode mockCreateKycFormResponse(Map<String, Object> payload) {
        String mockId = "kycf_" + UUID.randomUUID().toString().replace("-", "");
        ObjectNode response = mockKycFormBase(mockId, "created");
        response.put("pan", textValue(payload, "pan"));
        response.put("name", textValue(payload, "name"));
        response.put("date_of_birth", textValue(payload, "date_of_birth"));
        response.put("proof_details_callback_url", textValue(payload, "proof_details_callback_url"));
        response.put("esign_callback_url", textValue(payload, "esign_callback_url"));
        response.putObject("proof_details")
                .put("fetch_url", mockProofFetchUrl(null, payload, mockId))
                .put("status", "pending");
        response.putObject("esign_details").putNull("esign_url").putNull("status");
        response.putObject("requirements").putArray("fields_needed")
                .add("identity_proof").add("address").add("signature");
        return response;
    }

    private JsonNode mockUpdateKycFormResponse(String kycFormId, InvestorKycForm localMirror) {
        ObjectNode response = existingOrMockBase(localMirror, "created");
        response.put("id", kycFormId);
        if (!response.has("proof_details") || response.path("proof_details").isNull()) {
            response.putObject("proof_details")
                    .put("fetch_url", mockProofFetchUrl(localMirror, null, kycFormId))
                    .put("status", StringUtils.hasText(localMirror == null ? null : localMirror.getProofStatus())
                            ? localMirror.getProofStatus()
                            : "pending");
        }
        response.putObject("requirements").putArray("fields_needed").add("signature");
        return response;
    }

    /**
     * Fetch must never advance mock workflow state — only explicit simulate/callback/webhook paths do.
     */
    private JsonNode mockFetchKycFormResponse(String kycFormId, InvestorKycForm localMirror) {
        if (localMirror != null) {
            JsonNode existing = parseJson(localMirror.getExternalResponseJson());
            if (existing instanceof ObjectNode objectNode) {
                return objectNode.deepCopy();
            }
            ObjectNode response = mockKycFormBase(
                    kycFormId,
                    StringUtils.hasText(localMirror.getStatus()) ? localMirror.getStatus() : "created");
            response.putObject("proof_details")
                    .put("fetch_url", StringUtils.hasText(localMirror.getProofFetchUrl())
                            ? localMirror.getProofFetchUrl()
                            : mockProofFetchUrl(localMirror, null, kycFormId))
                    .put("status", StringUtils.hasText(localMirror.getProofStatus())
                            ? localMirror.getProofStatus()
                            : "pending");
            if (StringUtils.hasText(localMirror.getEsignUrl()) || StringUtils.hasText(localMirror.getEsignStatus())) {
                response.putObject("esign_details")
                        .put("esign_url", localMirror.getEsignUrl())
                        .put("status", localMirror.getEsignStatus());
            }
            response.put("signature_provided", Boolean.TRUE.equals(localMirror.getSignatureProvided()));
            return response;
        }
        ObjectNode response = mockKycFormBase(kycFormId, "created");
        response.putObject("proof_details")
                .put("fetch_url", mockProofFetchUrl(null, null, kycFormId))
                .put("status", "pending");
        response.putObject("esign_details").putNull("esign_url").putNull("status");
        response.put("signature_provided", false);
        response.putObject("requirements").putArray("fields_needed")
                .add("identity_proof").add("address").add("signature");
        return response;
    }

    private JsonNode mockSignatureKycFormResponse(String kycFormId) {
        ObjectNode response = mockKycFormBase(kycFormId, "created");
        response.put("signature_provided", true);
        return response;
    }

    private JsonNode mockRetryProofKycFormResponse(String kycFormId, InvestorKycForm localMirror) {
        ObjectNode response = existingOrMockBase(
                localMirror != null ? localMirror : mockMirrorStub(kycFormId),
                "created");
        response.put("id", kycFormId);
        response.putObject("proof_details")
                .put("fetch_url", mockProofFetchUrl(localMirror, null, kycFormId) + "&retry=1")
                .put("status", "pending");
        return response;
    }

    private static InvestorKycForm mockMirrorStub(String kycFormId) {
        InvestorKycForm stub = new InvestorKycForm();
        stub.setExternalKycFormId(kycFormId);
        return stub;
    }

    private JsonNode mockProofFetchedResponse(InvestorKycForm form) {
        ObjectNode response = existingOrMockBase(form, "created");
        ObjectNode proof = response.putObject("proof_details");
        proof.put("fetch_url", StringUtils.hasText(form.getProofFetchUrl())
                ? form.getProofFetchUrl()
                : mockProofFetchUrl(form, null, form.getExternalKycFormId()));
        proof.put("status", "fetched");
        JsonNode fieldsNeeded = response.path("requirements").path("fields_needed");
        if (fieldsNeeded.isArray()) {
            ArrayNode remaining = OBJECT_MAPPER.createArrayNode();
            for (JsonNode field : fieldsNeeded) {
                String value = field.asText("");
                if (!"identity_proof".equals(value) && !"address".equals(value)) {
                    remaining.add(value);
                }
            }
            if (remaining.isEmpty()) {
                response.putObject("requirements").putNull("fields_needed");
            } else {
                response.putObject("requirements").set("fields_needed", remaining);
            }
        }
        return response;
    }

    private JsonNode mockEsignCompletedResponse(InvestorKycForm form) {
        ObjectNode response = existingOrMockBase(form, "awaiting_submission");
        response.putObject("proof_details")
                .put("fetch_url", StringUtils.hasText(form.getProofFetchUrl())
                        ? form.getProofFetchUrl()
                        : mockProofFetchUrl(form, null, form.getExternalKycFormId()))
                .put("status", "fetched");
        response.putObject("esign_details")
                .put("esign_url", StringUtils.hasText(form.getEsignUrl())
                        ? form.getEsignUrl()
                        : mockEsignUrl(form, null, form.getExternalKycFormId()))
                .put("status", "successful");
        response.put("signature_provided", Boolean.TRUE.equals(form.getSignatureProvided()));
        response.putObject("requirements").putNull("fields_needed");
        return response;
    }

    private ObjectNode existingOrMockBase(InvestorKycForm form, String defaultStatus) {
        JsonNode existing = parseJson(form.getExternalResponseJson());
        if (existing instanceof ObjectNode objectNode) {
            return objectNode.deepCopy();
        }
        return mockKycFormBase(form.getExternalKycFormId(), defaultStatus);
    }

    private void requireSandboxSimulationEnabled() {
        if (!mockFallbackOnAccessDenied) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "KYC form sandbox simulation is only available when CYBRILLA_KYC_FORM_MOCK_FALLBACK is enabled "
                            + "(local profile). For real Digilocker, ask Cybrilla to enable kyc_forms on your partner account.");
        }
    }

    /**
     * Sandbox mock for {@code proof_details.fetch_url}. When partner {@code kyc_forms} is unavailable,
     * route the investor through the Platizio proof callback (same URL family Cybrilla uses post-Digilocker).
     */
    private String mockProofFetchUrl(InvestorKycForm form, Map<String, Object> payload, String kycFormId) {
        String callback = textValue(payload, "proof_details_callback_url");
        if (!StringUtils.hasText(callback) && form != null) {
            callback = form.getProofCallbackUrl();
        }
        if (StringUtils.hasText(callback)) {
            return callback + (callback.contains("?") ? "&" : "?") + "sandbox=digilocker";
        }
        return FINPRIM_SANDBOX_BASE + "/identity_documents/fetch_my_proof?form=" + kycFormId;
    }

    private String mockEsignUrl(InvestorKycForm form, Map<String, Object> payload, String kycFormId) {
        String callback = textValue(payload, "esign_callback_url");
        if (!StringUtils.hasText(callback) && form != null) {
            callback = form.getEsignCallbackUrl();
        }
        if (StringUtils.hasText(callback)) {
            return callback + (callback.contains("?") ? "&" : "?") + "sandbox=esign";
        }
        return FINPRIM_SANDBOX_BASE + "/v2/esigns/" + kycFormId + "/redirect";
    }

    private static ObjectNode mockKycFormBase(String id, String status) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "kyc_form");
        response.put("id", id);
        response.put("type", "modify");
        response.put("status", status);
        response.putNull("reason");
        return response;
    }

    private static String textValue(Map<String, Object> payload, String key) {
        if (payload == null) {
            return null;
        }
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private InvestorKycForm resolveLatestForm(UUID investorId) {
        return repository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(investorId, ACTIVE_STATES)
                .or(() -> repository.findFirstByInvestorIdOrderByCreatedAtDesc(investorId))
                .orElse(null);
    }

    private InvestorKycFormResponse toResponse(InvestorKycForm form) {
        return new InvestorKycFormResponse(form, parseJson(form == null ? null : form.getExternalResponseJson()));
    }
}
