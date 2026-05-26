package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class ReadOnlyTransactionAnnotationTest {

    @Test
    void multiQueryReadPathsAreReadOnlyTransactions() throws Exception {
        assertReadOnly(InvestorService.class, "listVisibleToRequesterPage", UUID.class, UUID.class, int.class, int.class);
        assertReadOnly(LeadService.class, "getById", UUID.class);
        assertReadOnly(DistributorService.class, "findSubDistributors", UUID.class);
    }

    private void assertReadOnly(Class<?> serviceClass, String methodName, Class<?>... parameterTypes) throws Exception {
        Transactional transactional = serviceClass
                .getMethod(methodName, parameterTypes)
                .getAnnotation(Transactional.class);

        assertThat(transactional)
                .as("%s.%s should be transactional", serviceClass.getSimpleName(), methodName)
                .isNotNull();
        assertThat(transactional.readOnly())
                .as("%s.%s should be read-only", serviceClass.getSimpleName(), methodName)
                .isTrue();
    }
}
