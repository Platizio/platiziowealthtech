package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.InvestorLead;
import com.platizio.wealthtech.repository.InvestorLeadRepository;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.annotations.SQLRestriction;
import org.junit.jupiter.api.Test;

class LeadSoftDeleteTest {

    @Test
    void leadEntityFiltersSoftDeletedRows() {
        SQLRestriction restriction = InvestorLead.class.getAnnotation(SQLRestriction.class);

        assertThat(restriction).isNotNull();
        assertThat(restriction.value()).isEqualTo("is_deleted = false");
    }

    @Test
    void deleteLeadMarksDeletedInsteadOfHardDeleting() {
        UUID leadId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        InvestorLead lead = new InvestorLead();
        AtomicReference<InvestorLead> savedLead = new AtomicReference<>();
        AtomicBoolean hardDeleteCalled = new AtomicBoolean(false);
        AtomicReference<String> auditDetails = new AtomicReference<>();
        LeadService leadService = new LeadService(
                leadRepository(lead, savedLead, hardDeleteCalled),
                null,
                new CapturingAuditService(auditDetails)
        );

        leadService.deleteLead(leadId, actorId);

        assertThat(hardDeleteCalled).isFalse();
        assertThat(savedLead.get()).isSameAs(lead);
        assertThat(savedLead.get().getIsDeleted()).isTrue();
        assertThat(savedLead.get().getDeletedAt()).isNotNull();
        assertThat(auditDetails.get()).isEqualTo("{\"softDeleted\":true,\"reason\":\"User requested deletion\"}");
    }

    private InvestorLeadRepository leadRepository(
            InvestorLead lead,
            AtomicReference<InvestorLead> savedLead,
            AtomicBoolean hardDeleteCalled
    ) {
        return (InvestorLeadRepository) Proxy.newProxyInstance(
                InvestorLeadRepository.class.getClassLoader(),
                new Class<?>[]{InvestorLeadRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.of(lead);
                    case "save" -> {
                        savedLead.set((InvestorLead) args[0]);
                        yield args[0];
                    }
                    case "delete", "deleteById", "deleteAll" -> {
                        hardDeleteCalled.set(true);
                        yield null;
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
            assertThat(actionType).isEqualTo("DELETED");
            auditDetails.set(detailsJson);
        }
    }
}
