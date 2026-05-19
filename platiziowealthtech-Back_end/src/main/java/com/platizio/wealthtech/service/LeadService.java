package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.*;
import com.platizio.wealthtech.dto.LeadAssignRequest;
import com.platizio.wealthtech.dto.LeadCreateRequest;
import com.platizio.wealthtech.dto.LeadCreateWithDistributorRequest;
import com.platizio.wealthtech.dto.LeadInteractionRequest;
import com.platizio.wealthtech.dto.LeadResponse;
import com.platizio.wealthtech.dto.LeadStatusUpdateRequest;
import com.platizio.wealthtech.dto.UpdateLeadRequest;
import com.platizio.wealthtech.repository.InvestorLeadRepository;
import com.platizio.wealthtech.repository.LeadInteractionRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeadService {

    private final InvestorLeadRepository investorLeadRepository;
    private final LeadInteractionRepository leadInteractionRepository;
    private final AuditService auditService;

    public LeadService(
            InvestorLeadRepository investorLeadRepository,
            LeadInteractionRepository leadInteractionRepository,
            AuditService auditService
    ) {
        this.investorLeadRepository = investorLeadRepository;
        this.leadInteractionRepository = leadInteractionRepository;
        this.auditService = auditService;
    }

    public List<InvestorLead> listByDistributor(UUID distributorId) {
        return investorLeadRepository.findByAssignedDistributorId(distributorId);
    }

    public List<InvestorLead> listAll() {
        return investorLeadRepository.findAll();
    }

    public InvestorLead getById(UUID leadId) {
        return investorLeadRepository.findById(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
    }

    @Transactional
    public InvestorLead updateStatus(UUID leadId, LeadStatusUpdateRequest request, UUID actorId) {
        InvestorLead lead = getById(leadId);
        verifyAssignedToCaller(lead, actorId);
        lead.setStatus(request.status());
        if (request.notes() != null) lead.setNotes(request.notes());
        InvestorLead saved = investorLeadRepository.save(lead);
        auditService.log("LEAD", saved.getId(), "STATUS_UPDATED", actorId,
                "{\"status\":\"" + request.status() + "\"}");
        return saved;
    }

    @Transactional
    public InvestorLead createLead(LeadCreateRequest request) {
        InvestorLead lead = new InvestorLead();
        lead.setProspectName(request.prospectName());
        lead.setMobileNumber(request.mobileNumber());
        lead.setEmail(request.email());
        lead.setCity(request.city());
        lead.setStateName(request.stateName());
        lead.setSource(request.source());
        lead.setNotes(request.notes());
        return investorLeadRepository.save(lead);
    }

    @Transactional
    public InvestorLead createLeadWithDistributor(LeadCreateWithDistributorRequest request, UUID actorId) {
        InvestorLead lead = new InvestorLead();
        lead.setProspectName(request.prospectName());
        lead.setMobileNumber(request.mobileNumber());
        lead.setEmail(request.email());
        lead.setCity(request.city());
        lead.setStateName(request.stateName());
        lead.setSource(request.source());
        lead.setNotes(request.notes());
        lead.setAssignedDistributorId(request.distributorId());
        lead.setStatus(LeadStatus.ASSIGNED);
        InvestorLead saved = investorLeadRepository.save(lead);
        auditService.log("LEAD", saved.getId(), "LEAD_ASSIGNED", actorId,
                "{\"assignedDistributorId\":\"" + request.distributorId() + "\"}");
        return saved;
    }

    @Transactional
    public InvestorLead assignLead(UUID leadId, LeadAssignRequest request, UUID actorId) {
        InvestorLead lead = investorLeadRepository.findById(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
        lead.setAssignedDistributorId(request.distributorId());
        lead.setStatus(LeadStatus.ASSIGNED);
        InvestorLead saved = investorLeadRepository.save(lead);
        auditService.log("LEAD", saved.getId(), "LEAD_ASSIGNED", actorId,
                "{\"assignedDistributorId\":\"" + request.distributorId() + "\"}");
        return saved;
    }

    @Transactional
    public LeadResponse updateLead(UUID leadId, UpdateLeadRequest request, UUID principalId) {
        InvestorLead lead = getById(leadId);
        verifyAssignedToCaller(lead, principalId);
        lead.setProspectName(request.name());
        lead.setEmail(request.email());
        if (request.phone() != null) {
            lead.setMobileNumber(request.phone());
        }
        lead.setNotes(request.notes());
        if (request.status() != null) {
            lead.setStatus(request.status());
        }
        InvestorLead saved = investorLeadRepository.save(lead);
        auditService.log("LEAD", saved.getId(), "LEAD_UPDATED", principalId, "{\"detailsUpdated\":true}");
        return LeadResponse.from(saved);
    }

    @Transactional
    public LeadInteraction addInteraction(UUID leadId, LeadInteractionRequest request, UUID actorId) {
        InvestorLead lead = investorLeadRepository.findById(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
        if (lead.getAssignedDistributorId() == null) {
            throw new IllegalStateException("Lead must be assigned before interactions are recorded");
        }
        verifyAssignedToCaller(lead, actorId);

        LeadInteraction interaction = new LeadInteraction();
        interaction.setLeadId(leadId);
        interaction.setDistributorId(actorId);
        interaction.setCommentText(request.commentText());
        interaction.setInteractionType(request.interactionType());
        return leadInteractionRepository.save(interaction);
    }

    @Transactional
    public void deleteLead(UUID leadId, UUID actorId) {
        InvestorLead lead = investorLeadRepository.findById(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
        lead.setIsDeleted(true);
        lead.setDeletedAt(LocalDateTime.now());
        investorLeadRepository.save(lead);
        auditService.log("LEAD", leadId, "DELETED", actorId, "{\"softDeleted\":true,\"reason\":\"User requested deletion\"}");
    }

    private void verifyAssignedToCaller(InvestorLead lead, UUID callerDistributorId) {
        if (!callerDistributorId.equals(lead.getAssignedDistributorId())) {
            throw new AccessDeniedException("Cannot modify another distributor's lead");
        }
    }
}
