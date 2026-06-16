package com.platizio.wealthtech.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorStatus;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.dto.AadhaarVerificationResponse;
import com.platizio.wealthtech.dto.EsignStartRequest;
import com.platizio.wealthtech.dto.EsignVerificationResponse;
import com.platizio.wealthtech.dto.ExternalKycSyncResponse;
import com.platizio.wealthtech.dto.IdentityDocumentCreateRequest;
import com.platizio.wealthtech.dto.InvestorExternalKycResponse;
import com.platizio.wealthtech.dto.InvestorKycCheckRequest;
import com.platizio.wealthtech.dto.InvestorKycRequestCreateRequest;
import com.platizio.wealthtech.dto.InvestorKycRequestUpdateRequest;
import com.platizio.wealthtech.dto.InvestorPreVerificationRequest;
import com.platizio.wealthtech.dto.InvestorPreVerificationResponse;
import com.platizio.wealthtech.dto.KycFlowAdvanceResponse;
import com.platizio.wealthtech.dto.KycFlowStatusResponse;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.CybrillaIntegrationEnvironment;
import com.platizio.wealthtech.integration.CybrillaUnavailableException;
import com.platizio.wealthtech.repository.InvestorRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.platizio.wealthtech.validation.PanFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;

@Service
public class InvestorKycService {

    private static final Logger logger = LoggerFactory.getLogger(InvestorKycService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<KycStatus> AUTO_SYNC_STATUSES = List.of(
            KycStatus.PENDING,
            KycStatus.IN_PROGRESS
    );

    private final InvestorRepository investorRepository;
    private final DistributorService distributorService;
    private final AuditService auditService;
    private final CybrillaClient cybrillaClient;
    private final CybrillaIntegrationEnvironment integrationEnvironment;
    private final BankVerificationStarter bankVerificationStarter;
    private final long scheduledSyncFailureBackoffMs;
    private final int preVerificationPollMaxAttempts;
    private final long preVerificationPollIntervalMs;
    private final String kycCallbackBaseUrl;
    private final String identityDocumentPostbackPath;
    private final String esignPostbackPath;
    private volatile long scheduledSyncBackoffUntilEpochMillis;
    // Commits the deferred/pending state in its OWN transaction so it survives
    // the rollback of the surrounding @Transactional KYC method when we rethrow.
    private final TransactionTemplate deferredPersistTx;

    public InvestorKycService(
            InvestorRepository investorRepository,
            DistributorService distributorService,
            AuditService auditService,
            CybrillaClient cybrillaClient,
            CybrillaIntegrationEnvironment integrationEnvironment,
            BankVerificationStarter bankVerificationStarter,
            PlatformTransactionManager transactionManager,
            @Value("${app.kyc-sync.failure-backoff-ms:900000}") long scheduledSyncFailureBackoffMs,
            @Value("${app.kyc-sync.pre-verification-poll-max-attempts:15}") int preVerificationPollMaxAttempts,
            @Value("${app.kyc-sync.pre-verification-poll-interval-ms:2000}") long preVerificationPollIntervalMs,
            @Value("${cybrilla.kyc-form.callback-base-url:http://localhost:3000}") String kycCallbackBaseUrl,
            @Value("${cybrilla.kyc.identity-document.postback-path:/distributor/investor-onboarding}") String identityDocumentPostbackPath,
            @Value("${cybrilla.kyc.esign.postback-path:/distributor/investor-onboarding}") String esignPostbackPath
    ) {
        this.investorRepository = investorRepository;
        this.distributorService = distributorService;
        this.auditService = auditService;
        this.cybrillaClient = cybrillaClient;
        this.integrationEnvironment = integrationEnvironment;
        this.bankVerificationStarter = bankVerificationStarter;
        this.scheduledSyncFailureBackoffMs = Math.max(0, scheduledSyncFailureBackoffMs);
        this.preVerificationPollMaxAttempts = Math.max(1, preVerificationPollMaxAttempts);
        this.preVerificationPollIntervalMs = Math.max(500L, preVerificationPollIntervalMs);
        this.kycCallbackBaseUrl = defaultText(kycCallbackBaseUrl, "http://localhost:3000");
        this.identityDocumentPostbackPath = defaultText(identityDocumentPostbackPath, "/distributor/investor-onboarding");
        this.esignPostbackPath = defaultText(esignPostbackPath, "/distributor/investor-onboarding");
        this.deferredPersistTx = new TransactionTemplate(transactionManager);
        this.deferredPersistTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public InvestorPreVerificationResponse createPreVerification(InvestorPreVerificationRequest request, UUID actorId) {
        validateSandboxPanBeforePoaCall(request.pan());
        JsonNode response = awaitPreVerificationCompletion(
                cybrillaClient.createPreVerification(preVerificationPayload(request))
        );
        return new InvestorPreVerificationResponse(response);
    }

    @Transactional(readOnly = true)
    public InvestorPreVerificationResponse fetchPreVerification(String preVerificationId, UUID actorId) {
        JsonNode response = awaitPreVerificationCompletion(
                cybrillaClient.fetchKycCheck(resolveExternalId(preVerificationId, null, "Pre-verification id"))
        );
        return new InvestorPreVerificationResponse(response);
    }

    /**
     * Clears saved POA/KYC attempt metadata after the investor's identity fields
     * (PAN, name, DOB, etc.) change so the next check runs against the new data.
     */
    @Transactional
    public void resetKycStateForIdentityChange(Investor investor) {
        InvestorKycStateReset.resetForIdentityChange(investor);
    }

    @Transactional
    public InvestorExternalKycResponse createKycCheck(UUID investorId, InvestorKycCheckRequest request, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        validateProductionKycPrerequisites(investor);
        validateSandboxPanBeforePoaCall(investor);
        if (request != null && request.dateOfBirth() != null) {
            investor.setDateOfBirth(request.dateOfBirth());
        }
        boolean forceNewCheck = request != null && Boolean.TRUE.equals(request.forceNewCheck());
        if (forceNewCheck) {
            resetKycAttemptState(investor);
        }

        // Make the endpoint idempotent for repeated UI clicks: if we already have
        // a saved pre-verification id, refresh that status instead of creating a
        // brand-new POA check every time.
        if (!forceNewCheck && StringUtils.hasText(investor.getExternalKycCheckId())) {
            try {
                JsonNode existingResponse = awaitPreVerificationCompletion(
                        cybrillaClient.fetchKycCheck(investor.getExternalKycCheckId())
                );
                if (!isPreVerification(existingResponse)) {
                    logger.info(
                            "external_kyc status='stale_kyc_check_reference' investor_id='{}' external_kyc_check_id='{}' object_type='{}' action='create_new_check'",
                            investor.getId(),
                            investor.getExternalKycCheckId(),
                            firstText(existingResponse, "object")
                    );
                    clearStaleKycCheckReference(investor);
                } else {
                    applyKycCheckResponse(investor, existingResponse);
                    Investor saved = saveAndStartBankVerificationIfKycComplete(investor, actorId, "kyc_check_reused");
                    auditService.log("INVESTOR", saved.getId(), "KYC_CHECK_REUSED", actorId, auditDetails(saved));
                    return new InvestorExternalKycResponse(saved, existingResponse);
                }
            } catch (CybrillaApiException ex) {
                if (!isNotFound(ex)) {
                    throw ex;
                }
                logger.info(
                        "external_kyc status='stale_kyc_check_reference' investor_id='{}' external_kyc_check_id='{}' action='create_new_check'",
                        investor.getId(),
                        investor.getExternalKycCheckId()
                );
                clearStaleKycCheckReference(investor);
            }
        }

        JsonNode response = awaitPreVerificationCompletion(cybrillaClient.createKycCheck(investor));
        applyKycCheckResponse(investor, response);
        Investor saved = saveAndStartBankVerificationIfKycComplete(investor, actorId, "kyc_check_created");
        auditService.log("INVESTOR", saved.getId(), "KYC_CHECK_CREATED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    /**
     * Runs POA combined pre-verification (readiness + PAN) before FP ONDC purchase review.
     * Demo investors seeded with local {@code kyc_readiness_status=verified} but no
     * {@code external_kyc_check_id} still fail review with {@code investor_data_submission_error}.
     */
    @Transactional
    public void ensurePoaReadinessForOrderPlacement(UUID investorId, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        validateSandboxPanBeforePoaCall(investor);
        ensureFpKycComplianceForOrderPlacement(investor);
        JsonNode response = resolvePoaReadinessForOrder(investor);
        applyKycCheckResponse(investor, response);
        investorRepository.save(investor);
        if (!isPoaReadinessVerifiedForOrder(response)) {
            String readinessReason = nestedText(response, "readiness", "reason");
            String panReason = nestedText(response, "pan", "reason");
            String detail = StringUtils.hasText(readinessReason) ? readinessReason : panReason;
            throw new IllegalStateException(
                    "POA investor readiness must be verified before placing ONDC orders"
                            + (StringUtils.hasText(detail) ? ": " + detail : ""));
        }
        logger.info(
                "poa_order_readiness status='verified' investor_id='{}' external_kyc_check_id='{}' external_kyc_compliance_id='{}'",
                investor.getId(),
                investor.getExternalKycCheckId(),
                investor.getExternalKycComplianceId()
        );
    }

    private void ensureFpKycComplianceForOrderPlacement(Investor investor) {
        if (Boolean.TRUE.equals(investor.getKycComplianceStatus())
                && StringUtils.hasText(investor.getExternalKycComplianceId())) {
            try {
                JsonNode existing = cybrillaClient.fetchKycComplianceCheck(investor.getExternalKycComplianceId());
                applyKycComplianceResponse(investor, existing);
                if (Boolean.TRUE.equals(investor.getKycComplianceStatus())) {
                    return;
                }
            } catch (CybrillaApiException ex) {
                if (!isNotFound(ex)) {
                    throw ex;
                }
                investor.setExternalKycComplianceId(null);
            }
        }
        try {
            JsonNode response = cybrillaClient.createKycComplianceCheck(investor.getPan(), investor.getDateOfBirth());
            applyKycComplianceResponse(investor, response);
        } catch (CybrillaApiException ex) {
            if (isKycComplianceApiUnavailable(ex)) {
                logger.warn(
                        "fp_kyc_compliance_order_readiness status='skipped_unavailable' investor_id='{}' reason='{}'",
                        investor.getId(),
                        ex.getMessage());
                return;
            }
            throw ex;
        }
        if (!Boolean.TRUE.equals(investor.getKycComplianceStatus())) {
            throw new IllegalStateException(
                    "FP KYC compliance check must pass before placing ONDC orders"
                            + (StringUtils.hasText(investor.getKycComplianceReason())
                            ? ": " + investor.getKycComplianceReason()
                            : ""));
        }
        logger.info(
                "fp_kyc_compliance_order_readiness status='verified' investor_id='{}' external_kyc_compliance_id='{}'",
                investor.getId(),
                investor.getExternalKycComplianceId()
        );
    }

    private boolean isKycComplianceApiUnavailable(CybrillaApiException ex) {
        if (isNotFound(ex)) {
            return true;
        }
        String message = ex.getMessage();
        return StringUtils.hasText(message)
                && (message.contains("404 NOT_FOUND") || message.contains("404 Not Found"));
    }

    private JsonNode resolvePoaReadinessForOrder(Investor investor) {
        if (StringUtils.hasText(investor.getExternalKycCheckId())) {
            try {
                JsonNode existing = awaitPreVerificationCompletion(
                        cybrillaClient.fetchKycCheck(investor.getExternalKycCheckId())
                );
                if (isPreVerification(existing) && isPoaReadinessVerifiedForOrder(existing)) {
                    return existing;
                }
                if (isPreVerification(existing)) {
                    logger.info(
                            "poa_order_readiness status='refresh_stale_check' investor_id='{}' external_kyc_check_id='{}'",
                            investor.getId(),
                            investor.getExternalKycCheckId()
                    );
                } else {
                    clearStaleKycCheckReference(investor);
                }
            } catch (CybrillaApiException ex) {
                if (!isNotFound(ex)) {
                    throw ex;
                }
                clearStaleKycCheckReference(investor);
            }
        }
        return awaitPreVerificationCompletion(cybrillaClient.createKycCheck(investor));
    }

    private boolean isPoaReadinessVerifiedForOrder(JsonNode response) {
        if (!isPreVerification(response) || !"completed".equalsIgnoreCase(kycStatusText(response))) {
            return false;
        }
        return "verified".equalsIgnoreCase(nestedText(response, "readiness", "status"))
                && "verified".equalsIgnoreCase(nestedText(response, "pan", "status"));
    }

    /**
     * Runs the FP KYC Check (`POST /api/kyc/check`) — the authoritative KRA
     * "is this PAN already KYC compliant?" lookup. Returns granular
     * status/reason/action plus any investment constraints so the customer can
     * be shown the correct indicator and re-KYC is skipped when already done.
     * Pass {@code fetchData=true} to also pull demographic details (requires a
     * SEBI RIA/AMC licence; AMFI ARN holders only get status).
     */
    @Transactional
    public InvestorExternalKycResponse runKycComplianceCheck(UUID investorId, boolean fetchData, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        LocalDate dateOfBirth = fetchData ? investor.getDateOfBirth() : null;
        JsonNode response = cybrillaClient.createKycComplianceCheck(investor.getPan(), dateOfBirth);
        applyKycComplianceResponse(investor, response);
        Investor saved = saveAndStartBankVerificationIfKycComplete(investor, actorId, "kyc_compliance_checked");
        auditService.log("INVESTOR", saved.getId(), "KYC_COMPLIANCE_CHECKED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional
    public InvestorExternalKycResponse applyInvestorKyc(UUID investorId, InvestorKycCheckRequest request, UUID actorId) {
        boolean forceNewCheck = request != null && Boolean.TRUE.equals(request.forceNewCheck());
        return applyInvestorKyc(investorId, request, actorId, forceNewCheck);
    }

    @Transactional
    public InvestorExternalKycResponse reapplyInvestorKyc(UUID investorId, InvestorKycCheckRequest request, UUID actorId) {
        return applyInvestorKyc(investorId, request, actorId, true);
    }

    private InvestorExternalKycResponse applyInvestorKyc(
            UUID investorId,
            InvestorKycCheckRequest request,
            UUID actorId,
            boolean forceNewCheck
    ) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        if (request != null && request.dateOfBirth() != null) {
            investor.setDateOfBirth(request.dateOfBirth());
        }
        validateSandboxPanBeforePoaCall(investor);
        JsonNode response = null;
        if (forceNewCheck) {
            resetKycAttemptState(investor);
        } else if (hasSavedExternalKycReference(investor)) {
            response = fetchAndApplyExternalKycStatus(investor);
        }
        if (response == null) {
            JsonNode newCheck = forceNewCheck
                    ? cybrillaClient.createKycCheck(investor)
                    : cybrillaClient.createReadinessCheck(investor);
            response = awaitPreVerificationCompletion(newCheck);
            applyKycCheckResponse(investor, response);
        }
        if (shouldStartFreshKycRequest(investor)) {
            investor = ensureExternalInvestorProfileBeforeKyc(investor, actorId);
            response = cybrillaClient.createKycRequest(kycRequestPayload(investor, null));
            applyKycRequestResponse(investor, response);
        }

        Investor saved = saveAndStartBankVerificationIfKycComplete(investor, actorId, forceNewCheck ? "kyc_reapplied" : "kyc_applied");
        auditService.log("INVESTOR", saved.getId(), forceNewCheck ? "KYC_REAPPLIED" : "KYC_APPLIED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional
    public InvestorExternalKycResponse fetchKycCheck(UUID investorId, String kycCheckId, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        JsonNode response = awaitPreVerificationCompletion(
                cybrillaClient.fetchKycCheck(resolveExternalId(kycCheckId, investor.getExternalKycCheckId(), "KYC check id"))
        );
        applyKycCheckResponse(investor, response);
        Investor saved = saveAndStartBankVerificationIfKycComplete(investor, actorId, "kyc_check_fetched");
        auditService.log("INVESTOR", saved.getId(), "KYC_CHECK_FETCHED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional
    public InvestorExternalKycResponse refetchKycCheck(UUID investorId, String kycCheckId, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        JsonNode response = cybrillaClient.refetchKycCheck(resolveExternalId(kycCheckId, investor.getExternalKycCheckId(), "KYC check id"));
        applyKycCheckResponse(investor, response);
        Investor saved = saveAndStartBankVerificationIfKycComplete(investor, actorId, "kyc_check_refetched");
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
        validateProductionKycPrerequisites(investor);
        if (isAlreadyKycCompliant(investor)) {
            // Investor's PAN is already KYC compliant (e.g. KYC done earlier with
            // another distributor/AMC/KRA). Do not start a fresh KYC application or
            // call Cybrilla again — skipping the unnecessary re-KYC round trip.
            logger.info(
                    "external_kyc status='rekyc_skipped' reason='already_kyc_compliant' investor_id='{}' kyc_status='{}'",
                    investor.getId(),
                    investor.getKycStatus()
            );
            auditService.log("INVESTOR", investor.getId(), "KYC_REQUEST_SKIPPED_ALREADY_VERIFIED", actorId, auditDetails(investor));
            return new InvestorExternalKycResponse(investor, existingExternalKycPayload(investor));
        }
        investor = ensureExternalInvestorProfileBeforeKyc(investor, actorId);
        JsonNode response = cybrillaClient.createKycRequest(kycRequestPayload(investor, request));
        applyKycRequestResponse(investor, response);
        Investor saved = saveAndStartBankVerificationIfKycComplete(investor, actorId, "kyc_request_created");
        auditService.log("INVESTOR", saved.getId(), "KYC_REQUEST_CREATED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional
    public InvestorExternalKycResponse fetchKycRequest(UUID investorId, String kycRequestId, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        JsonNode response = cybrillaClient.fetchKycRequest(resolveExternalId(kycRequestId, investor.getExternalKycRequestId(), "KYC request id"));
        applyKycRequestResponse(investor, response);
        Investor saved = saveAndStartBankVerificationIfKycComplete(investor, actorId, "kyc_request_fetched");
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
        Investor saved = saveAndStartBankVerificationIfKycComplete(investor, actorId, "kyc_request_updated");
        auditService.log("INVESTOR", saved.getId(), "KYC_REQUEST_UPDATED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional
    public InvestorExternalKycResponse simulateKycRequest(UUID investorId, String kycRequestId, String status, UUID actorId) {
        if (!integrationEnvironment.isSandboxMode()) {
            throw new IllegalStateException("KYC request simulation is only available in Cybrilla sandbox mode");
        }
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        JsonNode response = cybrillaClient.simulateKycRequest(
                resolveExternalId(kycRequestId, investor.getExternalKycRequestId(), "KYC request id"),
                status
        );
        applyKycRequestResponse(investor, response);
        Investor saved = saveAndStartBankVerificationIfKycComplete(investor, actorId, "kyc_request_simulated");
        auditService.log("INVESTOR", saved.getId(), "KYC_REQUEST_SIMULATED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional
    public AadhaarVerificationResponse createIdentityDocument(UUID investorId, IdentityDocumentCreateRequest request, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        if (StringUtils.hasText(investor.getExternalIdentityDocumentId())) {
            JsonNode existing = cybrillaClient.fetchIdentityDocument(investor.getExternalIdentityDocumentId());
            applyIdentityDocumentResponse(investor, existing);
            Investor saved = investorRepository.save(investor);
            auditService.log("INVESTOR", saved.getId(), "IDENTITY_DOCUMENT_REUSED", actorId, auditDetails(saved));
            return AadhaarVerificationResponse.from(saved, existing, false);
        }

        String kycRequestId = resolveKycRequestIdForIdentityDocument(investor, request);
        JsonNode response;
        try {
            response = cybrillaClient.createIdentityDocument(identityDocumentPayload(investor, request));
        } catch (CybrillaApiException ex) {
            if (!isIdentityDocumentAlreadyExists(ex)) {
                throw ex;
            }
            response = resolveExistingIdentityDocumentForKycRequest(kycRequestId);
            if (response == null) {
                throw ex;
            }
        }
        applyIdentityDocumentResponse(investor, response);
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "IDENTITY_DOCUMENT_CREATED", actorId, auditDetails(saved));
        return AadhaarVerificationResponse.from(saved, response, false);
    }

    @Transactional(readOnly = true)
    public JsonNode fetchIdentityDocument(UUID investorId, String identityDocumentId, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        return cybrillaClient.fetchIdentityDocument(
                resolveExternalId(identityDocumentId, investor.getExternalIdentityDocumentId(), "identity document id")
        );
    }

    @Transactional
    public AadhaarVerificationResponse refreshIdentityDocumentForInvestor(UUID investorId, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        String resolvedId = resolveExternalId(
                null,
                investor.getExternalIdentityDocumentId(),
                "identity document id"
        );
        JsonNode response = cybrillaClient.fetchIdentityDocument(resolvedId);
        applyIdentityDocumentResponse(investor, response);
        boolean proofsAttached = false;
        if (isIdentityDocumentFetchComplete(response) && StringUtils.hasText(investor.getExternalKycRequestId())) {
            proofsAttached = attachAadhaarProofsToKycRequest(investor, resolvedId);
        }
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "IDENTITY_DOCUMENT_REFRESHED", actorId, auditDetails(saved));
        return AadhaarVerificationResponse.from(saved, response, proofsAttached);
    }

    @Transactional
    public EsignVerificationResponse createEsign(UUID investorId, EsignStartRequest request, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        if (!StringUtils.hasText(investor.getExternalKycRequestId())) {
            throw new IllegalArgumentException("KYC request id is required before starting eSign");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kyc_request", investor.getExternalKycRequestId());
        payload.put("postback_url", resolveEsignPostbackUrl(request));
        JsonNode response = cybrillaClient.createEsign(payload);
        applyEsignResponse(investor, response);
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "ESIGN_CREATED", actorId, auditDetails(saved));
        return EsignVerificationResponse.from(saved, response);
    }

    @Transactional
    public EsignVerificationResponse refreshEsign(UUID investorId, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        String esignId = resolveExternalId(null, investor.getExternalEsignId(), "eSign id");
        JsonNode response = cybrillaClient.fetchEsign(esignId);
        applyEsignResponse(investor, response);
        if (isEsignComplete(response)) {
            investor.setKycStatus(KycStatus.IN_PROGRESS);
        }
        Investor saved = investorRepository.save(investor);
        auditService.log("INVESTOR", saved.getId(), "ESIGN_REFRESHED", actorId, auditDetails(saved));
        return EsignVerificationResponse.from(saved, response);
    }

    @Transactional
    public KycFlowStatusResponse getKycFlowStatus(UUID investorId, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        refreshKycFlowStateFromCybrilla(investor, actorId);
        return buildKycFlowStatusResponse(investor);
    }

    /**
     * Executes the next Cybrilla KYC step suggested by {@link #getKycFlowStatus(UUID, UUID)}.
     * Maps the documented POA/FP sequence: pre-verification → KYC request → Aadhaar → eSign.
     */
    @Transactional
    public KycFlowAdvanceResponse advanceKycFlow(UUID investorId, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        refreshKycFlowStateFromCybrilla(investor, actorId);
        KycFlowStatusResponse status = buildKycFlowStatusResponse(investor);
        KycFlowStatusResponse.NextAction action = status.nextAction();
        if (action == null
                || action == KycFlowStatusResponse.NextAction.WAIT
                || action == KycFlowStatusResponse.NextAction.COMPLETE) {
            return new KycFlowAdvanceResponse(status, null, status.message());
        }

        Object stepResult = switch (action) {
            case RUN_PRE_VERIFICATION -> createKycCheck(investorId, new InvestorKycCheckRequest(null, null), actorId);
            case CREATE_KYC_REQUEST -> createKycRequest(investorId, null, actorId);
            case START_AADHAAR -> createIdentityDocument(
                    investorId,
                    new IdentityDocumentCreateRequest(null, "aadhaar", defaultIdentityDocumentPostbackUrl(), null),
                    actorId
            );
            case REFRESH_AADHAAR -> refreshIdentityDocumentForInvestor(investorId, actorId);
            case START_ESIGN -> createEsign(investorId, new EsignStartRequest(defaultEsignPostbackUrl()), actorId);
            case REFRESH_ESIGN -> refreshEsign(investorId, actorId);
            default -> null;
        };

        investor = getAuthorizedInvestor(investorId, actorId);
        refreshKycFlowStateFromCybrilla(investor, actorId);
        KycFlowStatusResponse refreshed = buildKycFlowStatusResponse(investor);
        return new KycFlowAdvanceResponse(refreshed, stepResult, "Executed " + action + ".");
    }

    private void refreshKycFlowStateFromCybrilla(Investor investor, UUID actorId) {
        fetchAndApplyExternalKycStatus(investor);
        refreshSupplementaryReadinessIfNeeded(investor);
        refreshIdentityDocumentStateIfNeeded(investor);
        refreshEsignStateIfNeeded(investor);
        Investor saved = investorRepository.save(investor);
        if (saved.getKycStatus() == KycStatus.COMPLETED) {
            bankVerificationStarter.startBankVerificationAfterKycCompletion(saved, actorId, "kyc_flow_refresh");
        }
    }

    private void refreshSupplementaryReadinessIfNeeded(Investor investor) {
        if (!StringUtils.hasText(investor.getExternalKycCheckId())
                || StringUtils.hasText(investor.getKycReadinessStatus())
                || !"verified".equalsIgnoreCase(investor.getPanVerificationStatus())) {
            return;
        }
        try {
            JsonNode readinessResponse = awaitPreVerificationCompletion(cybrillaClient.createReadinessCheck(investor));
            applyPreVerificationResultFields(investor, readinessResponse);
            if (investor.getKycStatus() == KycStatus.NOT_STARTED) {
                investor.setKycStatus(resolvePreVerificationStatus(readinessResponse));
            }
        } catch (CybrillaApiException ex) {
            logger.warn(
                    "kyc_flow_readiness_supplement status='failed' investor_id='{}' reason='{}'",
                    investor.getId(),
                    ex.getMessage()
            );
        }
    }

    private void refreshIdentityDocumentStateIfNeeded(Investor investor) {
        if (!StringUtils.hasText(investor.getExternalIdentityDocumentId())) {
            return;
        }
        try {
            JsonNode response = cybrillaClient.fetchIdentityDocument(investor.getExternalIdentityDocumentId());
            applyIdentityDocumentResponse(investor, response);
            if (isIdentityDocumentFetchComplete(response)
                    && StringUtils.hasText(investor.getExternalKycRequestId())
                    && !Boolean.TRUE.equals(investor.getAadhaarProofsAttached())) {
                attachAadhaarProofsToKycRequest(investor, investor.getExternalIdentityDocumentId());
            }
        } catch (CybrillaApiException ex) {
            if (!isNotFound(ex)) {
                throw ex;
            }
            investor.setExternalIdentityDocumentId(null);
            investor.setAadhaarFetchStatus(null);
            investor.setAadhaarFetchReason(null);
            investor.setAadhaarProofsAttached(Boolean.FALSE);
        }
    }

    private void refreshEsignStateIfNeeded(Investor investor) {
        if (!StringUtils.hasText(investor.getExternalEsignId())) {
            return;
        }
        try {
            JsonNode response = cybrillaClient.fetchEsign(investor.getExternalEsignId());
            applyEsignResponse(investor, response);
            if (isEsignComplete(response) && investor.getKycStatus() == KycStatus.NOT_STARTED) {
                investor.setKycStatus(KycStatus.IN_PROGRESS);
            }
        } catch (CybrillaApiException ex) {
            if (!isNotFound(ex)) {
                throw ex;
            }
            investor.setExternalEsignId(null);
            investor.setEsignStatus(null);
        }
    }

    @Transactional(readOnly = true)
    public JsonNode listIdentityDocuments(UUID investorId, String kycRequestId, String fetchStatus, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        String resolvedKycRequestId = StringUtils.hasText(kycRequestId) ? kycRequestId : investor.getExternalKycRequestId();
        return cybrillaClient.listIdentityDocuments(resolvedKycRequestId, fetchStatus);
    }

    @Transactional
    public InvestorExternalKycResponse syncInvestorExternalKycStatus(UUID investorId, UUID actorId) {
        Investor investor = getAuthorizedInvestor(investorId, actorId);
        JsonNode response = syncInvestorExternalKycStatusPayload(investor);
        if (response == null) {
            throw new IllegalArgumentException("Investor does not have a saved external KYC check or KYC request id to refresh");
        }
        Investor saved = saveAndStartBankVerificationIfKycComplete(investor, actorId, "external_kyc_manual_sync");
        auditService.log("INVESTOR", saved.getId(), "EXTERNAL_KYC_MANUAL_SYNCED", actorId, auditDetails(saved));
        return new InvestorExternalKycResponse(saved, response);
    }

    @Transactional
    public int syncOutstandingExternalKycStatuses(int limit) {
        long now = System.currentTimeMillis();
        long backoffUntil = scheduledSyncBackoffUntilEpochMillis;
        if (now < backoffUntil) {
            logger.debug(
                    "external_kyc_sync status='skipped' reason='provider_backoff' retry_in_ms='{}'",
                    backoffUntil - now
            );
            return 0;
        }

        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<Investor> candidates = investorRepository.findKycSyncCandidates(
                AUTO_SYNC_STATUSES,
                PageRequest.of(0, safeLimit)
        );
        int synced = 0;
        for (Investor investor : candidates) {
            try {
                if (syncInvestorExternalKycStatus(investor, "scheduled")) {
                    synced++;
                }
            } catch (CybrillaApiException ex) {
                if (isNotFound(ex)) {
                    logger.info(
                            "external_kyc_sync status='stale_kyc_check_reference' trigger='scheduled' investor_id='{}' reason='{}'",
                            investor.getId(),
                            ex.getMessage()
                    );
                    clearStaleKycCheckReference(investor);
                    investorRepository.save(investor);
                    continue;
                }
                logger.warn(
                        "external_kyc_sync status='failed' trigger='scheduled' investor_id='{}' reason='{}'",
                        investor.getId(),
                        ex.getMessage()
                );
                activateScheduledSyncBackoff(ex, now);
                break;
            } catch (RuntimeException ex) {
                logger.warn(
                        "external_kyc_sync status='failed' trigger='scheduled' investor_id='{}' reason='{}'",
                        investor.getId(),
                        ex.getMessage()
                );
            }
        }
        return synced;
    }

    private void activateScheduledSyncBackoff(CybrillaApiException ex, long failureEpochMillis) {
        if (scheduledSyncFailureBackoffMs <= 0) {
            return;
        }
        long backoffUntil = failureEpochMillis + scheduledSyncFailureBackoffMs;
        scheduledSyncBackoffUntilEpochMillis = Math.max(scheduledSyncBackoffUntilEpochMillis, backoffUntil);
        logger.warn(
                "external_kyc_sync status='backing_off' retry_after_ms='{}' reason='{}'",
                scheduledSyncFailureBackoffMs,
                ex.getMessage()
        );
    }

    @Transactional
    public ExternalKycSyncResponse handleExternalKycWebhook(JsonNode payload) {
        JsonNode externalObject = webhookDataObject(payload);
        String eventType = firstText(payload, "type");
        String objectType = firstText(externalObject, "object");
        String externalId = firstText(externalObject, "id");

        if (isPreVerificationReference(eventType, objectType, externalId)) {
            return syncKycCheckEvent(externalId, eventType);
        }
        if (isKycRequestReference(eventType, objectType, externalId)) {
            return syncKycRequestEvent(externalId, eventType);
        }
        if (isIdentityDocumentReference(eventType, objectType, externalId)) {
            return syncIdentityDocumentEvent(externalId, eventType);
        }
        if (isEsignReference(eventType, objectType, externalId)) {
            return syncEsignEvent(externalId, eventType);
        }
        return new ExternalKycSyncResponse("ignored_unsupported_event", eventType, externalId, null, null);
    }

    private boolean syncInvestorExternalKycStatus(Investor investor, String trigger) {
        JsonNode response = syncInvestorExternalKycStatusPayload(investor);
        if (response == null) {
            return false;
        }
        Investor saved = saveAndStartBankVerificationIfKycComplete(investor, savedDistributorActor(investor), trigger);
        auditService.log("INVESTOR", saved.getId(), "EXTERNAL_KYC_SYNCED", saved.getDistributorId(), auditDetails(saved));
        logger.info(
                "external_kyc_sync status='completed' trigger='{}' investor_id='{}' kyc_status='{}'",
                trigger,
                saved.getId(),
                saved.getKycStatus()
        );
        return true;
    }

    private UUID savedDistributorActor(Investor investor) {
        return investor == null ? null : investor.getDistributorId();
    }

    private Investor saveAndStartBankVerificationIfKycComplete(Investor investor, UUID actorId, String trigger) {
        Investor saved = investorRepository.save(investor);
        if (saved.getKycStatus() != KycStatus.COMPLETED) {
            return saved;
        }
        return bankVerificationStarter.startBankVerificationAfterKycCompletion(saved, actorId, trigger);
    }

    private JsonNode syncInvestorExternalKycStatusPayload(Investor investor) {
        return fetchAndApplyExternalKycStatus(investor);
    }

    /**
     * POA pre-verification is asynchronous. When Cybrilla returns {@code accepted},
     * poll until {@code completed} (or a terminal failure) so callers receive the
     * nested readiness/PAN/name/DOB results in one round trip.
     */
    private JsonNode awaitPreVerificationCompletion(JsonNode response) {
        if (!isPreVerification(response)) {
            return response;
        }
        String preVerificationId = firstText(response, "id");
        String status = kycStatusText(response);
        if (!StringUtils.hasText(preVerificationId)
                || !"accepted".equalsIgnoreCase(status)) {
            return response;
        }

        JsonNode latest = response;
        for (int attempt = 1; attempt <= preVerificationPollMaxAttempts; attempt++) {
            sleepPreVerificationPollInterval();
            latest = cybrillaClient.fetchKycCheck(preVerificationId);
            status = kycStatusText(latest);
            if ("completed".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
                logger.info(
                        "poa_pre_verification status='completed_poll' external_id='{}' attempts='{}' final_status='{}'",
                        preVerificationId,
                        attempt,
                        status
                );
                return latest;
            }
            if (!"accepted".equalsIgnoreCase(status)) {
                return latest;
            }
        }
        logger.info(
                "poa_pre_verification status='poll_timeout' external_id='{}' attempts='{}' last_status='{}'",
                preVerificationId,
                preVerificationPollMaxAttempts,
                status
        );
        return latest;
    }

    private void sleepPreVerificationPollInterval() {
        try {
            Thread.sleep(preVerificationPollIntervalMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CybrillaApiException("Interrupted while waiting for POA pre-verification to complete", ex);
        }
    }

    private JsonNode fetchAndApplyExternalKycStatus(Investor investor) {
        JsonNode lastResponse = null;
        if (StringUtils.hasText(investor.getExternalKycCheckId())) {
            try {
                JsonNode response = awaitPreVerificationCompletion(
                        cybrillaClient.fetchKycCheck(investor.getExternalKycCheckId())
                );
                applyKycCheckResponse(investor, response);
                lastResponse = response;
            } catch (CybrillaApiException ex) {
                if (!isNotFound(ex)) {
                    throw ex;
                }
                logger.info(
                        "external_kyc status='stale_kyc_check_reference' investor_id='{}' external_kyc_check_id='{}'",
                        investor.getId(),
                        investor.getExternalKycCheckId()
                );
                clearStaleKycCheckReference(investor);
            }
        }
        if (StringUtils.hasText(investor.getExternalKycRequestId())
                && (lastResponse == null || investor.getKycStatus() != KycStatus.COMPLETED)) {
            try {
                JsonNode response = cybrillaClient.fetchKycRequest(investor.getExternalKycRequestId());
                applyKycRequestResponse(investor, response);
                lastResponse = response;
            } catch (CybrillaApiException ex) {
                if (!isNotFound(ex)) {
                    throw ex;
                }
                logger.info(
                        "external_kyc status='stale_kyc_request_reference' investor_id='{}' external_kyc_request_id='{}'",
                        investor.getId(),
                        investor.getExternalKycRequestId()
                );
                investor.setExternalKycRequestId(null);
            }
        }
        refreshIdentityDocumentStateIfNeeded(investor);
        refreshEsignStateIfNeeded(investor);
        return lastResponse;
    }

    private boolean hasSavedExternalKycReference(Investor investor) {
        return StringUtils.hasText(investor.getExternalKycCheckId())
                || StringUtils.hasText(investor.getExternalKycRequestId());
    }

    private void clearStaleKycCheckReference(Investor investor) {
        investor.setExternalKycCheckId(null);
        investor.setExternalKycStatus(null);
        investor.setExternalKycPayloadJson(null);
    }

    private boolean isNotFound(CybrillaApiException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RestClientResponseException responseEx) {
            return HttpStatus.NOT_FOUND.equals(responseEx.getStatusCode());
        }
        String message = ex.getMessage();
        return message != null && message.contains("404");
    }

    private boolean isIdentityDocumentAlreadyExists(CybrillaApiException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return message.toLowerCase(Locale.ROOT).contains("identity document already exist");
    }

    private String resolveKycRequestIdForIdentityDocument(Investor investor, IdentityDocumentCreateRequest request) {
        if (request != null && StringUtils.hasText(request.kycRequestId())) {
            return request.kycRequestId().trim();
        }
        return investor.getExternalKycRequestId();
    }

    private JsonNode resolveExistingIdentityDocumentForKycRequest(String kycRequestId) {
        if (!StringUtils.hasText(kycRequestId)) {
            return null;
        }
        JsonNode listResponse = cybrillaClient.listIdentityDocuments(kycRequestId.trim(), null);
        JsonNode data = listResponse == null ? null : listResponse.path("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return null;
        }
        String identityDocumentId = firstText(data.get(0), "id");
        if (!StringUtils.hasText(identityDocumentId)) {
            return data.get(0);
        }
        return cybrillaClient.fetchIdentityDocument(identityDocumentId);
    }

    /**
     * True when the investor's PAN is already KYC compliant, so a fresh KYC
     * application (re-KYC) must not be started. This is the case when the POA
     * pre-verification readiness check returned {@code verified} (KYC done
     * elsewhere with another distributor/AMC/KRA) or when KYC is already
     * COMPLETED locally.
     */
    private boolean isAlreadyKycCompliant(Investor investor) {
        if (investor.getKycStatus() == KycStatus.COMPLETED) {
            return true;
        }
        if (Boolean.TRUE.equals(investor.getKycComplianceStatus())) {
            return true;
        }
        return "verified".equalsIgnoreCase(investor.getKycReadinessStatus());
    }

    private JsonNode existingExternalKycPayload(Investor investor) {
        String payload = investor.getExternalKycPayloadJson();
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(payload);
        } catch (JsonProcessingException ex) {
            logger.debug("external_kyc status='payload_parse_failed' investor_id='{}'", investor.getId());
            return null;
        }
    }

    private void resetKycAttemptState(Investor investor) {
        InvestorKycStateReset.resetAttemptState(investor);
    }

    private boolean shouldStartFreshKycRequest(Investor investor) {
        return !StringUtils.hasText(investor.getExternalKycRequestId())
                && investor.getKycStatus() == KycStatus.NOT_STARTED
                && "failed".equalsIgnoreCase(investor.getKycReadinessStatus())
                && ("kyc_unavailable".equalsIgnoreCase(investor.getKycReadinessCode())
                        || "unavailable".equalsIgnoreCase(investor.getKycReadinessCode()));
    }

    private Investor ensureExternalInvestorProfileBeforeKyc(Investor investor, UUID actorId) {
        validateProductionKycPrerequisites(investor);
        if (StringUtils.hasText(investor.getCybrillaInvestorId())) {
            try {
                cybrillaClient.updateInvestorProfile(investor);
                cybrillaClient.syncInvestorContactResources(investor);
            } catch (CybrillaApiException ex) {
                logger.warn(
                        "external_profile_sync_before_kyc status='partial' investor_id='{}' reason='{}'",
                        investor.getId(),
                        ex.getMessage()
                );
            }
            return investor;
        }

        try {
            String externalInvestorId = cybrillaClient.createInvestorProfile(investor);
            if (!StringUtils.hasText(externalInvestorId)) {
                throw new CybrillaApiException("Fintech Primitives investor profile response did not include id");
            }
            investor.setCybrillaInvestorId(externalInvestorId);
            investor.setExternalSyncPending(Boolean.FALSE);
            investor.setExternalSyncMessage(null);
            Investor saved = investorRepository.save(investor);
            auditService.log("INVESTOR", saved.getId(), "EXTERNAL_PROFILE_CREATED_BEFORE_KYC", actorId, auditDetails(saved));
            return saved;
        } catch (CybrillaUnavailableException ex) {
            // Provider unreachable (network/DNS). Degrade gracefully: persist the
            // pending state in a separate transaction (the surrounding @Transactional
            // method will roll back when we rethrow) and surface a clear 503.
            String message = "Cybrilla/Fintech Primitives is currently unreachable (network/DNS). "
                    + "Investor details were saved; please retry KYC once connectivity is restored.";
            markExternalProfileSyncDeferred(investor.getId(), message);
            logger.warn(
                    "external_profile_deferred_before_kyc investor_id='{}' reason='{}'",
                    investor.getId(), ex.getMessage()
            );
            throw new CybrillaUnavailableException(
                    "KYC deferred: investor profile could not be synced because the provider is unreachable. " + ex.getMessage(),
                    ex
            );
        } catch (CybrillaApiException ex) {
            investor.setExternalSyncPending(Boolean.TRUE);
            investor.setExternalSyncMessage("Investor details could not be posted to Cybrilla/Fintech Primitives. KYC was not started to avoid data mismatch.");
            Investor saved = investorRepository.save(investor);
            auditService.log("INVESTOR", saved.getId(), "EXTERNAL_PROFILE_PENDING_BEFORE_KYC", actorId, auditDetails(saved));
            throw new CybrillaApiException("Unable to sync investor profile before KYC: " + ex.getMessage(), ex);
        }
    }

    /**
     * Persists the deferred/pending flag in a brand-new transaction so it is
     * committed even though the caller's @Transactional method is about to roll
     * back (we rethrow to signal 503). Re-reads the row to avoid touching the
     * entity bound to the outer, rolling-back persistence context.
     */
    private void markExternalProfileSyncDeferred(UUID investorId, String message) {
        try {
            deferredPersistTx.executeWithoutResult(status ->
                    investorRepository.findById(investorId).ifPresent(fresh -> {
                        fresh.setExternalSyncPending(Boolean.TRUE);
                        fresh.setExternalSyncMessage(message);
                        investorRepository.save(fresh);
                    })
            );
        } catch (RuntimeException persistEx) {
            logger.warn(
                    "external_profile_defer_persist_failed investor_id='{}' reason='{}'",
                    investorId, persistEx.getMessage()
            );
        }
    }

    private ExternalKycSyncResponse syncKycCheckEvent(String externalId, String eventType) {
        if (!StringUtils.hasText(externalId)) {
            return new ExternalKycSyncResponse("ignored_missing_external_id", eventType, null, null, null);
        }
        Optional<Investor> investor = investorRepository.findByExternalKycCheckId(externalId.trim());
        if (investor.isEmpty()) {
            return new ExternalKycSyncResponse("ignored_no_matching_investor", eventType, externalId, null, null);
        }
        JsonNode response = awaitPreVerificationCompletion(cybrillaClient.fetchKycCheck(externalId.trim()));
        Investor saved = investor.get();
        applyKycCheckResponse(saved, response);
        saved = saveAndStartBankVerificationIfKycComplete(saved, saved.getDistributorId(), "external_kyc_webhook");
        auditService.log("INVESTOR", saved.getId(), "EXTERNAL_KYC_WEBHOOK_SYNCED", saved.getDistributorId(), auditDetails(saved));
        return new ExternalKycSyncResponse("synced", eventType, externalId, saved.getId(), saved.getKycStatus());
    }

    private ExternalKycSyncResponse syncIdentityDocumentEvent(String externalId, String eventType) {
        if (!StringUtils.hasText(externalId)) {
            return new ExternalKycSyncResponse("ignored_missing_external_id", eventType, null, null, null);
        }
        Optional<Investor> investor = investorRepository.findByExternalIdentityDocumentId(externalId.trim());
        if (investor.isEmpty()) {
            return new ExternalKycSyncResponse("ignored_no_matching_investor", eventType, externalId, null, null);
        }
        Investor saved = investor.get();
        refreshIdentityDocumentStateIfNeeded(saved);
        saved = investorRepository.save(saved);
        auditService.log("INVESTOR", saved.getId(), "IDENTITY_DOCUMENT_WEBHOOK_SYNCED", saved.getDistributorId(), auditDetails(saved));
        return new ExternalKycSyncResponse("synced", eventType, externalId, saved.getId(), saved.getKycStatus());
    }

    private ExternalKycSyncResponse syncEsignEvent(String externalId, String eventType) {
        if (!StringUtils.hasText(externalId)) {
            return new ExternalKycSyncResponse("ignored_missing_external_id", eventType, null, null, null);
        }
        Optional<Investor> investor = investorRepository.findByExternalEsignId(externalId.trim());
        if (investor.isEmpty()) {
            return new ExternalKycSyncResponse("ignored_no_matching_investor", eventType, externalId, null, null);
        }
        Investor saved = investor.get();
        refreshEsignStateIfNeeded(saved);
        saved = investorRepository.save(saved);
        auditService.log("INVESTOR", saved.getId(), "ESIGN_WEBHOOK_SYNCED", saved.getDistributorId(), auditDetails(saved));
        return new ExternalKycSyncResponse("synced", eventType, externalId, saved.getId(), saved.getKycStatus());
    }

    private ExternalKycSyncResponse syncKycRequestEvent(String externalId, String eventType) {
        if (!StringUtils.hasText(externalId)) {
            return new ExternalKycSyncResponse("ignored_missing_external_id", eventType, null, null, null);
        }
        Optional<Investor> investor = investorRepository.findByExternalKycRequestId(externalId.trim());
        if (investor.isEmpty()) {
            return new ExternalKycSyncResponse("ignored_no_matching_investor", eventType, externalId, null, null);
        }
        JsonNode response = cybrillaClient.fetchKycRequest(externalId.trim());
        Investor saved = investor.get();
        applyKycRequestResponse(saved, response);
        saved = saveAndStartBankVerificationIfKycComplete(saved, saved.getDistributorId(), "external_kyc_webhook");
        auditService.log("INVESTOR", saved.getId(), "EXTERNAL_KYC_WEBHOOK_SYNCED", saved.getDistributorId(), auditDetails(saved));
        return new ExternalKycSyncResponse("synced", eventType, externalId, saved.getId(), saved.getKycStatus());
    }

    private JsonNode webhookDataObject(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        JsonNode dataObject = payload.path("data").path("object");
        return dataObject.isMissingNode() || dataObject.isNull() ? payload : dataObject;
    }

    private boolean isPreVerificationReference(String eventType, String objectType, String externalId) {
        return "pre_verification".equalsIgnoreCase(objectType)
                || startsWithIgnoreCase(eventType, "pre_verification.")
                || startsWithIgnoreCase(externalId, "pv_");
    }

    private boolean isKycRequestReference(String eventType, String objectType, String externalId) {
        return "kyc_request".equalsIgnoreCase(objectType)
                || startsWithIgnoreCase(eventType, "kyc_request.")
                || startsWithIgnoreCase(externalId, "kycr_");
    }

    private boolean isIdentityDocumentReference(String eventType, String objectType, String externalId) {
        return "identity_document".equalsIgnoreCase(objectType)
                || startsWithIgnoreCase(eventType, "identity_document.")
                || startsWithIgnoreCase(externalId, "iddoc_");
    }

    private boolean isEsignReference(String eventType, String objectType, String externalId) {
        return "esign".equalsIgnoreCase(objectType)
                || startsWithIgnoreCase(eventType, "esign.")
                || startsWithIgnoreCase(externalId, "esign_");
    }

    private KycFlowStatusResponse buildKycFlowStatusResponse(Investor investor) {
        return KycFlowStatusResponse.from(
                investor,
                KycFlowStatusResponse.extractAadhaarRedirectUrl(investor),
                KycFlowStatusResponse.extractEsignRedirectUrl(investor),
                KycFlowStatusResponse.extractFieldsNeeded(investor)
        );
    }

    private void validateSandboxPanBeforePoaCall(Investor investor) {
        validateSandboxPanBeforePoaCall(investor == null ? null : investor.getPan());
    }

    private void validateSandboxPanBeforePoaCall(String rawPan) {
        if (!integrationEnvironment.enforceSandboxPanPatterns()) {
            return;
        }
        PanFormat.validateBeforePoaApi(PanFormat.normalize(rawPan), true);
    }

    private void validateProductionKycPrerequisites(Investor investor) {
        if (!integrationEnvironment.isProductionMode()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(investor.getFullName())) {
            missing.add("fullName");
        }
        if (!StringUtils.hasText(investor.getPan())) {
            missing.add("pan");
        }
        if (investor.getDateOfBirth() == null) {
            missing.add("dateOfBirth");
        }
        if (!StringUtils.hasText(investor.getEmail())) {
            missing.add("email");
        }
        if (!StringUtils.hasText(investor.getMobileNumber())) {
            missing.add("mobileNumber");
        }
        if (!StringUtils.hasText(investor.getAddressLine1())) {
            missing.add("addressLine1");
        }
        if (!StringUtils.hasText(investor.getCity())) {
            missing.add("city");
        }
        if (!StringUtils.hasText(investor.getState())) {
            missing.add("state");
        }
        if (!StringUtils.hasText(investor.getPostalCode())) {
            missing.add("postalCode");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Production KYC requires complete investor profile data before Cybrilla calls. Missing: "
                            + String.join(", ", missing)
            );
        }
    }

    private String defaultIdentityDocumentPostbackUrl() {
        return resolveIdentityDocumentPostbackUrl(null);
    }

    private String defaultEsignPostbackUrl() {
        return resolveEsignPostbackUrl(null);
    }

    private boolean startsWithIgnoreCase(String value, String prefix) {
        return StringUtils.hasText(value) && value.trim().toLowerCase(Locale.ROOT).startsWith(prefix);
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
        put(payload, "postback_url", resolveIdentityDocumentPostbackUrl(request));
        appendFields(payload, request.fields());
        return payload;
    }

    private String resolveIdentityDocumentPostbackUrl(IdentityDocumentCreateRequest request) {
        if (request != null && StringUtils.hasText(request.postbackUrl())) {
            return request.postbackUrl().trim();
        }
        String base = kycCallbackBaseUrl.replaceAll("/+$", "");
        String path = identityDocumentPostbackPath.startsWith("/")
                ? identityDocumentPostbackPath
                : "/" + identityDocumentPostbackPath;
        return base + path;
    }

    private String resolveEsignPostbackUrl(EsignStartRequest request) {
        if (request != null && StringUtils.hasText(request.postbackUrl())) {
            return request.postbackUrl().trim();
        }
        String base = kycCallbackBaseUrl.replaceAll("/+$", "");
        String path = esignPostbackPath.startsWith("/")
                ? esignPostbackPath
                : "/" + esignPostbackPath;
        return base + path;
    }

    private void applyIdentityDocumentResponse(Investor investor, JsonNode response) {
        if (response == null || response.isNull()) {
            return;
        }
        String responseId = firstText(response, "id");
        if (StringUtils.hasText(responseId)) {
            investor.setExternalIdentityDocumentId(responseId);
        }
        // A fresh identity-document state requires proof attachment reconciliation.
        investor.setAadhaarProofsAttached(Boolean.FALSE);
        investor.setAadhaarFetchStatus(nestedText(response, "fetch", "status"));
        investor.setAadhaarFetchReason(nestedText(response, "fetch", "reason"));
        investor.setExternalKycPayloadJson(response.toString());
    }

    private boolean isIdentityDocumentFetchComplete(JsonNode response) {
        String fetchStatus = nestedText(response, "fetch", "status");
        if (!StringUtils.hasText(fetchStatus)) {
            return false;
        }
        return switch (fetchStatus.trim().toLowerCase(Locale.ROOT)) {
            case "successful", "completed", "verified" -> true;
            default -> false;
        };
    }

    private boolean attachAadhaarProofsToKycRequest(Investor investor, String identityDocumentId) {
        if (!StringUtils.hasText(investor.getExternalKycRequestId()) || !StringUtils.hasText(identityDocumentId)) {
            return false;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("identity_proof", identityDocumentId);
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("proof_type", "aadhaar");
        address.put("proof", identityDocumentId);
        fields.put("address", address);
        JsonNode response = cybrillaClient.updateKycRequest(
                investor.getExternalKycRequestId(),
                fields
        );
        applyKycRequestResponse(investor, response);
        investor.setAadhaarProofsAttached(Boolean.TRUE);
        return true;
    }

    private void applyEsignResponse(Investor investor, JsonNode response) {
        if (response == null || response.isNull()) {
            return;
        }
        String responseId = firstText(response, "id");
        if (StringUtils.hasText(responseId)) {
            investor.setExternalEsignId(responseId);
        }
        String status = firstText(response, "status");
        investor.setEsignStatus(status);
        investor.setExternalKycPayloadJson(response.toString());
    }

    private boolean isEsignComplete(JsonNode response) {
        String status = firstText(response, "status");
        if (!StringUtils.hasText(status)) {
            return false;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return "successful".equals(normalized) || "completed".equals(normalized) || "complete".equals(normalized);
    }

    private Map<String, Object> preVerificationPayload(InvestorPreVerificationRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String pan = normalizePan(request.pan());
        putPoaValue(payload, "pan", pan);
        putPoaValue(payload, "name", request.fullName());
        if (request.dateOfBirth() != null) {
            putPoaValue(payload, "date_of_birth", request.dateOfBirth().toString());
        }
        return payload;
    }

    private String normalizePan(String rawPan) {
        return PanFormat.normalize(rawPan);
    }

    private void applyKycCheckResponse(Investor investor, JsonNode response) {
        if (response == null || response.isNull()) {
            return;
        }
        if ("kyc_request".equalsIgnoreCase(firstText(response, "object"))) {
            applyKycRequestResponse(investor, response);
            return;
        }
        String responseId = firstText(response, "id");
        if (StringUtils.hasText(responseId) && isPreVerification(response)) {
            investor.setExternalKycCheckId(responseId);
        }
        investor.setExternalKycStatus(kycStatusText(response));
        investor.setExternalKycPayloadJson(response.toString());
        applyPreVerificationResultFields(investor, response);
        investor.setKycStatus(resolveKycCheckStatus(response));
        markReadyIfEligible(investor);
    }

    private void applyKycComplianceResponse(Investor investor, JsonNode response) {
        if (response == null || response.isNull()) {
            return;
        }
        String responseId = firstText(response, "id");
        if (StringUtils.hasText(responseId)) {
            investor.setExternalKycComplianceId(responseId);
        }
        JsonNode statusNode = response.path("status");
        Boolean complianceStatus = statusNode.isBoolean() ? statusNode.asBoolean() : null;
        investor.setKycComplianceStatus(complianceStatus);
        investor.setKycComplianceReason(firstText(response, "reason"));
        investor.setKycComplianceAction(firstText(response, "action"));

        JsonNode constraints = response.path("constraints");
        boolean hasConstraints = constraints.isArray() && constraints.size() > 0;
        investor.setKycConstraintsJson(hasConstraints ? constraints.toString() : null);

        investor.setExternalKycStatus(complianceStatus == null ? null : Boolean.toString(complianceStatus));
        investor.setExternalKycPayloadJson(response.toString());
        investor.setKycStatus(resolveComplianceKycStatus(
                complianceStatus,
                investor.getKycComplianceReason(),
                investor.getKycComplianceAction()
        ));
        markReadyIfEligible(investor);
    }

    /**
     * Maps the FP KYC Check {@code status}/{@code reason}/{@code action} triple
     * to a local {@link KycStatus}. Programmatic interpretation uses
     * {@code reason}/{@code action} codes, never the free-text reason string.
     */
    private KycStatus resolveComplianceKycStatus(Boolean complianceStatus, String reason, String action) {
        if (Boolean.TRUE.equals(complianceStatus)) {
            return KycStatus.COMPLETED;
        }
        if (complianceStatus == null) {
            return KycStatus.PENDING;
        }
        String normalizedAction = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        String normalizedReason = reason == null ? "" : reason.trim().toLowerCase(Locale.ROOT);
        if ("create".equals(normalizedAction)
                || "unavailable".equals(normalizedReason)
                || "rejected".equals(normalizedReason)) {
            return KycStatus.NOT_STARTED;
        }
        if ("disallowed".equals(normalizedAction) || "deactivated".equals(normalizedReason)) {
            return KycStatus.FAILED;
        }
        if ("none".equals(normalizedAction) || "underprocess".equals(normalizedReason)) {
            return KycStatus.IN_PROGRESS;
        }
        // modify (incomplete / legacy / onhold) and unknown → a one-time fix is needed.
        return KycStatus.RETRY_REQUIRED;
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
            case "true", "verified", "completed", "complete", "successful" -> KycStatus.COMPLETED;
            case "failed", "failure", "rejected", "invalid" -> KycStatus.FAILED;
            case "expired" -> KycStatus.RETRY_REQUIRED;
            case "pending", "submitted", "esign_required", "in_progress", "processing" -> KycStatus.IN_PROGRESS;
            default -> defaultStatus;
        };
    }

    private void applyPreVerificationResultFields(Investor investor, JsonNode response) {
        if (!isPreVerification(response)) {
            return;
        }

        investor.setKycReadinessStatus(nestedText(response, "readiness", "status"));
        investor.setKycReadinessCode(nestedText(response, "readiness", "code"));
        investor.setKycReadinessReason(nestedText(response, "readiness", "reason"));
        investor.setPanVerificationStatus(nestedText(response, "pan", "status"));
        investor.setPanVerificationCode(nestedText(response, "pan", "code"));
        investor.setPanVerificationReason(nestedText(response, "pan", "reason"));
        applyPanAadhaarLinkResult(investor);
    }

    private void applyPanAadhaarLinkResult(Investor investor) {
        String panStatus = investor.getPanVerificationStatus();
        String panCode = investor.getPanVerificationCode();
        if ("aadhaar_not_linked".equalsIgnoreCase(panCode)) {
            investor.setPanAadhaarLinkStatus("NOT_LINKED");
            investor.setPanAadhaarLinkReason(defaultText(
                    investor.getPanVerificationReason(),
                    "PAN is not seeded with Aadhaar"
            ));
            return;
        }
        if ("verified".equalsIgnoreCase(panStatus)) {
            investor.setPanAadhaarLinkStatus("LINKED");
            investor.setPanAadhaarLinkReason(null);
            return;
        }
        if (StringUtils.hasText(panStatus) || StringUtils.hasText(panCode)) {
            investor.setPanAadhaarLinkStatus("UNKNOWN");
            investor.setPanAadhaarLinkReason(investor.getPanVerificationReason());
            return;
        }
        investor.setPanAadhaarLinkStatus(null);
        investor.setPanAadhaarLinkReason(null);
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
