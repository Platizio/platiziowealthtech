package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.domain.TermsAcceptance;
import com.platizio.wealthtech.dto.ContactDeclarationRequest;
import com.platizio.wealthtech.dto.ContactVerificationStatus;
import com.platizio.wealthtech.dto.DistributorProfileSubmitRequest;
import com.platizio.wealthtech.dto.DistributorProfileSubmitResponse;
import com.platizio.wealthtech.dto.InvestorBankRequest;
import com.platizio.wealthtech.dto.InvestorCreateRequest;
import com.platizio.wealthtech.dto.OtpVerifyCodeRequest;
import com.platizio.wealthtech.dto.TermsAcceptanceRequest;
import com.platizio.wealthtech.dto.InvestorDocumentUploadResponse;
import com.platizio.wealthtech.dto.InvestorExternalKycResponse;
import com.platizio.wealthtech.dto.InvestorKycCheckRequest;
import com.platizio.wealthtech.dto.InvestorKycRequestCreateRequest;
import com.platizio.wealthtech.dto.InvestorKycRequestUpdateRequest;
import com.platizio.wealthtech.dto.InvestorKycSimulationRequest;
import com.platizio.wealthtech.dto.InvestorOnboardingResumeResponse;
import com.platizio.wealthtech.dto.InvestorPreVerificationRequest;
import com.platizio.wealthtech.dto.InvestorPreVerificationResponse;
import com.platizio.wealthtech.dto.AadhaarVerificationResponse;
import com.platizio.wealthtech.dto.EsignStartRequest;
import com.platizio.wealthtech.dto.EsignVerificationResponse;
import com.platizio.wealthtech.dto.IdentityDocumentCreateRequest;
import com.platizio.wealthtech.dto.KycFlowAdvanceResponse;
import com.platizio.wealthtech.dto.KycFlowStatusResponse;
import com.platizio.wealthtech.dto.InvestorUpdateRequest;
import com.platizio.wealthtech.dto.SendToInvestorRequest;
import com.platizio.wealthtech.dto.SendToInvestorResponse;
import com.platizio.wealthtech.dto.UploadedInvestorDocumentResponse;
import com.platizio.wealthtech.security.JwtAuthPrincipal;
import com.platizio.wealthtech.domain.InvestorLinkingStatus;
import com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge;
import com.platizio.wealthtech.domain.InvestorLinkRequest;
import com.platizio.wealthtech.domain.OnboardingSubmission;
import com.platizio.wealthtech.service.ConsentRecordService;
import com.platizio.wealthtech.service.InvestorContactVerificationService;
import com.platizio.wealthtech.service.InvestorDocumentService;
import com.platizio.wealthtech.service.InvestorKycService;
import com.platizio.wealthtech.service.InvestorLinkRequestService;
import com.platizio.wealthtech.service.InvestorService;
import com.platizio.wealthtech.service.OnboardingSubmissionService;
import com.platizio.wealthtech.service.TermsAcceptanceService;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/investors")
@Tag(name = "Investors", description = "Endpoints for managing investors and their KYC/Bank details")
public class InvestorController {

    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(InvestorController.class);

    private final InvestorService investorService;
    private final InvestorDocumentService investorDocumentService;
    private final InvestorKycService investorKycService;
    private final InvestorContactVerificationService contactVerificationService;
    private final TermsAcceptanceService termsAcceptanceService;
    private final OnboardingSubmissionService onboardingSubmissionService;
    private final InvestorLinkRequestService investorLinkRequestService;

    public InvestorController(
            InvestorService investorService,
            InvestorDocumentService investorDocumentService,
            InvestorKycService investorKycService,
            InvestorContactVerificationService contactVerificationService,
            TermsAcceptanceService termsAcceptanceService,
            OnboardingSubmissionService onboardingSubmissionService,
            InvestorLinkRequestService investorLinkRequestService
    ) {
        this.investorService = investorService;
        this.investorDocumentService = investorDocumentService;
        this.investorKycService = investorKycService;
        this.contactVerificationService = contactVerificationService;
        this.termsAcceptanceService = termsAcceptanceService;
        this.onboardingSubmissionService = onboardingSubmissionService;
        this.investorLinkRequestService = investorLinkRequestService;
    }

    @Operation(summary = "List investors", description = "Returns paginated investors visible to the authenticated distributor.")
    @ApiResponse(responseCode = "200", description = "List of investors", 
                 content = @Content(array = @ArraySchema(schema = @Schema(implementation = Investor.class))))
    @GetMapping
    public Page<Investor> listAll(
            @RequestParam(required = false) UUID distributorId,
            @RequestParam(defaultValue = "false") boolean syncFromCybrilla,
            @RequestParam(defaultValue = "false") boolean forceSync,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth
    ) {
        UUID actorId = actorId(auth);
        UUID syncDistributorId = distributorId != null ? distributorId : actorId;
        investorService.syncFromCybrillaBeforeRead(syncDistributorId, syncFromCybrilla, forceSync);
        return investorService.listVisibleToRequesterPage(actorId, distributorId, page, size);
    }

    @Operation(summary = "Create a new investor", description = "Registers a new investor in the system with DRAFT status.")
    @ApiResponse(responseCode = "200", description = "Investor created successfully", 
                 content = @Content(schema = @Schema(implementation = Investor.class)))
    @PostMapping
    public Investor create(@Valid @RequestBody InvestorCreateRequest request, Authentication auth) {
        return investorService.createInvestor(request, actorId(auth));
    }

    @Operation(summary = "Send to Investor (approval-gated onboarding)",
            description = "From Step-1 basic identity, parks a PENDING investor (distributor_id NOT linked yet — R5) "
                    + "keyed on PAN (R4) and sends it to the investor for approval. Returns PENDING_INVESTOR_APPROVAL "
                    + "plus the approval token so the email-link flow is demoable without SMTP (investor.md §3 T1, §6.1).")
    @ApiResponse(responseCode = "200", description = "Investor parked PENDING and sent for approval",
                 content = @Content(schema = @Schema(implementation = SendToInvestorResponse.class)))
    @PostMapping("/send-to-investor")
    public SendToInvestorResponse sendToInvestor(
            @Valid @RequestBody SendToInvestorRequest request, Authentication auth) {
        UUID distributorId = actorId(auth);
        Investor investor = investorService.createOrUpdatePendingInvestor(request, distributorId);
        InvestorLinkRequest linkRequest =
                investorLinkRequestService.sendToInvestor(investor.getId(), distributorId, request.payloadJson());
        // Dev surfacing (no real email yet — that's a later shared task): log the approval URL at
        // INFO and return the token so the flow is demoable/testable without SMTP.
        String approvalUrl = "/investor/approve?token=" + linkRequest.getToken();
        logger.info(
                "investor_link status='sent_to_investor' investor_id='{}' distributor_id='{}' approval_url='{}'",
                investor.getId(), distributorId, approvalUrl);
        return new SendToInvestorResponse(
                "PENDING_INVESTOR_APPROVAL",
                "Investor approval is pending.",
                investor.getId(),
                linkRequest.getToken());
    }

    // ── Contact verification (email + mobile OTP via Supabase / self-declaration) ──

    @Operation(summary = "Send an email verification OTP", description = "Sends a one-time code to the investor's email via Supabase Auth.")
    @PostMapping("/{id}/email/otp/request")
    public ContactVerificationStatus requestEmailOtp(@PathVariable UUID id, Authentication auth) {
        return contactVerificationService.requestEmailOtp(id, actorId(auth));
    }

    @Operation(summary = "Verify the email OTP", description = "Verifies the emailed code and marks the email verified (method OTP).")
    @PostMapping("/{id}/email/otp/verify")
    public ContactVerificationStatus verifyEmailOtp(
            @PathVariable UUID id, @Valid @RequestBody OtpVerifyCodeRequest request, Authentication auth) {
        return contactVerificationService.verifyEmailOtp(id, actorId(auth), request.code());
    }

    @Operation(summary = "Send a mobile verification OTP", description = "Sends a one-time code to the investor's mobile via Supabase Auth SMS.")
    @PostMapping("/{id}/mobile/otp/request")
    public ContactVerificationStatus requestMobileOtp(@PathVariable UUID id, Authentication auth) {
        return contactVerificationService.requestMobileOtp(id, actorId(auth));
    }

    @Operation(summary = "Verify the mobile OTP", description = "Verifies the SMS code and marks the mobile verified (method OTP).")
    @PostMapping("/{id}/mobile/otp/verify")
    public ContactVerificationStatus verifyMobileOtp(
            @PathVariable UUID id, @Valid @RequestBody OtpVerifyCodeRequest request, Authentication auth) {
        return contactVerificationService.verifyMobileOtp(id, actorId(auth), request.code());
    }

    @Operation(summary = "Self-declare a contact", description = "Distributor attests an email/mobile belongs to the investor, with the relationship (belongs_to).")
    @PostMapping("/{id}/contact/declare")
    public ContactVerificationStatus declareContact(
            @PathVariable UUID id, @Valid @RequestBody ContactDeclarationRequest request, Authentication auth) {
        return contactVerificationService.declareContact(id, actorId(auth), request.channel(), request.belongsTo());
    }

    @Operation(summary = "Get contact verification status", description = "Returns the email/mobile verification state for the investor.")
    @GetMapping("/{id}/contact/status")
    public ContactVerificationStatus contactStatus(@PathVariable UUID id, Authentication auth) {
        return contactVerificationService.getStatus(id, actorId(auth));
    }

    // ── Terms & Conditions acceptance (Task 4 / DF-10) ──

    @Operation(summary = "Record a T&C acceptance", description = "Persists that the investor accepted a terms document (version), with timestamp + IP/UA.")
    @PostMapping("/{id}/terms/accept")
    public TermsAcceptance acceptTerms(
            @PathVariable UUID id,
            @Valid @RequestBody TermsAcceptanceRequest request,
            HttpServletRequest httpRequest,
            Authentication auth) {
        UUID actorId = actorId(auth);
        investorService.getInvestor(id, actorId); // ownership check (404/403 if not the distributor's investor)
        return termsAcceptanceService.record(
                TermsAcceptanceService.SUBJECT_INVESTOR, id, request.documentKey(), request.version(),
                clientIp(httpRequest), httpRequest.getHeader("User-Agent"), actorId);
    }

    @Operation(summary = "List T&C acceptances", description = "Returns the investor's recorded terms acceptances (latest first).")
    @GetMapping("/{id}/terms")
    public List<TermsAcceptance> listTerms(@PathVariable UUID id, Authentication auth) {
        UUID actorId = actorId(auth);
        investorService.getInvestor(id, actorId); // ownership check
        return termsAcceptanceService.list(TermsAcceptanceService.SUBJECT_INVESTOR, id);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @GetMapping("/filter/kyc")
    public List<Investor> filterByKycStatus(
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(required = false) UUID distributorId,
            Authentication auth
    ) {
        return investorService.filterByKycStatus(status, distributorId, actorId(auth));
    }

    @GetMapping("/search")
    public List<Investor> search(
            @RequestParam String query,
            @RequestParam(required = false) UUID distributorId,
            @RequestParam(defaultValue = "false") boolean syncFromCybrilla,
            @RequestParam(defaultValue = "false") boolean forceSync,
            @RequestParam(defaultValue = "10") int limit,
            Authentication auth
    ) {
        UUID actorId = actorId(auth);
        UUID syncDistributorId = distributorId != null ? distributorId : actorId;
        investorService.syncFromCybrillaBeforeRead(syncDistributorId, syncFromCybrilla, forceSync);
        return investorService.search(query, distributorId, actorId, limit);
    }

    @GetMapping("/search/transaction-eligible")
    public List<Investor> searchTransactionEligible(
            @RequestParam String query,
            @RequestParam(required = false) UUID distributorId,
            @RequestParam(defaultValue = "10") int limit,
            Authentication auth
    ) {
        return investorService.searchEligibleForTransactions(query, distributorId, actorId(auth), limit);
    }

    @Operation(
            summary = "List investors for a distributor",
            description = "When Cybrilla investor source is enabled (default), this calls Finprim "
                    + "GET /v2/investor_profiles before returning the local cache so the UI shows "
                    + "reconciled investor rows. Finprim has no separate sync API."
    )
    @GetMapping("/by-distributor/{distributorId}")
    public List<Investor> listByDistributor(
            @PathVariable UUID distributorId,
            @RequestParam(defaultValue = "false") boolean syncFromCybrilla,
            @RequestParam(defaultValue = "false") boolean forceSync,
            Authentication auth
    ) {
        investorService.syncFromCybrillaBeforeRead(distributorId, syncFromCybrilla, forceSync);
        return investorService.listVisibleToDistributor(actorId(auth), distributorId);
    }

    @PostMapping("/sync-from-cybrilla")
    public java.util.Map<String, Object> syncFromCybrilla(
            @RequestParam(required = false) UUID distributorId,
            @RequestParam(defaultValue = "false") boolean importNew,
            Authentication auth
    ) {
        UUID actorId = actorId(auth);
        UUID syncDistributorId = distributorId != null ? distributorId : actorId;
        var syncResult = investorService.syncFromCybrillaExplicit(syncDistributorId, actorId, importNew);
        int count = investorService.listByDistributor(syncDistributorId).size();
        java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("status", "completed");
        response.put("distributorId", syncDistributorId.toString());
        response.put("localCount", count);
        response.put("providerCount", syncResult.providerCount());
        response.put("restored", syncResult.restored());
        response.put("imported", syncResult.imported());
        response.put("updated", syncResult.updated());
        response.put("skippedUnlinked", syncResult.skippedUnlinked());
        response.put("skippedArchived", syncResult.skippedArchived());
        response.put("failed", syncResult.failed());
        response.put("importNew", importNew);
        response.put("message", buildInvestorSyncMessage(syncResult, count));
        return response;
    }

    @Operation(
            summary = "Restore one investor from Finprim",
            description = "Re-links a single Finprim investor profile into the local cache after a local archive/delete."
    )
    @PostMapping("/restore-from-cybrilla")
    public Investor restoreFromCybrilla(
            @RequestParam(required = false) UUID distributorId,
            @RequestParam(required = false) String cybrillaInvestorId,
            @RequestParam(required = false) String pan,
            Authentication auth
    ) {
        UUID actorId = actorId(auth);
        UUID syncDistributorId = distributorId != null ? distributorId : actorId;
        return investorService.restoreInvestorFromCybrilla(syncDistributorId, actorId, cybrillaInvestorId, pan);
    }

    @Operation(
            summary = "Purge local investor cache",
            description = "Soft-deletes all investors in the local PostgreSQL cache for a distributor. "
                    + "Does not remove Finprim investor profiles (FP has no public DELETE API). "
                    + "After purge, passive reads will not re-import FP profiles unless syncFromCybrilla=true."
    )
    @PostMapping("/purge-local-cache")
    public java.util.Map<String, Object> purgeLocalCache(
            @RequestParam(required = false) UUID distributorId,
            Authentication auth
    ) {
        UUID actorId = actorId(auth);
        UUID targetDistributorId = distributorId != null ? distributorId : actorId;
        int purged = investorService.purgeLocalInvestorCache(targetDistributorId, actorId);
        return java.util.Map.of(
                "status", "completed",
                "distributorId", targetDistributorId.toString(),
                "purgedCount", purged,
                "message", purged == 0
                        ? "Local investor cache was already empty for this distributor."
                        : "Local investor cache purged. Finprim profiles remain until Cybrilla support resets the sandbox tenant."
        );
    }

    private static String buildInvestorSyncMessage(
            com.platizio.wealthtech.service.InvestorCybrillaSyncService.InvestorSyncResult syncResult,
            int localCount
    ) {
        if (syncResult.providerCount() == 0) {
            return "Finprim returned 0 investor profiles for this tenant.";
        }
        if (syncResult.restored() > 0) {
            return "Restored "
                    + syncResult.restored()
                    + " archived investor(s) from Finprim into your local list.";
        }
        if (syncResult.imported() > 0) {
            return "Imported "
                    + syncResult.imported()
                    + " new Finprim profile(s) into your local cache.";
        }
        if (syncResult.failed() > 0) {
            return "Restored "
                    + syncResult.restored()
                    + " investor(s), but "
                    + syncResult.failed()
                    + " Finprim profile(s) could not be reconciled. Check backend logs.";
        }
        if (localCount == 0 && !syncResult.importNew()) {
            return "Finprim has "
                    + syncResult.providerCount()
                    + " profile(s). None were linked locally. "
                    + "Archive restore only runs for investors you previously onboarded. "
                    + "Use importNew=true only for sandbox cleanup imports.";
        }
        return "Investor cache checked against Finprim. Active rows updated where needed.";
    }

    @GetMapping("/by-distributor/{distributorId}/visible-to/{requesterId}")
    public List<Investor> listByDistributorForRequester(
            @PathVariable UUID distributorId,
            @PathVariable UUID requesterId,
            Authentication auth
    ) {
        UUID actorId = actorId(auth);
        if (!actorId.equals(requesterId)) {
            throw new AccessDeniedException("Requester id must match authenticated principal");
        }
        return investorService.listVisibleToDistributor(actorId, distributorId);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR','SUB_DISTRIBUTOR')")
    @GetMapping("/households/{householdId}")
    public List<Investor> listHousehold(
            @PathVariable UUID householdId,
            @RequestParam UUID distributorId,
            Authentication auth
    ) {
        return investorService.listHousehold(actorId(auth), distributorId, householdId);
    }

    @GetMapping("/visible-to/{requesterId}")
    public List<Investor> listVisibleToRequester(@PathVariable UUID requesterId, Authentication auth) {
        UUID actorId = actorId(auth);
        if (!actorId.equals(requesterId)) {
            throw new AccessDeniedException("Requester id must match authenticated principal");
        }
        return investorService.listVisibleToMaster(actorId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{investorId}/kyc")
    public Investor updateKycStatus(
            @PathVariable UUID investorId,
            @RequestParam KycStatus status,
            Authentication auth
    ) {
        return investorService.updateKycStatus(investorId, status, actorId(auth));
    }

    @PostMapping("/pre-verifications")
    public InvestorPreVerificationResponse createPreVerification(
            @Valid @RequestBody InvestorPreVerificationRequest request,
            Authentication auth
    ) {
        return investorKycService.createPreVerification(request, actorId(auth));
    }

    @GetMapping("/pre-verifications/{preVerificationId}")
    public InvestorPreVerificationResponse fetchPreVerification(
            @PathVariable String preVerificationId,
            Authentication auth
    ) {
        return investorKycService.fetchPreVerification(preVerificationId, actorId(auth));
    }

    @PostMapping("/{investorId}/kyc-checks")
    public InvestorExternalKycResponse createKycCheck(
            @PathVariable UUID investorId,
            @Valid @RequestBody(required = false) InvestorKycCheckRequest request,
            Authentication auth
    ) {
        return investorKycService.createKycCheck(investorId, request, actorId(auth));
    }

    @PostMapping("/{investorId}/kyc-compliance-check")
    public InvestorExternalKycResponse runKycComplianceCheck(
            @PathVariable UUID investorId,
            @RequestParam(defaultValue = "false") boolean fetchData,
            Authentication auth
    ) {
        return investorKycService.runKycComplianceCheck(investorId, fetchData, actorId(auth));
    }

    @PostMapping("/{investorId}/kyc/apply")
    public InvestorExternalKycResponse applyKyc(
            @PathVariable UUID investorId,
            @Valid @RequestBody(required = false) InvestorKycCheckRequest request,
            Authentication auth
    ) {
        return investorKycService.applyInvestorKyc(investorId, request, actorId(auth));
    }

    @PostMapping({"/{investorId}/kyc/rekyc", "/{investorId}/kyc/reapply", "/{investorId}/kyc/re-apply"})
    public InvestorExternalKycResponse reapplyKyc(
            @PathVariable UUID investorId,
            @Valid @RequestBody(required = false) InvestorKycCheckRequest request,
            Authentication auth
    ) {
        return investorKycService.reapplyInvestorKyc(investorId, request, actorId(auth));
    }

    @GetMapping("/{investorId}/kyc-checks/{kycCheckId}")
    public InvestorExternalKycResponse fetchKycCheck(
            @PathVariable UUID investorId,
            @PathVariable String kycCheckId,
            Authentication auth
    ) {
        return investorKycService.fetchKycCheck(investorId, kycCheckId, actorId(auth));
    }

    @PutMapping("/{investorId}/kyc-checks/{kycCheckId}/refetch")
    public InvestorExternalKycResponse refetchKycCheck(
            @PathVariable UUID investorId,
            @PathVariable String kycCheckId,
            Authentication auth
    ) {
        return investorKycService.refetchKycCheck(investorId, kycCheckId, actorId(auth));
    }

    @RequestMapping(
            value = {"/{investorId}/kyc-sync", "/{investorId}/kyc/refresh"},
            method = {RequestMethod.GET, RequestMethod.POST}
    )
    public InvestorExternalKycResponse syncSavedKycStatus(
            @PathVariable UUID investorId,
            Authentication auth
    ) {
        return investorKycService.syncInvestorExternalKycStatus(investorId, actorId(auth));
    }

    @GetMapping("/{investorId}/kyc-flow/status")
    public KycFlowStatusResponse getKycFlowStatus(
            @PathVariable UUID investorId,
            Authentication auth
    ) {
        return investorKycService.getKycFlowStatus(investorId, actorId(auth));
    }

    @PostMapping("/{investorId}/kyc-flow/advance")
    public KycFlowAdvanceResponse advanceKycFlow(
            @PathVariable UUID investorId,
            Authentication auth
    ) {
        return investorKycService.advanceKycFlow(investorId, actorId(auth));
    }

    @GetMapping("/{investorId}/kyc-requests")
    public JsonNode listKycRequests(
            @PathVariable UUID investorId,
            @RequestParam(required = false) String status,
            Authentication auth
    ) {
        return investorKycService.listKycRequests(investorId, status, actorId(auth));
    }

    @PostMapping("/{investorId}/kyc-requests")
    public InvestorExternalKycResponse createKycRequest(
            @PathVariable UUID investorId,
            @Valid @RequestBody(required = false) InvestorKycRequestCreateRequest request,
            Authentication auth
    ) {
        return investorKycService.createKycRequest(investorId, request, actorId(auth));
    }

    @GetMapping("/{investorId}/kyc-requests/{kycRequestId}")
    public InvestorExternalKycResponse fetchKycRequest(
            @PathVariable UUID investorId,
            @PathVariable String kycRequestId,
            Authentication auth
    ) {
        return investorKycService.fetchKycRequest(investorId, kycRequestId, actorId(auth));
    }

    @PatchMapping("/{investorId}/kyc-requests/{kycRequestId}")
    public InvestorExternalKycResponse updateKycRequest(
            @PathVariable UUID investorId,
            @PathVariable String kycRequestId,
            @Valid @RequestBody InvestorKycRequestUpdateRequest request,
            Authentication auth
    ) {
        return investorKycService.updateKycRequest(investorId, kycRequestId, request, actorId(auth));
    }

    @PostMapping("/{investorId}/kyc-requests/{kycRequestId}/simulate")
    public InvestorExternalKycResponse simulateKycRequest(
            @PathVariable UUID investorId,
            @PathVariable String kycRequestId,
            @Valid @RequestBody InvestorKycSimulationRequest request,
            Authentication auth
    ) {
        return investorKycService.simulateKycRequest(investorId, kycRequestId, request.status(), actorId(auth));
    }

    @PostMapping("/{investorId}/identity-documents")
    public AadhaarVerificationResponse createIdentityDocument(
            @PathVariable UUID investorId,
            @Valid @RequestBody(required = false) IdentityDocumentCreateRequest request,
            Authentication auth
    ) {
        return investorKycService.createIdentityDocument(
                investorId,
                request == null ? new IdentityDocumentCreateRequest(null, "aadhaar", null, null) : request,
                actorId(auth)
        );
    }

    @GetMapping("/{investorId}/identity-documents/{identityDocumentId}")
    public JsonNode fetchIdentityDocument(
            @PathVariable UUID investorId,
            @PathVariable String identityDocumentId,
            Authentication auth
    ) {
        return investorKycService.fetchIdentityDocument(investorId, identityDocumentId, actorId(auth));
    }

    @PostMapping("/{investorId}/identity-documents/{identityDocumentId}/refresh")
    public AadhaarVerificationResponse refreshIdentityDocument(
            @PathVariable UUID investorId,
            @PathVariable String identityDocumentId,
            Authentication auth
    ) {
        // Keep legacy path for backward compatibility; service now uses saved id.
        return investorKycService.refreshIdentityDocumentForInvestor(investorId, actorId(auth));
    }

    @PostMapping("/{investorId}/identity-documents/refresh")
    public AadhaarVerificationResponse refreshIdentityDocument(
            @PathVariable UUID investorId,
            Authentication auth
    ) {
        return investorKycService.refreshIdentityDocumentForInvestor(investorId, actorId(auth));
    }

    @PostMapping("/{investorId}/esign/start")
    public EsignVerificationResponse createEsign(
            @PathVariable UUID investorId,
            @Valid @RequestBody(required = false) EsignStartRequest request,
            Authentication auth
    ) {
        return investorKycService.createEsign(investorId, request, actorId(auth));
    }

    @PostMapping("/{investorId}/esign/refresh")
    public EsignVerificationResponse refreshEsign(
            @PathVariable UUID investorId,
            Authentication auth
    ) {
        return investorKycService.refreshEsign(investorId, actorId(auth));
    }

    @GetMapping("/{investorId}/identity-documents")
    public JsonNode listIdentityDocuments(
            @PathVariable UUID investorId,
            @RequestParam(required = false) String kycRequestId,
            @RequestParam(required = false) String fetchStatus,
            Authentication auth
    ) {
        return investorKycService.listIdentityDocuments(investorId, kycRequestId, fetchStatus, actorId(auth));
    }

    @PostMapping("/{investorId}/bank-accounts")
    public InvestorBankAccount addBank(
            @PathVariable UUID investorId,
            @Valid @RequestBody InvestorBankRequest request,
            Authentication auth
    ) {
        return investorService.addBankAccount(investorId, request, actorId(auth));
    }

    @GetMapping("/{investorId}/bank-accounts")
    public List<InvestorBankAccount> listBankAccounts(
            @PathVariable UUID investorId,
            Authentication auth
    ) {
        return investorService.listBankAccounts(investorId, actorId(auth));
    }

    @PatchMapping("/{investorId}/bank-accounts/{bankAccountId}/verification")
    public InvestorBankAccount refreshBankVerification(
            @PathVariable UUID investorId,
            @PathVariable UUID bankAccountId,
            Authentication auth
    ) {
        return investorService.refreshBankVerification(investorId, bankAccountId, actorId(auth));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{investorId}/bank-verify")
    public Investor verifyBank(@PathVariable UUID investorId, Authentication auth) {
        return investorService.verifyBank(investorId, actorId(auth));
    }

    @GetMapping("/{investorId}")
    public Investor getInvestorById(@PathVariable UUID investorId, Authentication auth) {
        return investorService.getInvestor(investorId, actorId(auth));
    }

    @GetMapping({"/{investorId}/onboarding", "/{investorId}/onboarding/resume"})
    public InvestorOnboardingResumeResponse getOnboardingResume(
            @PathVariable UUID investorId,
            Authentication auth
    ) {
        return investorService.getOnboardingResume(investorId, actorId(auth));
    }

    @PutMapping("/{investorId}")
    public Investor updateInvestor(
            @PathVariable UUID investorId,
            @Valid @RequestBody InvestorUpdateRequest request,
            Authentication auth
    ) {
        UUID actorId = actorId(auth);
        Investor updated = investorService.updateInvestor(investorId, request, actorId);
        // A distributor edit to KYC-material data invalidates any prior investor attestation.
        onboardingSubmissionService.invalidateAttestationOnEdit(investorId, actorId);
        return updated;
    }

    // ── Investor-approval gate on distributor onboarding (SRS FR-ONB-001/002/003) ──

    @Operation(summary = "Submit onboarding for investor review",
            description = "Freezes the current KYC-material snapshot into a hashed revision the investor must attest before finalize.")
    @PostMapping("/{id}/onboarding/submit-for-review")
    public OnboardingSubmission submitForReview(@PathVariable UUID id, Authentication auth) {
        UUID actorId = actorId(auth);
        Investor investor = investorService.getInvestor(id, actorId); // ownership check (404/403)
        return onboardingSubmissionService.submitForInvestorReview(id, onboardingSnapshotJson(investor), actorId);
    }

    @Operation(summary = "Onboarding approval status",
            description = "Returns the latest revision's status/hash/revisionNo, or status NONE when nothing was submitted.")
    @GetMapping("/{id}/onboarding/approval-status")
    public java.util.Map<String, Object> approvalStatus(@PathVariable UUID id, Authentication auth) {
        UUID actorId = actorId(auth);
        investorService.getInvestor(id, actorId); // ownership check
        return onboardingSubmissionService.latestRevisionForInvestor(id)
                .map(s -> {
                    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("status", s.getStatus().name());
                    body.put("contentSha256", s.getContentSha256());
                    body.put("revisionNo", s.getRevisionNo());
                    return body;
                })
                .orElseGet(() -> java.util.Map.of("status", "NONE"));
    }

    @Operation(summary = "Finalize onboarding",
            description = "Hard gate: requires the latest revision to be ATTESTED and unchanged, then marks the investor ready.")
    @PostMapping("/{id}/onboarding/finalize")
    public Investor finalizeOnboarding(@PathVariable UUID id, Authentication auth) {
        UUID actorId = actorId(auth);
        Investor investor = investorService.getInvestor(id, actorId); // ownership check
        onboardingSubmissionService.assertFinalizable(id, ConsentRecordService.sha256(onboardingSnapshotJson(investor)));
        return investorService.markReadyAfterInvestorApproval(id, actorId);
    }

    // ── Distributor-facing skip-form path (investor.md R10) ──
    // The investor approved the link but skipped the profile form, so the distributor fills
    // it on their behalf. Both endpoints resolve the acting distributor from the JWT
    // (NEVER from the body) and delegate to InvestorService, which asserts the investor is
    // in a fillable state and that the acting distributor is the one linked to the investor.
    // Wrong-state → IllegalStateException (400); cross-distributor → AccessDeniedException
    // (403), both mapped by GlobalExceptionHandler. The investor-facing approve/apply/reject
    // half lives on the investor-portal track and is intentionally not exposed here.

    @Operation(summary = "Submit distributor-filled profile for investor review (R10)",
            description = "From an INVESTOR_SKIPPED (or DISTRIBUTOR_FILLING) investor, moves linking_status "
                    + "→ DISTRIBUTOR_FILLING and freezes the distributor-filled profile into a fresh 2FA "
                    + "challenge the investor must approve. The acting distributor is taken from the JWT.")
    @PostMapping("/{investorId}/profile/submit")
    public DistributorProfileSubmitResponse submitProfile(
            @PathVariable UUID investorId,
            @Valid @RequestBody DistributorProfileSubmitRequest body,
            Authentication auth) {
        ProfileChangeApprovalChallenge challenge =
                investorService.submitProfileForInvestorReview(investorId, body.payloadJson(), actorId(auth));
        return new DistributorProfileSubmitResponse(
                investorId, InvestorLinkingStatus.DISTRIBUTOR_FILLING, challenge.getId());
    }

    @Operation(summary = "Update the pending distributor-filled profile (R10)",
            description = "While still DISTRIBUTOR_FILLING, supersedes any live challenge (forcing "
                    + "re-authorisation) and freezes a fresh challenge over the edited profile. The acting "
                    + "distributor is taken from the JWT.")
    @PutMapping("/{investorId}/profile")
    public DistributorProfileSubmitResponse updateProfile(
            @PathVariable UUID investorId,
            @Valid @RequestBody DistributorProfileSubmitRequest body,
            Authentication auth) {
        ProfileChangeApprovalChallenge challenge =
                investorService.updateProfile(investorId, body.payloadJson(), actorId(auth));
        return new DistributorProfileSubmitResponse(
                investorId, InvestorLinkingStatus.DISTRIBUTOR_FILLING, challenge.getId());
    }

    /**
     * Deterministic JSON of the KYC-material investor fields (stable key order via a
     * {@link java.util.LinkedHashMap}). Hashing this proves exactly what the investor
     * attested; any later edit changes the hash and invalidates the attestation.
     */
    private String onboardingSnapshotJson(Investor investor) {
        java.util.LinkedHashMap<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("fullName", investor.getFullName());
        snapshot.put("pan", investor.getPan());
        snapshot.put("dateOfBirth", investor.getDateOfBirth() == null ? null : investor.getDateOfBirth().toString());
        snapshot.put("addressLine1", investor.getAddressLine1());
        snapshot.put("addressLine2", investor.getAddressLine2());
        snapshot.put("city", investor.getCity());
        snapshot.put("state", investor.getState());
        snapshot.put("postalCode", investor.getPostalCode());
        snapshot.put("mobileNumber", investor.getMobileNumber());
        snapshot.put("email", investor.getEmail());
        try {
            return SNAPSHOT_MAPPER.writeValueAsString(snapshot);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize onboarding snapshot", ex);
        }
    }

    @GetMapping("/{investorId}/documents")
    public List<UploadedInvestorDocumentResponse> listDocuments(
            @PathVariable UUID investorId,
            Authentication auth
    ) {
        return investorDocumentService.listDocuments(investorId, actorId(auth));
    }

    @PutMapping(value = "/{investorId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InvestorDocumentUploadResponse uploadDocument(
            @PathVariable UUID investorId,
            @RequestParam("documentType") String documentType,
            @RequestParam("file") MultipartFile file,
            Authentication auth
    ) {
        return investorDocumentService.uploadDocument(investorId, documentType, file, actorId(auth));
    }

    /**
     * Returns investors for a postal code, but only those visible to the authenticated
     * distributor. ADMIN role bypasses the ownership filter. Scoped via
     * {@link InvestorService#findByPostalCode(String, java.util.UUID, com.platizio.wealthtech.domain.DistributorRole)}
     * which also filters out soft-deleted rows.
     */
    @GetMapping("/by-postal-code/{postalCode}")
    public List<Investor> getInvestorsByPostalCode(@PathVariable String postalCode, Authentication auth) {
        JwtAuthPrincipal principal = actorPrincipal(auth);
        return investorService.findByPostalCode(postalCode, principal.getDistributorId(), principal.getRole());
    }

    private static JwtAuthPrincipal actorPrincipal(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof JwtAuthPrincipal p)) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }
        return p;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR','SUB_DISTRIBUTOR')")
    @DeleteMapping("/{investorId}")
    public java.util.Map<String, Object> deleteInvestor(@PathVariable UUID investorId, Authentication auth) {
        return investorService.deleteInvestor(investorId, actorId(auth));
    }

    private UUID actorId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof JwtAuthPrincipal)) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }
        return ((JwtAuthPrincipal) auth.getPrincipal()).getDistributorId();
    }
}
