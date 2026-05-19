package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.InvestorLead;
import com.platizio.wealthtech.domain.LeadStatus;
import java.util.UUID;

public record LeadResponse(
        UUID id,
        String name,
        String email,
        String phone,
        String notes,
        LeadStatus status,
        UUID distributorId
) {
    public static LeadResponse from(InvestorLead lead) {
        return new LeadResponse(
                lead.getId(),
                lead.getProspectName(),
                lead.getEmail(),
                lead.getMobileNumber(),
                lead.getNotes(),
                lead.getStatus(),
                lead.getAssignedDistributorId()
        );
    }
}
