package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        investor.setDistributorId(actorId);
        AtomicReference<Investor> savedInvestor = new AtomicReference<>();
        AtomicBoolean hardDeleteCalled = new AtomicBoolean(false);
        AtomicReference<String> auditDetails = new AtomicReference<>();
        RecordingDistributorService distributorService = new RecordingDistributorService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR, null);

        InvestorService investorService = new InvestorService(
                investorRepository(investor, savedInvestor, hardDeleteCalled),
                null,
                distributorService,
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

    @Test
    void deleteInvestorRejectsAnotherDistributorInvestor() {
        UUID investorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID ownerDistributorId = UUID.randomUUID();
        Investor investor = new Investor();
        investor.setDistributorId(ownerDistributorId);
        AtomicReference<Investor> savedInvestor = new AtomicReference<>();
        AtomicBoolean hardDeleteCalled = new AtomicBoolean(false);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR, null);

        InvestorService investorService = new InvestorService(
                investorRepository(investor, savedInvestor, hardDeleteCalled),
                null,
                distributorService,
                new CapturingAuditService(new AtomicReference<>()),
                null
        );

        assertThatThrownBy(() -> investorService.deleteInvestor(investorId, actorId))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessage("Cannot delete another distributor's investor");

        assertThat(savedInvestor.get()).isNull();
        assertThat(hardDeleteCalled).isFalse();
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

    private static class RecordingDistributorService extends DistributorService {
        private final Map<UUID, Distributor> distributors = new HashMap<>();

        RecordingDistributorService() {
            super(null, null, null);
        }

        void put(UUID distributorId, DistributorRole role, UUID masterDistributorId) {
            Distributor distributor = new Distributor();
            distributor.setRole(role);
            distributor.setMasterDistributorId(masterDistributorId);
            distributors.put(distributorId, distributor);
        }

        @Override
        public Distributor getDistributor(UUID distributorId) {
            return Optional.ofNullable(distributors.get(distributorId))
                    .orElseThrow(() -> new AssertionError("Unexpected distributor lookup: " + distributorId));
        }
    }
}
