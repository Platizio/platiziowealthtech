package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platizio.wealthtech.dto.AuditLogResponse;
import com.platizio.wealthtech.service.AuditService;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    @Test
    void auditSearchRejectsDateRangeLongerThanNinetyDays() {
        AuditController controller = new AuditController(new RecordingAuditService());
        OffsetDateTime fromDate = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime toDate = OffsetDateTime.parse("2026-04-02T00:00:01Z");

        assertThatThrownBy(() -> controller.searchAuditLogs(null, null, fromDate, toDate, 0, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Audit date range cannot exceed 90 days");
    }

    @Test
    void auditSearchDefaultsMissingDatesToLastNinetyDays() {
        RecordingAuditService auditService = new RecordingAuditService();
        Clock clock = Clock.fixed(Instant.parse("2026-05-21T10:00:00Z"), ZoneOffset.UTC);
        AuditController controller = new AuditController(auditService, clock);

        controller.searchAuditLogs(null, null, null, null, 0, 50);

        assertThat(auditService.fromDate).isEqualTo(OffsetDateTime.parse("2026-02-20T10:00:00Z"));
        assertThat(auditService.toDate).isEqualTo(OffsetDateTime.parse("2026-05-21T10:00:00Z"));
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
