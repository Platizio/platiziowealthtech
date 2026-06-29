package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.dto.InvestorCreateRequest;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.CybrillaApiException;
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
                null,
                null,
                "Address line 1",
                null,
                "Mumbai",
                "Maharashtra",
                "400001",
                null,
                null,
                null,
                null,
                null,
                "Sensitive onboarding note",
                null, null, null, null, null, null, null, null, null, null, null, null, null
        ));

        assertThat(auditDetails.get()).isEqualTo("{\"pan_provided\":true}");
        assertThat(auditDetails.get())
                .doesNotContain("ABCDE1234F")
                .doesNotContain("1234")
                .doesNotContain("9876543210")
                .doesNotContain("jane@example.com")
                .doesNotContain("Sensitive onboarding note");
    }

    @Test
    void createInvestorKeepsLocalRecordPendingWhenExternalProfileFails() {
        UUID distributorId = UUID.randomUUID();
        AtomicReference<Investor> lastSaved = new AtomicReference<>();
        InvestorService investorService = new InvestorService(
                investorRepository(lastSaved),
                null,
                new FixedDistributorService(distributorId),
                new NoopAuditService(),
                failingCybrillaClient()
        );

        Investor saved = investorService.createInvestor(new InvestorCreateRequest(
                distributorId,
                "Pending Investor",
                "9876543210",
                "pending@example.com",
                "ABCDE1234F",
                LocalDate.of(1990, 1, 1),
                null,
                null,
                "Address line 1",
                null,
                "Mumbai",
                "Maharashtra",
                "400001",
                null,
                null,
                null,
                null,
                null,
                "External verification pending",
                null, null, null, null, null, null, null, null, null, null, null, null, null
        ));

        assertThat(saved).isSameAs(lastSaved.get());
        assertThat(saved.getKycStatus()).isEqualTo(KycStatus.PENDING);
        assertThat(saved.getCybrillaInvestorId()).isNull();
        assertThat(saved.getExternalSyncPending()).isTrue();
        assertThat(saved.getExternalSyncMessage()).contains("Unable to post investor data");
        assertThat(saved.getHouseholdId()).isNotNull();
    }

    @Test
    void createInvestorPersistsIrisScalarsOntoTheInvestor() {
        UUID distributorId = UUID.randomUUID();
        AtomicReference<Investor> lastSaved = new AtomicReference<>();
        InvestorService investorService = new InvestorService(
                investorRepository(lastSaved),
                null,
                new FixedDistributorService(distributorId),
                new NoopAuditService(),
                cybrillaClient()
        );

        investorService.createInvestor(new InvestorCreateRequest(
                distributorId,
                "Rich Investor",
                "9876543210",
                "rich@example.com",
                "ABCDE1234F",
                LocalDate.of(1990, 1, 1),
                null,
                null,
                "Address line 1",
                null,
                "Mumbai",
                "Maharashtra",
                "400001",
                null,
                null,
                null,
                null,
                null,
                null,
                // IRIS rich scalars.
                "single",
                "resident_individual",
                "female",
                "India",
                "India",
                Boolean.FALSE,
                "upto_1lakh",
                "service",
                "salary",
                Boolean.TRUE,
                Boolean.FALSE,
                Boolean.TRUE,
                null
        ));

        Investor saved = lastSaved.get();
        assertThat(saved.getHoldingMode()).isEqualTo("single");
        assertThat(saved.getCategory()).isEqualTo("resident_individual");
        assertThat(saved.getGender()).isEqualTo("female");
        assertThat(saved.getCountryOfBirth()).isEqualTo("India");
        assertThat(saved.getCountryOfCitizenship()).isEqualTo("India");
        assertThat(saved.getTaxResidentOtherCountry()).isFalse();
        assertThat(saved.getAnnualIncome()).isEqualTo("upto_1lakh");
        assertThat(saved.getOccupation()).isEqualTo("service");
        assertThat(saved.getSourceOfWealth()).isEqualTo("salary");
        assertThat(saved.getPep()).isTrue();
        assertThat(saved.getRelativeOfPep()).isFalse();
        assertThat(saved.getDisplayNominees()).isTrue();
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

    private InvestorRepository investorRepository(AtomicReference<Investor> lastSaved) {
        return (InvestorRepository) Proxy.newProxyInstance(
                InvestorRepository.class.getClassLoader(),
                new Class<?>[]{InvestorRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByPan", "findByEmail" -> Optional.empty();
                    case "save" -> {
                        lastSaved.set((Investor) args[0]);
                        yield args[0];
                    }
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
                    case "createInvestorProfile" -> {
                        assertThat(((Investor) args[0]).getHouseholdId()).isNotNull();
                        yield "cybrilla-investor-1";
                    }
                    // B-68: signature changed from List<ProductScheme> to
                    // SchemeFetchResult — return an empty-but-complete result
                    // so any indirect invocation through this proxy still
                    // works (test method doesn't actually hit this path).
                    case "fetchProductSchemes" -> CybrillaClient.SchemeFetchResult.complete(List.of());
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private CybrillaClient failingCybrillaClient() {
        return (CybrillaClient) Proxy.newProxyInstance(
                CybrillaClient.class.getClassLoader(),
                new Class<?>[]{CybrillaClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createInvestorProfile" -> throw new CybrillaApiException("PAN verification unavailable");
                    case "fetchProductSchemes" -> CybrillaClient.SchemeFetchResult.complete(List.of());
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

    private static class NoopAuditService extends AuditService {
        NoopAuditService() {
            super(null);
        }

        @Override
        public void log(String entityType, UUID entityId, String actionType, UUID actorId, String detailsJson) {
        }
    }
}
