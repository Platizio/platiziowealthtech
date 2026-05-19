package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.dto.AuditLogResponse;
import com.platizio.wealthtech.service.AuditService;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;

class AuditControllerTest {

    @Test
    void auditSearchEndpointRequiresAdminRole() throws NoSuchMethodException {
        Method method = AuditController.class.getMethod(
                "searchAuditLogs",
                UUID.class,
                String.class,
                OffsetDateTime.class,
                OffsetDateTime.class,
                int.class,
                int.class
        );

        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void auditSearchForwardsFiltersToService() {
        RecordingAuditService auditService = new RecordingAuditService();
        AuditController controller = new AuditController(auditService);
        UUID actorId = UUID.randomUUID();
        OffsetDateTime fromDate = OffsetDateTime.parse("2026-05-01T00:00:00Z");
        OffsetDateTime toDate = OffsetDateTime.parse("2026-05-19T00:00:00Z");

        Page<AuditLogResponse> response = controller.searchAuditLogs(
                actorId,
                "LEAD_UPDATED",
                fromDate,
                toDate,
                2,
                25
        );

        assertThat(response.getTotalElements()).isZero();
        assertThat(auditService.actorId).isEqualTo(actorId);
        assertThat(auditService.eventType).isEqualTo("LEAD_UPDATED");
        assertThat(auditService.fromDate).isEqualTo(fromDate);
        assertThat(auditService.toDate).isEqualTo(toDate);
        assertThat(auditService.page).isEqualTo(2);
        assertThat(auditService.size).isEqualTo(25);
    }

    private static class RecordingAuditService extends AuditService {

        private UUID actorId;
        private String eventType;
        private OffsetDateTime fromDate;
        private OffsetDateTime toDate;
        private int page;
        private int size;

        RecordingAuditService() {
            super(null);
        }

        @Override
        public Page<AuditLogResponse> findAuditLogs(
                UUID actorId,
                String eventType,
                OffsetDateTime fromDate,
                OffsetDateTime toDate,
                int page,
                int size
        ) {
            this.actorId = actorId;
            this.eventType = eventType;
            this.fromDate = fromDate;
            this.toDate = toDate;
            this.page = page;
            this.size = size;
            return Page.empty();
        }
    }
}
