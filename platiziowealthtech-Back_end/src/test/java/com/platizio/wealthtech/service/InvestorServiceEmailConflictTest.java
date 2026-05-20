package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platizio.wealthtech.common.ConflictException;
import com.platizio.wealthtech.domain.Investor;
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
                .isInstanceOf(ConflictException.class)
                .hasMessage("An investor with this email is already registered");
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
                null
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
}
