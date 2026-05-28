package com.platizio.wealthtech.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.platizio.wealthtech.dto.InvestorKycRequestUpdateRequest;
import com.platizio.wealthtech.dto.InvestorPreVerificationRequest;
import com.platizio.wealthtech.dto.InvestorPreVerificationResponse;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.auth.CybrillaPreVerificationProperties;
import com.platizio.wealthtech.repository.InvestorRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class InvestorKycService {

    private static final Pattern CYBRILLA_SANDBOX_PAN_PATTERN = Pattern.compile("^[A-Z]{3}P[IAX][0-9]{4}[A-Z]$");
    private static final String CYBRILLA_SANDBOX_PAN_MESSAGE = "Cybrilla sandbox only accepts simulator PANs: "
            + "use AAAPX1234A for verified KYC, AAAPA1234A for Aadhaar-not-linked, or AAAPI1234A for invalid PAN.";

    private final InvestorRepository investorRepository;
    private final DistributorService distributorService;
    private final AuditService auditService;
    private final CybrillaClient cybrillaClient;
    private final CybrillaPreVerificationProperties poaProperties;

    public InvestorKycService(
            InvestorRepository investorRepository,
            DistributorService distributorService,
            AuditService auditService,
            CybrillaClient cybrillaClient,
            CybrillaPreVerificationProperties poaProperties
    ) {
        this.investorRepository = investorRepository;
        this.distributorService = distributorService;
        this.auditService = auditService;
        this.cybrillaClient = cybrillaClient;
        this.poaProperties = poaProperties;
    }

    @Transactional
    public InvestorPreVerificationResponse createPreVerification(InvestorPreVerificationRequest request, UUID actorId) {
        JsonNode response = cybrillaClient.createPreVerification(preVerificationPayload(request));
        return new InvestorPreVerificationResponse(response);
    }

    @Transactional(readOnly = true)
    public InvestorPreVerificationResponse fetchPreVerification(String preVerificationId, UUID actorId) {
        JsonNode response = cybrillaClient.fetchKycCheck(resolveExternalId(preVerificationId, null, "Pre-verification id"));
        return new InvestorPreVerificationResponse(response);
    }

    @Transactional
    public InvestorExternalKycResponse createKycCheck(UUID investorId, InvestorKycCheckRequest request, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        if (request != null && request.dateOfBirth() != null) {
            investor.setDateOfBirth(request.dateOfBirth());
        }
        validateCybrillaSandboxPan(normalizePan(investor.getPan()));
        JsonNode response = cybrillaClient.createKycCheck(investor);
        applyKycCheckResponse(investor, response);
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "KYC_CHECK_CREATED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional
    public InvestorExternalKycResponse fetchKycCheck(UUID investorId, String kycCheckId, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        JsonNode response = cybrillaClient.fetchKycCheck(resolveExternalId(kycCheckId, investor.getExternalKycCheckId(), "KYC check id"));
        applyKycCheckResponse(investor, response);
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "KYC_CHECK_FETCHED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional
    public InvestorExternalKycResponse refetchKycCheck(UUID investorId, String kycCheckId, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        JsonNode response = cybrillaClient.refetchKycCheck(resolveExternalId(kycCheckId, investor.getExternalKycCheckId(), "KYC check id"));
        applyKycCheckResponse(investor, response);
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "KYC_CHECK_REFETCHED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional(readOnly = true)
    public JsonNode listKycRequests(UUID investorId, String status, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        return cybrillaClient.listKycRequests(investor.getPan(), status);
    }

    @Transactional
    public InvestorExternalKycResponse createKycRequest(UUID investorId, InvestorKycRequestCreateRequest request, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        JsonNode response = cybrillaClient.createKycRequest(kycRequestPayload(investor, request));
        applyKycRequestResponse(investor, response);
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "KYC_REQUEST_CREATED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional
    public InvestorExternalKycResponse fetchKycRequest(UUID investorId, String kycRequestId, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        JsonNode response = cybrillaClient.fetchKycRequest(resolveExternalId(kycRequestId, investor.getExternalKycRequestId(), "KYC request id"));
        applyKycRequestResponse(investor, response);
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "KYC_REQUEST_FETCHED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional
    public InvestorExternalKycResponse updateKycRequest(UUID investorId, String kycRequestId, InvestorKycRequestUpdateRequest request, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        JsonNode response = cybrillaClient.updateKycRequest(
                resolveExternalId(kycRequestId, investor.getExternalKycRequestId(), "KYC request id"),
                updatePayload(request)
        );
        applyKycRequestResponse(investor, response);
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "KYC_REQUEST_UPDATED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional
    public InvestorExternalKycResponse simulateKycRequest(UUID investorId, String kycRequestId, String status, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        JsonNode response = cybrillaClient.simulateKycRequest(
                resolveExternalId(kycRequestId, investor.getExternalKycRequestId(), "KYC request id"),
                status
        );
        applyKycRequestResponse(investor, response);
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "KYC_REQUEST_SIMULATED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional
    public InvestorExternalKycResponse createIdentityDocument(UUID investorId, IdentityDocumentCreateRequest request, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        JsonNode response = cybrillaClient.createIdentityDocument(identityDocumentPayload(investor, request));
        investor.setExternalKycPayloadJson(response.toString());
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "IDENTITY_DOCUMENT_CREATED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional(readOnly = true)
    public JsonNode fetchIdentityDocument(UUID investorId, String identityDocumentId, UUID actorId) {
        getAuthorizedInvestor(investorId, actorId);
        return cybrillaClient.fetchIdentityDocument(identityDocumentId);
    }

    @Transactional(readOnly = true)
    public JsonNode listIdentityDocuments(UUID investorId, String kycRequestId, String fetchStatus, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        String resolvedKycRequestId = StringUtils.hasText(kycRequestId) ? kycRequestId : investor.getExternalKycRequestId();
        return cybrillaClient.listIdentityDocuments(resolvedKycRequestId, fetchStatus);
    }

    private Investor getAuthorizedInvestor(UUID investorId, UUID actorId) {
        Investor investor = investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
        Distributor actor = distributorService.getDistributor(actorId);
        if (actor.getRole() == DistributorRole.ADMIN || actorId.equals(investor.getDistributorId())) {
            return investor;
        }
        if (actor.getRole() == DistributorRole.MASTER_DISTRIBUTOR) {
            Distributor owner = distributorService.getDistributor(investor.getDistributorId());
            if (actorId.equals(owner.getMasterDistributorId())) {
                return investor;
            }
        }
        throw new AccessDeniedException("Cannot manage KYC for another distributor's investor");
    }

    private Map<String, Object> kycRequestPayload(Investor investor, InvestorKycRequestCreateRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "name", defaultText(request == null ? null : request.name(), investor.getFullName()));
        put(payload, "pan", defaultText(request == null ? null : request.pan(), investor.getPan()));
        put(payload, "email", defaultText(request == null ? null : request.email(), investor.getEmail()));
        putMobile(payload, defaultText(request == null ? null : request.mobile(), investor.getMobileNumber()));
        LocalDate dateOfBirth = request != null && request.dateOfBirth() != null ? request.dateOfBirth() : investor.getDateOfBirth();
        if (dateOfBirth != null) {
            put(payload, "date_of_birth", dateOfBirth.toString());
        }
        appendFields(payload, request == null ? null : request.fields());
        return payload;
    }

    private Map<String, Object> updatePayload(InvestorKycRequestUpdateRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        appendFields(payload, request == null ? null : request.fields());
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("KYC request update requires at least one field");
        }
        return payload;
    }

    private Map<String, Object> identityDocumentPayload(Investor investor, IdentityDocumentCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Identity document request body is required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        String kycRequestId = defaultText(request.kycRequestId(), investor.getExternalKycRequestId());
        if (!StringUtils.hasText(kycRequestId)) {
            throw new IllegalArgumentException("kycRequestId is required until the investor has an external KYC request id");
        }
        put(payload, "kyc_request", kycRequestId);
        put(payload, "type", defaultText(request.type(), "aadhaar"));
        put(payload, "postback_url", request.postbackUrl());
        if (!payload.containsKey("postback_url")) {
            throw new IllegalArgumentException("postbackUrl is required");
        }
        appendFields(payload, request.fields());
        return payload;
    }

    private Map<String, Object> preVerificationPayload(InvestorPreVerificationRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String pan = normalizePan(request.pan());
        validateCybrillaSandboxPan(pan);
        put(payload, "investor_identifier", pan);
        putPoaValue(payload, "pan", pan);
        putPoaValue(payload, "name", request.fullName());
        if (request.dateOfBirth() != null) {
            putPoaValue(payload, "date_of_birth", request.dateOfBirth().toString());
        }
        return payload;
    }

    private String normalizePan(String rawPan) {
        return rawPan == null ? null : rawPan.trim().toUpperCase(Locale.ROOT);
    }

    private void validateCybrillaSandboxPan(String pan) {
        if (!isCybrillaSandbox() || !StringUtils.hasText(pan)) {
            return;
        }
        if (!CYBRILLA_SANDBOX_PAN_PATTERN.matcher(pan).matches()) {
            throw new IllegalArgumentException(CYBRILLA_SANDBOX_PAN_MESSAGE);
        }
    }

    private boolean isCybrillaSandbox() {
        return poaProperties != null
                && StringUtils.hasText(poaProperties.getBaseUrl())
                && poaProperties.getBaseUrl().toLowerCase(Locale.ROOT).contains("sandbox");
    }

    private void applyKycCheckResponse(Investor investor, JsonNode response) {
        String responseId = firstText(response, "id");
        if (StringUtils.hasText(responseId)) {
            investor.setExternalKycCheckId(responseId);
        }
        investor.setExternalKycStatus(kycStatusText(response));
        investor.setExternalKycPayloadJson(response == null ? null : response.toString());
        investor.setKycStatus(resolveKycCheckStatus(response));
        markReadyIfEligible(investor);
    }

    private void applyKycRequestResponse(Investor investor, JsonNode response) {
        String responseId = firstText(response, "id");
        if (StringUtils.hasText(responseId)) {
            investor.setExternalKycRequestId(responseId);
        }
        investor.setExternalKycStatus(kycStatusText(response));
        investor.setExternalKycPayloadJson(response == null ? null : response.toString());
        investor.setKycStatus(resolveKycRequestStatus(response));
        markReadyIfEligible(investor);
    }

    private KycStatus resolveKycCheckStatus(JsonNode response) {
        if (response != null && response.path("status").isBoolean()) {
            return response.path("status").asBoolean() ? KycStatus.COMPLETED : KycStatus.RETRY_REQUIRED;
        }
        if (isPreVerification(response)) {
            return resolvePreVerificationStatus(response);
        }
        return mapExternalStatus(kycStatusText(response), KycStatus.PENDING);
    }

    private KycStatus resolveKycRequestStatus(JsonNode response) {
        return mapExternalStatus(kycStatusText(response), KycStatus.IN_PROGRESS);
    }

    private KycStatus mapExternalStatus(String status, KycStatus defaultStatus) {
        if (!StringUtils.hasText(status)) {
            return defaultStatus;
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "true", "verified", "completed", "complete" -> KycStatus.COMPLETED;
            case "successful" -> KycStatus.IN_PROGRESS;
            case "failed", "failure", "rejected", "invalid" -> KycStatus.FAILED;
            case "expired" -> KycStatus.RETRY_REQUIRED;
            case "pending", "submitted", "esign_required", "in_progress", "processing" -> KycStatus.IN_PROGRESS;
            default -> defaultStatus;
        };
    }

    private String kycStatusText(JsonNode response) {
        if (response == null || response.isNull()) {
            return null;
        }
        JsonNode status = response.path("status");
        if (!status.isMissingNode() && !status.isNull()) {
            return status.asText();
        }
        JsonNode fetchStatus = response.path("fetch").path("status");
        return fetchStatus.isMissingNode() || fetchStatus.isNull() ? null : fetchStatus.asText();
    }

    private boolean isPreVerification(JsonNode response) {
        return response != null && "pre_verification".equals(firstText(response, "object"));
    }

    private KycStatus resolvePreVerificationStatus(JsonNode response) {
        String status = firstText(response, "status");
        if (!"completed".equalsIgnoreCase(status)) {
            return mapExternalStatus(status, KycStatus.IN_PROGRESS);
        }

        String readinessStatus = nestedText(response, "readiness", "status");
        String readinessCode = nestedText(response, "readiness", "code");
        if ("verified".equalsIgnoreCase(readinessStatus)) {
            return KycStatus.COMPLETED;
        }
        if ("failed".equalsIgnoreCase(readinessStatus)) {
            if ("kyc_unavailable".equalsIgnoreCase(readinessCode) || "unavailable".equalsIgnoreCase(readinessCode)) {
                return KycStatus.NOT_STARTED;
            }
            if ("upstream_error".equalsIgnoreCase(readinessCode)
                    || "kyc_incomplete".equalsIgnoreCase(readinessCode)
                    || "unknown".equalsIgnoreCase(readinessCode)) {
                return KycStatus.RETRY_REQUIRED;
            }
            return KycStatus.FAILED;
        }

        if (hasFailedPreVerificationHash(response, "pan")
                || hasFailedPreVerificationHash(response, "name")
                || hasFailedPreVerificationHash(response, "date_of_birth")) {
            return KycStatus.FAILED;
        }
        if (hasVerifiedPreVerificationHash(response, "pan")
                && hasVerifiedPreVerificationHash(response, "name")
                && hasVerifiedPreVerificationHash(response, "date_of_birth")) {
            return KycStatus.COMPLETED;
        }
        return KycStatus.IN_PROGRESS;
    }

    private boolean hasFailedPreVerificationHash(JsonNode response, String fieldName) {
        return "failed".equalsIgnoreCase(nestedText(response, fieldName, "status"));
    }

    private boolean hasVerifiedPreVerificationHash(JsonNode response, String fieldName) {
        return "verified".equalsIgnoreCase(nestedText(response, fieldName, "status"));
    }

    private String nestedText(JsonNode node, String objectName, String fieldName) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(objectName).path(fieldName);
        return value.isMissingNode() || value.isNull() || !StringUtils.hasText(value.asText())
                ? null
                : value.asText().trim();
    }

    private void markReadyIfEligible(Investor investor) {
        if (investor.getKycStatus() == KycStatus.COMPLETED
                && investor.getBankVerificationStatus() == BankVerificationStatus.VERIFIED) {
            investor.setInvestorStatus(InvestorStatus.READY_FOR_TRANSACTIONS);
        }
    }

    private void appendFields(Map<String, Object> payload, Map<String, Object> fields) {
        if (fields == null) {
            return;
        }
        fields.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                if ("mobile".equals(key.trim()) && value instanceof String mobile) {
                    putMobile(payload, mobile);
                    return;
                }
                payload.put(key, value);
            }
        });
    }

    private void put(Map<String, Object> payload, String key, String value) {
        if (StringUtils.hasText(value)) {
            payload.put(key, value.trim());
        }
    }

    private void putPoaValue(Map<String, Object> payload, String key, String value) {
        if (StringUtils.hasText(value)) {
            payload.put(key, Map.of("value", value.trim()));
        }
    }

    private void putMobile(Map<String, Object> payload, String rawMobile) {
        MobileParts mobile = MobileParts.from(rawMobile);
        if (mobile != null) {
            payload.put("mobile", Map.of("isd", mobile.isd(), "number", mobile.number()));
        }
    }

    private String firstText(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() || !StringUtils.hasText(value.asText())
                ? null
                : value.asText().trim();
    }

    private String defaultText(String requestedValue, String fallbackValue) {
        return StringUtils.hasText(requestedValue) ? requestedValue : fallbackValue;
    }

    private String resolveExternalId(String requestedId, String savedId, String label) {
        String externalId = StringUtils.hasText(requestedId) ? requestedId : savedId;
        if (!StringUtils.hasText(externalId)) {
            throw new IllegalArgumentException(label + " is required");
        }
        return externalId.trim();
    }

    private String auditDetails(Investor investor) {
        return "{\"externalKycCheckId\":\"" + safe(investor.getExternalKycCheckId())
                + "\",\"externalKycRequestId\":\"" + safe(investor.getExternalKycRequestId())
                + "\",\"externalKycStatus\":\"" + safe(investor.getExternalKycStatus())
                + "\",\"kycStatus\":\"" + investor.getKycStatus() + "\"}";
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }

    private record MobileParts(String isd, String number) {
        private static MobileParts from(String rawMobile) {
            if (!StringUtils.hasText(rawMobile)) {
                return null;
            }
            String digits = rawMobile.replaceAll("[^0-9]", "");
            if (!StringUtils.hasText(digits)) {
                return null;
            }
            if (digits.startsWith("91") && digits.length() > 10) {
                digits = digits.substring(2);
            }
            return new MobileParts("+91", digits);
        }
    }
}
