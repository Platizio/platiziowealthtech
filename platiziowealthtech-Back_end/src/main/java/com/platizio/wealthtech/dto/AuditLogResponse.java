package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.AuditEvent;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        String entityType,
        UUID entityId,
        String eventType,
        UUID actorId,
        String detailsJson,
        OffsetDateTime createdAt
) {
    public static AuditLogResponse from(AuditEvent event) {
        return new AuditLogResponse(
                event.getId(),
                event.getEntityType(),
                event.getEntityId(),
                event.getActionType(),
                event.getActorId(),
                event.getDetailsJson(),
                event.getCreatedAt()
        );
    }
}
