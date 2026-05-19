package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.InvestorLead;
import com.platizio.wealthtech.domain.LeadInteraction;
import com.platizio.wealthtech.dto.LeadAssignRequest;
import com.platizio.wealthtech.dto.LeadCreateRequest;
import com.platizio.wealthtech.dto.LeadCreateWithDistributorRequest;
import com.platizio.wealthtech.dto.LeadInteractionRequest;
import com.platizio.wealthtech.dto.LeadResponse;
import com.platizio.wealthtech.dto.LeadStatusUpdateRequest;
import com.platizio.wealthtech.dto.UpdateLeadRequest;
import com.platizio.wealthtech.security.JwtAuthPrincipal;
import com.platizio.wealthtech.service.LeadService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/leads")
public class LeadController {

    private final LeadService leadService;

    public LeadController(LeadService leadService) {
        this.leadService = leadService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public List<InvestorLead> listAll() {
        return leadService.listAll();
    }

    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR','SUB_DISTRIBUTOR')")
    @GetMapping("/{leadId}")
    public InvestorLead getById(@PathVariable UUID leadId) {
        return leadService.getById(leadId);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR','SUB_DISTRIBUTOR')")
    @PostMapping
    public InvestorLead createLead(@Valid @RequestBody LeadCreateRequest request) {
        return leadService.createLead(request);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR','SUB_DISTRIBUTOR')")
    @PostMapping("/assigned")
    public InvestorLead createLeadWithDistributor(
            @Valid @RequestBody LeadCreateWithDistributorRequest request,
            Authentication auth
    ) {
        return leadService.createLeadWithDistributor(request, actorId(auth));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR','SUB_DISTRIBUTOR')")
    @GetMapping("/distributor/{distributorId}")
    public List<InvestorLead> listByDistributor(@PathVariable UUID distributorId) {
        return leadService.listByDistributor(distributorId);
    }

    @PreAuthorize("hasRole('DISTRIBUTOR')")
    @PutMapping("/{leadId}")
    public LeadResponse updateLead(
            @PathVariable UUID leadId,
            @Valid @RequestBody UpdateLeadRequest request,
            Authentication auth
    ) {
        return leadService.updateLead(leadId, request, actorId(auth));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR','SUB_DISTRIBUTOR')")
    @PostMapping("/{leadId}/assign")
    public InvestorLead assignLead(
            @PathVariable UUID leadId,
            @Valid @RequestBody LeadAssignRequest request,
            Authentication auth
    ) {
        return leadService.assignLead(leadId, request, actorId(auth));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR','SUB_DISTRIBUTOR')")
    @PatchMapping("/{leadId}/status")
    public InvestorLead updateStatus(
            @PathVariable UUID leadId,
            @Valid @RequestBody LeadStatusUpdateRequest request,
            Authentication auth
    ) {
        return leadService.updateStatus(leadId, request, actorId(auth));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR','SUB_DISTRIBUTOR')")
    @PostMapping("/{leadId}/interactions")
    public LeadInteraction addInteraction(
            @PathVariable UUID leadId,
            @Valid @RequestBody LeadInteractionRequest request,
            Authentication auth
    ) {
        return leadService.addInteraction(leadId, request, actorId(auth));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{leadId}")
    public void deleteLead(@PathVariable UUID leadId, Authentication auth) {
        leadService.deleteLead(leadId, actorId(auth));
    }

    private UUID actorId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof JwtAuthPrincipal)) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }
        return ((JwtAuthPrincipal) auth.getPrincipal()).getDistributorId();
    }
}
