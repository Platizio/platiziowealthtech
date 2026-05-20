package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.dto.InvestorCreateRequest;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InvestorServiceAuditTest {

    @Test
    void createInvestorLogsPanPresenceWithoutPanValue() {
        UUID distributorId = UUID.randomUUID();
        AtomicReference<String> auditDetails = new AtomicReference<>();
        InvestorService investorService = new InvestorService(
                investorRepository(),
                null,
                new FixedDistributorService(distributorId),
                new CapturingAuditService(auditDetails),
                cybrillaClient()
        );

        investorService.createInvestor(new InvestorCreateRequest(
                distributorId,
                "Jane Investor",
                "9876543210",
                "jane@example.com",
                "ABCDE1234F",
                LocalDate.of(1990, 1, 1),
                "Address line 1",
                null,
                "Mumbai",
                "Maharashtra",
                "400001",
                "Sensitive onboarding note"
        ));

        assertThat(auditDetails.get()).isEqualTo("{\"pan_provided\":true}");
        assertThat(auditDetails.get())
                .doesNotContain("ABCDE1234F")
                .doesNotContain("1234")
                .doesNotContain("9876543210")
                .doesNotContain("jane@example.com")
                .doesNotContain("Sensitive onboarding note");
    }

    private InvestorRepository investorRepository() {
        return (InvestorRepository) Proxy.newProxyInstance(
                InvestorRepository.class.getClassLoader(),
                new Class<?>[]{InvestorRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByPan", "findByEmail" -> Optional.empty();
                    case "save" -> args[0];
                    case "findAll", "findByDistributorId" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private CybrillaClient cybrillaClient() {
        return (CybrillaClient) Proxy.newProxyInstance(
                CybrillaClient.class.getClassLoader(),
                new Class<?>[]{CybrillaClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createInvestorProfile" -> "cybrilla-investor-1";
                    case "fetchProductSchemes" -> List.of();
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

    private static class FixedDistributorService extends DistributorService {

        private final UUID distributorId;

        FixedDistributorService(UUID distributorId) {
            super(null, null, null);
            this.distributorId = distributorId;
        }

        @Override
        public Distributor getDistributor(UUID distributorId) {
            assertThat(distributorId).isEqualTo(this.distributorId);
            Distributor distributor = new Distributor();
            distributor.setRole(com.platizio.wealthtech.domain.DistributorRole.SUB_DISTRIBUTOR);
            return distributor;
        }
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
            assertThat(actionType).isEqualTo("CREATED");
            auditDetails.set(detailsJson);
        }
    }
}
