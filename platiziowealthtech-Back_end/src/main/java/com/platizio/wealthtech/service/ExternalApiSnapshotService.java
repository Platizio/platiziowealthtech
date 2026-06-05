package com.platizio.wealthtech.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.ExternalApiSnapshot;
import com.platizio.wealthtech.repository.ExternalApiSnapshotRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExternalApiSnapshotService {

    private static final Logger logger = LoggerFactory.getLogger(ExternalApiSnapshotService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ExternalApiSnapshotRepository repository;

    public ExternalApiSnapshotService(ExternalApiSnapshotRepository repository) {
        this.repository = repository;
    }

    public void record(
            String provider,
            String operation,
            String method,
            String path,
            Object requestPayload,
            Object responsePayload,
            Integer statusCode,
            boolean success,
            UUID actorId,
            String entityType,
            UUID entityId
    ) {
        ExternalApiSnapshot snapshot = new ExternalApiSnapshot();
        snapshot.setProvider(provider);
        snapshot.setOperation(operation);
        snapshot.setMethod(method);
        snapshot.setPath(path);
        snapshot.setRequestJson(writeJson(requestPayload));
        snapshot.setResponseJson(writeJson(responsePayload));
        snapshot.setStatusCode(statusCode);
        snapshot.setSuccess(success);
        snapshot.setActorId(actorId);
        snapshot.setEntityType(entityType);
        snapshot.setEntityId(entityId);
        repository.save(snapshot);
    }

    public void recordSuccess(
            String provider,
            String operation,
            String method,
            String path,
            Object requestPayload,
            Object responsePayload
    ) {
        record(provider, operation, method, path, requestPayload, responsePayload, 200, true, null, null, null);
    }

    public void recordFailure(
            String provider,
            String operation,
            String method,
            String path,
            Object requestPayload,
            Integer statusCode,
            String errorMessage
    ) {
        record(
                provider,
                operation,
                method,
                path,
                requestPayload,
                Map.of("error", errorMessage == null ? "unknown_error" : errorMessage),
                statusCode,
                false,
                null,
                null,
                null
        );
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            logger.warn("external_api_snapshot status='serialize_failed' reason='{}'", ex.getMessage());
            return null;
        }
    }
}
