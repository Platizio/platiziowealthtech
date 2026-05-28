package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platizio.wealthtech.common.DuplicateResourceException;
import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorRelationshipType;
import com.platizio.wealthtech.dto.InvestorCreateRequest;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InvestorServiceEmailConflictTest {

    @Test
    void createInvestorRejectsDuplicateEmailBeforeSaving() {
        AtomicBoolean saveCalled = new AtomicBoolean(false);
        InvestorService investorService = new InvestorService(
                investorRepositoryWithDuplicateEmail(saveCalled),
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> investorService.createInvestor(request()))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("An investor with this email address already exists");
        assertThat(saveCalled).isFalse();
    }

    @Test
    void createInvestorRejectsDuplicatePanAsConflictBeforeSaving() {
        AtomicBoolean saveCalled = new AtomicBoolean(false);
        InvestorService investorService = new InvestorService(
                investorRepositoryWithDuplicatePan(saveCalled),
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> investorService.createInvestor(request()))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("An investor with this PAN already exists");
        assertThat(saveCalled).isFalse();
    }

    @Test
    void createMinorRequiresGuardianPanOrGuardianInvestorLink() {
        AtomicBoolean saveCalled = new AtomicBoolean(false);
        InvestorService investorService = new InvestorService(
                investorRepositoryWithoutDuplicates(saveCalled),
                null,
                new FixedDistributorService(),
                null,
                null
        );

        assertThatThrownBy(() -> investorService.createInvestor(minorRequestWithoutGuardian()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Minor folios require guardian PAN or guardian investor link");
        assertThat(saveCalled).isFalse();
    }

    private InvestorRepository investorRepositoryWithDuplicateEmail(AtomicBoolean saveCalled) {
        return (InvestorRepository) Proxy.newProxyInstance(
                InvestorRepository.class.getClassLoader(),
                new Class<?>[]{InvestorRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByPan" -> Optional.empty();
                    case "findByEmail" -> Optional.of(new Investor());
                    case "save" -> {
                        saveCalled.set(true);
                        yield args[0];
                    }
                    case "findAll", "findByDistributorId" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private InvestorRepository investorRepositoryWithDuplicatePan(AtomicBoolean saveCalled) {
        return (InvestorRepository) Proxy.newProxyInstance(
                InvestorRepository.class.getClassLoader(),
                new Class<?>[]{InvestorRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByPan" -> Optional.of(new Investor());
                    case "findByEmail" -> Optional.empty();
                    case "save" -> {
                        saveCalled.set(true);
                        yield args[0];
                    }
                    case "findAll", "findByDistributorId" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private InvestorCreateRequest request() {
        return new InvestorCreateRequest(
                UUID.randomUUID(),
                "Jane Investor",
                "9876543210",
                "jane@example.com",
                "ABCDE1234F",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private InvestorCreateRequest minorRequestWithoutGuardian() {
        return new InvestorCreateRequest(
                UUID.randomUUID(),
                "Junior Investor",
                "9876543210",
                "junior@example.com",
                "PQRST1234F",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "Family",
                InvestorRelationshipType.MINOR,
                null,
                null,
                null
        );
    }

    private InvestorRepository investorRepositoryWithoutDuplicates(AtomicBoolean saveCalled) {
        return (InvestorRepository) Proxy.newProxyInstance(
                InvestorRepository.class.getClassLoader(),
                new Class<?>[]{InvestorRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByPan", "findByEmail" -> Optional.empty();
                    case "save" -> {
                        saveCalled.set(true);
                        yield args[0];
                    }
                    case "findAll", "findByDistributorId" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static class FixedDistributorService extends DistributorService {
        FixedDistributorService() {
            super(null, null, null);
        }

        @Override
        public Distributor getDistributor(UUID distributorId) {
            return new Distributor();
        }
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
}
