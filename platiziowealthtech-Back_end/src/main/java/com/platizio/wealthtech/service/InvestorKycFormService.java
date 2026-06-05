package com.platizio.wealthtech.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorKycForm;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.dto.ExternalKycSyncResponse;
import com.platizio.wealthtech.dto.InvestorKycFormResponse;
import com.platizio.wealthtech.dto.KycFormUpdateRequest;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.InvestorKycFormRepository;
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
    private final String callbackBaseUrl;

    public InvestorKycFormService(
            InvestorService investorService,
            InvestorKycFormRepository repository,
            CybrillaClient cybrillaClient,
            AuditService auditService,
            @Value("${cybrilla.kyc-form.callback-base-url:http://localhost:3000}") String callbackBaseUrl
    ) {
        this.investorService = investorService;
        this.repository = repository;
        this.cybrillaClient = cybrillaClient;
        this.auditService = auditService;
        this.callbackBaseUrl = trimTrailingSlash(callbackBaseUrl);
    }

    @Transactional(readOnly = true)
    public InvestorKycFormResponse getLatestForm(UUID investorId, UUID actorId) {
        investorService.getInvestor(investorId, actorId);
        InvestorKycForm form = repository.findFirstByInvestorIdOrderByCreatedAtDesc(investorId).orElse(null);
        return new InvestorKycFormResponse(form, parseJson(form == null ? null : form.getExternalResponseJson()));
    }

    @Transactional
    public InvestorKycFormResponse createModifyForm(UUID investorId, UUID actorId) {
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
        repository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(investorId, ACTIVE_STATES)
                .ifPresent(existing -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "An on-going KYC modify form already exists for this investor (status="
                                    + existing.getStatus() + "). Complete or wait for it to expire before starting a new one.");
                });

        String proofCallback = buildCallbackUrl(investorId, "proof-callback");
        String esignCallback = buildCallbackUrl(investorId, "esign-callback");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "modify");
        payload.put("pan", investor.getPan());
        payload.put("name", investor.getFullName());
        payload.put("date_of_birth", investor.getDateOfBirth().toString());
        payload.put("proof_details_callback_url", proofCallback);
        payload.put("esign_callback_url", esignCallback);

        JsonNode response = cybrillaClient.createKycForm(payload);

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

        JsonNode response = cybrillaClient.updateKycForm(form.getExternalKycFormId(), payload);
        applyExternalResponse(form, response);

        InvestorKycForm saved = repository.save(form);
        auditService.log("INVESTOR", investorId, "KYC_FORM_UPDATED", actorId, auditDetails(saved));
        return new InvestorKycFormResponse(saved, response);
    }

    @Transactional
    public InvestorKycFormResponse refreshForm(UUID investorId, String kycFormId, UUID actorId) {
        investorService.getInvestor(investorId, actorId);
        InvestorKycForm form = loadForm(investorId, kycFormId);
        JsonNode response = cybrillaClient.fetchKycForm(form.getExternalKycFormId());
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

        JsonNode response = cybrillaClient.uploadKycFormSignature(form.getExternalKycFormId(), bytes, filename, contentType);
        applyExternalResponse(form, response);
        InvestorKycForm saved = repository.save(form);
        auditService.log("INVESTOR", investorId, "KYC_FORM_SIGNATURE_UPLOADED", actorId, auditDetails(saved));
        return new InvestorKycFormResponse(saved, response);
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
        JsonNode response = cybrillaClient.retryKycFormProofDetailsFetch(form.getExternalKycFormId());
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
            canonical = cybrillaClient.fetchKycForm(externalId.trim());
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
                JsonNode response = cybrillaClient.fetchKycForm(form.getExternalKycFormId());
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

    private String buildCallbackUrl(UUID investorId, String suffix) {
        return callbackBaseUrl + "/distributor/investors/" + investorId + "/kyc-modify/" + suffix;
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
}
