package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.AuditEvent;
import com.platizio.wealthtech.dto.AuditLogResponse;
import com.platizio.wealthtech.repository.AuditEventRepository;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class AuditServiceQueryTest {

    @Test
    void findAuditLogsUsesFiltersAndMapsResponses() {
        UUID actorId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-05-19T01:00:00Z");
        OffsetDateTime fromDate = OffsetDateTime.parse("2026-05-01T00:00:00Z");
        OffsetDateTime toDate = OffsetDateTime.parse("2026-05-19T23:59:59Z");
        AuditEvent event = new AuditEvent();
        ReflectionTestUtils.setField(event, "id", eventId);
        ReflectionTestUtils.setField(event, "createdAt", createdAt);
        event.setEntityType("LEAD");
        event.setEntityId(entityId);
        event.setActionType("LEAD_UPDATED");
        event.setActorId(actorId);
        event.setDetailsJson("{\"detailsUpdated\":true}");
        CapturedFilters capturedFilters = new CapturedFilters();
        AuditService auditService = new AuditService(repository(capturedFilters, event));

        Page<AuditLogResponse> response = auditService.findAuditLogs(
                actorId,
                "LEAD_UPDATED",
                fromDate,
                toDate,
                -1,
                1_000
        );

        assertThat(capturedFilters.actorId).isEqualTo(actorId);
        assertThat(capturedFilters.eventType).isEqualTo("LEAD_UPDATED");
        assertThat(capturedFilters.fromDate).isEqualTo(fromDate);
        assertThat(capturedFilters.toDate).isEqualTo(toDate);
        assertThat(capturedFilters.pageable.getPageNumber()).isZero();
        assertThat(capturedFilters.pageable.getPageSize()).isEqualTo(100);
        assertThat(capturedFilters.pageable.getSort().toString()).contains("createdAt: DESC");
        assertThat(response.getContent()).singleElement().satisfies(log -> {
            assertThat(log.id()).isEqualTo(eventId);
            assertThat(log.entityType()).isEqualTo("LEAD");
            assertThat(log.entityId()).isEqualTo(entityId);
            assertThat(log.eventType()).isEqualTo("LEAD_UPDATED");
            assertThat(log.actorId()).isEqualTo(actorId);
            assertThat(log.detailsJson()).isEqualTo("{\"detailsUpdated\":true}");
            assertThat(log.createdAt()).isEqualTo(createdAt);
        });
    }

    private AuditEventRepository repository(CapturedFilters capturedFilters, AuditEvent event) {
        return (AuditEventRepository) Proxy.newProxyInstance(
                AuditEventRepository.class.getClassLoader(),
                new Class<?>[]{AuditEventRepository.class},
                (proxy, method, args) -> {
                    if ("findByFilters".equals(method.getName())) {
                        capturedFilters.actorId = (UUID) args[0];
                        capturedFilters.eventType = (String) args[1];
                        capturedFilters.fromDate = (OffsetDateTime) args[2];
                        capturedFilters.toDate = (OffsetDateTime) args[3];
                        capturedFilters.pageable = (Pageable) args[4];
                        return new PageImpl<>(List.of(event), capturedFilters.pageable, 1);
                    }
                    return defaultValue(method.getReturnType());
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

    private static class CapturedFilters {
        private UUID actorId;
        private String eventType;
        private OffsetDateTime fromDate;
        private OffsetDateTime toDate;
        private Pageable pageable;
    }
}
