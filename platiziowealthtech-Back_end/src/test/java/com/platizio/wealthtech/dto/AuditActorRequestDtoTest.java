package com.platizio.wealthtech.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AuditActorRequestDtoTest {

    @Test
    void leadRequestBodiesDoNotAcceptActorId() {
        assertThat(recordComponentNames(LeadAssignRequest.class)).doesNotContain("actorId");
        assertThat(recordComponentNames(LeadCreateWithDistributorRequest.class)).doesNotContain("actorId");
        assertThat(recordComponentNames(LeadStatusUpdateRequest.class)).doesNotContain("actorId");
    }

    @Test
    void leadInteractionRequestDoesNotAcceptDistributorId() {
        assertThat(recordComponentNames(LeadInteractionRequest.class)).doesNotContain("distributorId");
    }

    private String[] recordComponentNames(Class<? extends Record> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toArray(String[]::new);
    }
}
