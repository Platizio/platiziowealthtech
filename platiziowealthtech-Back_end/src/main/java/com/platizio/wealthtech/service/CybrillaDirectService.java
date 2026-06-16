package com.platizio.wealthtech.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.integration.CybrillaClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Thin proxy to Cybrilla/Finprim — returns provider JSON without local PAN simulation guards.
 */
@Service
public class CybrillaDirectService {

    private static final Logger logger = LoggerFactory.getLogger(CybrillaDirectService.class);

    private final CybrillaClient cybrillaClient;
    private final int preVerificationPollMaxAttempts;
    private final long preVerificationPollIntervalMs;

    public CybrillaDirectService(
            CybrillaClient cybrillaClient,
            @Value("${app.kyc-sync.pre-verification-poll-max-attempts:15}") int preVerificationPollMaxAttempts,
            @Value("${app.kyc-sync.pre-verification-poll-interval-ms:2000}") long preVerificationPollIntervalMs
    ) {
        this.cybrillaClient = cybrillaClient;
        this.preVerificationPollMaxAttempts = Math.max(1, preVerificationPollMaxAttempts);
        this.preVerificationPollIntervalMs = Math.max(500L, preVerificationPollIntervalMs);
    }

    public JsonNode createPreVerification(Map<String, Object> body, boolean waitForCompletion) {
        Map<String, Object> payload = body == null ? Map.of() : new LinkedHashMap<>(body);
        JsonNode created = cybrillaClient.createPreVerification(payload);
        if (!waitForCompletion) {
            return created;
        }
        return awaitPreVerificationCompletion(created);
    }

    public JsonNode fetchPreVerification(String preVerificationId) {
        return cybrillaClient.fetchKycCheck(preVerificationId);
    }

    public JsonNode fetchCataloguePage(String endpoint, int page, int size) {
        String resolvedEndpoint = endpoint == null || endpoint.isBlank() ? "poa-mf" : endpoint.trim();
        return cybrillaClient.fetchLiveCataloguePage(resolvedEndpoint, page, size).rawResponse();
    }

    private JsonNode awaitPreVerificationCompletion(JsonNode response) {
        if (response == null || response.isNull() || !"pre_verification".equalsIgnoreCase(text(response, "object"))) {
            return response;
        }
        String preVerificationId = text(response, "id");
        String status = text(response, "status");
        if (!StringUtils.hasText(preVerificationId) || !"accepted".equalsIgnoreCase(status)) {
            return response;
        }

        JsonNode latest = response;
        for (int attempt = 1; attempt <= preVerificationPollMaxAttempts; attempt++) {
            sleepPollInterval();
            latest = cybrillaClient.fetchKycCheck(preVerificationId);
            status = text(latest, "status");
            if ("completed".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
                logger.info(
                        "cybrilla_direct pre_verification status='completed_poll' id='{}' attempts='{}' final_status='{}'",
                        preVerificationId,
                        attempt,
                        status
                );
                return latest;
            }
            if (!"accepted".equalsIgnoreCase(status)) {
                return latest;
            }
        }
        logger.info(
                "cybrilla_direct pre_verification status='poll_timeout' id='{}' attempts='{}' last_status='{}'",
                preVerificationId,
                preVerificationPollMaxAttempts,
                status
        );
        return latest;
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(preVerificationPollIntervalMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling Cybrilla pre-verification", ex);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
