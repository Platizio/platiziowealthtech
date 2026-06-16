package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.dto.InvestorKycFormResponse;
import com.platizio.wealthtech.dto.KycFormCreateRequest;
import com.platizio.wealthtech.dto.KycFormUpdateRequest;
import com.platizio.wealthtech.security.JwtAuthPrincipal;
import com.platizio.wealthtech.service.InvestorKycFormService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/investors/{investorId}/kyc-form")
@Tag(name = "Investor KYC Form", description = "Cybrilla POA KYC Forms (modify) workflow: create, complete, sign and submit a KYC modification.")
public class InvestorKycFormController {

    private final InvestorKycFormService kycFormService;

    public InvestorKycFormController(InvestorKycFormService kycFormService) {
        this.kycFormService = kycFormService;
    }

    @Operation(summary = "Get the latest KYC modify form for an investor")
    @GetMapping
    public InvestorKycFormResponse getLatest(@PathVariable UUID investorId, Authentication auth) {
        return kycFormService.getLatestForm(investorId, actorId(auth));
    }

    @Operation(summary = "Start a KYC modify form (type=modify) for an already-verified investor")
    @PostMapping
    public InvestorKycFormResponse create(
            @PathVariable UUID investorId,
            @RequestBody(required = false) KycFormCreateRequest request,
            Authentication auth
    ) {
        String callbackBaseUrl = request == null ? null : request.callbackBaseUrl();
        return kycFormService.createModifyForm(investorId, actorId(auth), callbackBaseUrl);
    }

    @Operation(summary = "Refresh a KYC modify form from Cybrilla")
    @PostMapping("/{kycFormId}/refresh")
    public InvestorKycFormResponse refresh(
            @PathVariable UUID investorId,
            @PathVariable String kycFormId,
            Authentication auth
    ) {
        return kycFormService.refreshForm(investorId, kycFormId, actorId(auth));
    }

    @Operation(summary = "Provide the fields still needed to complete a KYC modify form")
    @PatchMapping("/{kycFormId}")
    public InvestorKycFormResponse update(
            @PathVariable UUID investorId,
            @PathVariable String kycFormId,
            @RequestBody KycFormUpdateRequest request,
            Authentication auth
    ) {
        return kycFormService.updateForm(investorId, kycFormId, request, actorId(auth));
    }

    @Operation(summary = "Upload the investor's wet-signature photocopy to a KYC modify form")
    @PostMapping(value = "/{kycFormId}/signature", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InvestorKycFormResponse uploadSignature(
            @PathVariable UUID investorId,
            @PathVariable String kycFormId,
            @RequestParam("file") MultipartFile file,
            Authentication auth
    ) {
        return kycFormService.uploadSignature(investorId, kycFormId, file, actorId(auth));
    }

    @Operation(summary = "Retry the Digilocker proof-details fetch (only when its status is 'failed')")
    @PostMapping("/{kycFormId}/retry-proof-fetch")
    public InvestorKycFormResponse retryProofFetch(
            @PathVariable UUID investorId,
            @PathVariable String kycFormId,
            Authentication auth
    ) {
        return kycFormService.retryProofDetailsFetch(investorId, kycFormId, actorId(auth));
    }

    @Operation(summary = "Simulate Digilocker proof fetch (local sandbox when kyc_forms API is unavailable)")
    @PostMapping("/{kycFormId}/sandbox/simulate-proof-fetch")
    public InvestorKycFormResponse simulateProofFetch(
            @PathVariable UUID investorId,
            @PathVariable String kycFormId,
            Authentication auth
    ) {
        return kycFormService.simulateProofFetch(investorId, kycFormId, actorId(auth));
    }

    @Operation(summary = "Simulate eSign completion (local sandbox when kyc_forms API is unavailable)")
    @PostMapping("/{kycFormId}/sandbox/simulate-esign")
    public InvestorKycFormResponse simulateEsign(
            @PathVariable UUID investorId,
            @PathVariable String kycFormId,
            Authentication auth
    ) {
        return kycFormService.simulateEsign(investorId, kycFormId, actorId(auth));
    }

    private UUID actorId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof JwtAuthPrincipal)) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }
        return ((JwtAuthPrincipal) auth.getPrincipal()).getDistributorId();
    }
}
