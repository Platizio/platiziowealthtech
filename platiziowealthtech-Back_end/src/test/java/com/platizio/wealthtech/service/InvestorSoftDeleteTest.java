package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.annotations.SQLRestriction;
import org.junit.jupiter.api.Test;

class InvestorSoftDeleteTest {

    @Test
    void investorEntityFiltersSoftDeletedRows() {
        SQLRestriction restriction = Investor.class.getAnnotation(SQLRestriction.class);

        assertThat(restriction).isNotNull();
        assertThat(restriction.value()).isEqualTo("is_deleted = false");
    }

    @Test
    void deleteInvestorMarksDeletedInsteadOfHardDeleting() {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Investor investor = new Investor();
        AtomicReference<Investor> savedInvestor = new AtomicReference<>();
        AtomicBoolean hardDeleteCalled = new AtomicBoolean(false);
        AtomicReference<String> auditDetails = new AtomicReference<>();

        InvestorService investorService = new InvestorService(
                investorRepository(investor, savedInvestor, hardDeleteCalled),
                null,
                null,
                new CapturingAuditService(auditDetails),
                null
        );

        investorService.deleteInvestor(investorId, actorId);

        assertThat(hardDeleteCalled).isFalse();
        assertThat(savedInvestor.get()).isSameAs(investor);
        assertThat(savedInvestor.get().getIsDeleted()).isTrue();
        assertThat(savedInvestor.get().getDeletedAt()).isNotNull();
        assertThat(auditDetails.get()).isEqualTo("{\"softDeleted\":true}");
    }

    private InvestorRepository investorRepository(
            Investor investor,
            AtomicReference<Investor> savedInvestor,
            AtomicBoolean hardDeleteCalled
    ) {
        return (InvestorRepository) Proxy.newProxyInstance(
                InvestorRepository.class.getClassLoader(),
                new Class<?>[]{InvestorRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.of(investor);
                    case "save" -> {
                        savedInvestor.set((Investor) args[0]);
                        yield args[0];
                    }
                    case "delete", "deleteById", "deleteAll" -> {
                        hardDeleteCalled.set(true);
                        yield null;
                    }
                    case "findAll", "findByDistributorId" -> List.of();
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
            assertThat(entityType).isEqualTo("INVESTOR");
            assertThat(actionType).isEqualTo("DELETED");
            auditDetails.set(detailsJson);
        }
    }
}
