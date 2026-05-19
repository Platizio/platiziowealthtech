package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platizio.wealthtech.domain.InvestorLead;
import com.platizio.wealthtech.domain.LeadSource;
import com.platizio.wealthtech.domain.LeadStatus;
import com.platizio.wealthtech.dto.LeadResponse;
import com.platizio.wealthtech.dto.UpdateLeadRequest;
import com.platizio.wealthtech.repository.InvestorLeadRepository;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class LeadUpdateTest {

    @Test
    void updateLeadRejectsLeadAssignedToAnotherDistributor() {
        UUID assignedDistributorId = UUID.randomUUID();
        UUID callerDistributorId = UUID.randomUUID();
        AtomicReference<InvestorLead> savedLead = new AtomicReference<>();
        LeadService leadService = new LeadService(
                leadRepository(lead(assignedDistributorId), savedLead),
                null,
                null
        );

        assertThatThrownBy(() -> leadService.updateLead(
                UUID.randomUUID(),
                new UpdateLeadRequest("Updated Lead", "lead@example.com", "9876543210", "Updated notes", LeadStatus.CONTACTED),
                callerDistributorId
        )).isInstanceOf(AccessDeniedException.class);
        assertThat(savedLead.get()).isNull();
    }

    @Test
    void updateLeadAppliesChangesForAssignedDistributor() {
        UUID assignedDistributorId = UUID.randomUUID();
        InvestorLead lead = lead(assignedDistributorId);
        AtomicReference<InvestorLead> savedLead = new AtomicReference<>();
        AtomicReference<String> auditDetails = new AtomicReference<>();
        LeadService leadService = new LeadService(
                leadRepository(lead, savedLead),
                null,
                new CapturingAuditService(auditDetails)
        );

        LeadResponse response = leadService.updateLead(
                UUID.randomUUID(),
                new UpdateLeadRequest("Updated Lead", "lead@example.com", "9876543210", "Updated notes", LeadStatus.CONTACTED),
                assignedDistributorId
        );

        assertThat(savedLead.get()).isSameAs(lead);
        assertThat(savedLead.get().getProspectName()).isEqualTo("Updated Lead");
        assertThat(savedLead.get().getEmail()).isEqualTo("lead@example.com");
        assertThat(savedLead.get().getMobileNumber()).isEqualTo("9876543210");
        assertThat(savedLead.get().getNotes()).isEqualTo("Updated notes");
        assertThat(savedLead.get().getStatus()).isEqualTo(LeadStatus.CONTACTED);
        assertThat(response.name()).isEqualTo("Updated Lead");
        assertThat(response.status()).isEqualTo(LeadStatus.CONTACTED);
        assertThat(response.distributorId()).isEqualTo(assignedDistributorId);
        assertThat(auditDetails.get()).isEqualTo("{\"detailsUpdated\":true}");
        assertThat(auditDetails.get())
                .doesNotContain("Updated Lead")
                .doesNotContain("lead@example.com")
                .doesNotContain("9876543210")
                .doesNotContain("Updated notes");
    }

    private InvestorLead lead(UUID assignedDistributorId) {
        InvestorLead lead = new InvestorLead();
        lead.setProspectName("Original Lead");
        lead.setMobileNumber("9123456780");
        lead.setSource(LeadSource.MANUAL);
        lead.setStatus(LeadStatus.ASSIGNED);
        lead.setAssignedDistributorId(assignedDistributorId);
        return lead;
    }

    private InvestorLeadRepository leadRepository(InvestorLead lead, AtomicReference<InvestorLead> savedLead) {
        return (InvestorLeadRepository) Proxy.newProxyInstance(
                InvestorLeadRepository.class.getClassLoader(),
                new Class<?>[]{InvestorLeadRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.of(lead);
                    case "save" -> {
                        savedLead.set((InvestorLead) args[0]);
                        yield args[0];
                    }
                    case "findAll", "findByAssignedDistributorId" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        return 0;
    }

    private static class CapturingAuditService extends AuditService {

        private final AtomicReference<String> auditDetails;

        CapturingAuditService(AtomicReference<String> auditDetails) {
            super(null);
            this.auditDetails = auditDetails;
        }

        @Override
        public void log(String entityType, UUID entityId, String actionType, UUID actorId, String detailsJson) {
            assertThat(entityType).isEqualTo("LEAD");
            assertThat(actionType).isEqualTo("LEAD_UPDATED");
            auditDetails.set(detailsJson);
        }
    }
}
