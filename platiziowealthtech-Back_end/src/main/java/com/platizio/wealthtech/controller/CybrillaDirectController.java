package com.platizio.wealthtech.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.service.CybrillaDirectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pass-through to Cybrilla POA and Finprim catalogue APIs. Returns raw provider JSON.
 */
@RestController
@RequestMapping("/api/v1/cybrilla")
@Tag(name = "Cybrilla Direct", description = "Raw Cybrilla/Finprim API proxy (no local PAN simulation guards)")
public class CybrillaDirectController {

    private final CybrillaDirectService cybrillaDirectService;

    public CybrillaDirectController(CybrillaDirectService cybrillaDirectService) {
        this.cybrillaDirectService = cybrillaDirectService;
    }

    @Operation(summary = "Create POA pre-verification and return Cybrilla's raw response")
    @PostMapping("/pre-verifications")
    public JsonNode createPreVerification(
            @RequestBody Map<String, Object> body,
            @RequestParam(defaultValue = "true") boolean waitForCompletion
    ) {
        return cybrillaDirectService.createPreVerification(body, waitForCompletion);
    }

    @Operation(summary = "Fetch POA pre-verification by pv_ id")
    @GetMapping("/pre-verifications/{preVerificationId}")
    public JsonNode fetchPreVerification(@PathVariable String preVerificationId) {
        return cybrillaDirectService.fetchPreVerification(preVerificationId);
    }

    @Operation(summary = "Fetch one live Finprim catalogue page (default POA MF scheme plans)")
    @GetMapping("/fund-schemes")
    public JsonNode fetchFundSchemes(
            @RequestParam(required = false) String endpoint,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return cybrillaDirectService.fetchCataloguePage(endpoint, page, size);
    }

    @Operation(summary = "Fetch POA-orderable MF scheme plans (GET /v2/mf_scheme_plans/cybrillapoa)")
    @GetMapping("/mf-scheme-plans")
    public JsonNode fetchMfSchemePlans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return cybrillaDirectService.fetchCataloguePage("poa-mf", page, size);
    }
}
