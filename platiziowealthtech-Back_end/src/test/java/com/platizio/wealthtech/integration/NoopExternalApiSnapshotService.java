package com.platizio.wealthtech.integration;

import com.platizio.wealthtech.service.ExternalApiSnapshotService;
import java.util.UUID;

class NoopExternalApiSnapshotService extends ExternalApiSnapshotService {

    NoopExternalApiSnapshotService() {
        super(null);
    }

    @Override
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
        // Tests exercise HTTP mapping and metrics; snapshot persistence is covered elsewhere.
    }
}
