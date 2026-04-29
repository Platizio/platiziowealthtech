package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.AuditEvent;
import com.platizio.wealthtech.repository.AuditEventRepository;
import java.util.UUID;
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
}