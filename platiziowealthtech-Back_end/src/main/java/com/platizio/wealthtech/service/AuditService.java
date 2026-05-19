package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.AuditEvent;
import com.platizio.wealthtech.dto.AuditLogResponse;
import com.platizio.wealthtech.repository.AuditEventRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    public void log(String entityType, UUID entityId, String actionType, UUID actorId, String detailsJson) {
        AuditEvent event = new AuditEvent();
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setActionType(actionType);
        event.setActorId(actorId);
        event.setDetailsJson(detailsJson);
        auditEventRepository.save(event);
    }

    public Page<AuditLogResponse> findAuditLogs(
            UUID actorId,
            String eventType,
            OffsetDateTime fromDate,
            OffsetDateTime toDate,
            int page,
            int size
    ) {
        PageRequest pageRequest = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        return auditEventRepository.findByFilters(actorId, eventType, fromDate, toDate, pageRequest)
                .map(AuditLogResponse::from);
    }
}
