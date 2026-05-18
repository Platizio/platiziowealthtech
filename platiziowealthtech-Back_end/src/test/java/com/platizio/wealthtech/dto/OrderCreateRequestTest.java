package com.platizio.wealthtech.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OrderCreateRequestTest {

    @Test
    void orderCreateRequestDoesNotAcceptDistributorId() {
        assertThat(recordComponentNames(OrderCreateRequest.class)).doesNotContain("distributorId");
    }

    @Test
    void bulkOrderCreateRequestDoesNotAcceptDistributorId() {
        assertThat(recordComponentNames(BulkOrderCreateRequest.class)).doesNotContain("distributorId");
    }

    private String[] recordComponentNames(Class<? extends Record> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toArray(String[]::new);
    }
}
