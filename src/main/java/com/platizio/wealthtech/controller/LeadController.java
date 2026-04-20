package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.InvestorLead;
import com.platizio.wealthtech.domain.LeadInteraction;
import com.platizio.wealthtech.dto.LeadAssignRequest;
import com.platizio.wealthtech.dto.LeadCreateRequest;
import com.platizio.wealthtech.dto.LeadCreateWithDistributorRequest;
import com.platizio.wealthtech.dto.LeadInteractionRequest;
import com.platizio.wealthtech.dto.LeadStatusUpdateRequest;
import com.platizio.wealthtech.service.LeadService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/leads")
public class LeadController {

    private final LeadService leadService;

    public LeadController(LeadService leadService) {
        this.leadService = leadService;
    }

    @GetMapping
    public List<InvestorLead> listAll() {
        return leadService.listAll();
    }

    @GetMapping("/{leadId}")
    public InvestorLead getById(@PathVariable UUID leadId) {
        return leadService.getById(leadId);
    }

    @PostMapping
    public InvestorLead createLead(@Valid @RequestBody LeadCreateRequest request) {
        return leadService.createLead(request);
    }

    @PostMapping("/assigned")
    public InvestorLead createLeadWithDistributor(@Valid @RequestBody LeadCreateWithDistributorRequest request) {
        return leadService.createLeadWithDistributor(request);
    }

    @GetMapping("/distributor/{distributorId}")
    public List<InvestorLead> listByDistributor(@PathVariable UUID distributorId) {
        return leadService.listByDistributor(distributorId);
    }

    @PostMapping("/{leadId}/assign")
    public InvestorLead assignLead(@PathVariable UUID leadId, @Valid @RequestBody LeadAssignRequest request) {
        return leadService.assignLead(leadId, request);
    }

    @PatchMapping("/{leadId}/status")
    public InvestorLead updateStatus(@PathVariable UUID leadId, @Valid @RequestBody LeadStatusUpdateRequest request) {
        return leadService.updateStatus(leadId, request);
    }

    @PostMapping("/{leadId}/interactions")
    public LeadInteraction addInteraction(@PathVariable UUID leadId, @Valid @RequestBody LeadInteractionRequest request) {
        return leadService.addInteraction(leadId, request);
    }

    @DeleteMapping("/{leadId}/{actorId}")
    public void deleteLead(@PathVariable UUID leadId, @PathVariable UUID actorId) {
        leadService.deleteLead(leadId, actorId);
    }
}