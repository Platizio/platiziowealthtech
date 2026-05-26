package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.dto.AuditLogResponse;
import com.platizio.wealthtech.service.AuditService;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/audit", "/api/v1/audit"})
public class AuditController {

    private static final Duration MAX_AUDIT_DATE_RANGE = Duration.ofDays(90);

    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public AuditController(AuditService auditService) {
        this(auditService, Clock.systemUTC());
    }

    AuditController(AuditService auditService, Clock clock) {
        this.auditService = auditService;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public Page<AuditLogResponse> searchAuditLogs(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        AuditDateRange dateRange = boundedAuditDateRange(fromDate, toDate);
        return auditService.findAuditLogs(actorId, eventType, dateRange.fromDate(), dateRange.toDate(), page, size);
    }

    private AuditDateRange boundedAuditDateRange(OffsetDateTime fromDate, OffsetDateTime toDate) {
        OffsetDateTime effectiveToDate = toDate != null ? toDate : OffsetDateTime.now(clock);
        OffsetDateTime effectiveFromDate = fromDate != null ? fromDate : effectiveToDate.minus(MAX_AUDIT_DATE_RANGE);

        if (effectiveToDate.isBefore(effectiveFromDate)) {
            throw new IllegalArgumentException("toDate must be greater than or equal to fromDate");
        }
        if (Duration.between(effectiveFromDate, effectiveToDate).compareTo(MAX_AUDIT_DATE_RANGE) > 0) {
            throw new IllegalArgumentException("Audit date range cannot exceed 90 days");
        }
        return new AuditDateRange(effectiveFromDate, effectiveToDate);
    }

    private record AuditDateRange(OffsetDateTime fromDate, OffsetDateTime toDate) {
    }
}
