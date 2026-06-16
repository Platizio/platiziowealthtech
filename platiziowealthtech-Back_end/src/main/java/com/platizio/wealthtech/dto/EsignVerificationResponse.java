package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.domain.Investor;

public record EsignVerificationResponse(
        Investor investor,
        JsonNode externalResponse,
        String esignId,
        String status,
        String redirectUrl,
        boolean completed
) {
    public static EsignVerificationResponse from(Investor investor, JsonNode externalResponse) {
        String esignId = firstText(externalResponse, "id");
        if (!hasText(esignId) && investor != null) {
            esignId = investor.getExternalEsignId();
        }
        String status = firstText(externalResponse, "status");
        if (!hasText(status) && investor != null) {
            status = investor.getEsignStatus();
        }
        String redirectUrl = firstText(externalResponse, "redirect_url");
        return new EsignVerificationResponse(
                investor,
                externalResponse,
                esignId,
                status,
                redirectUrl,
                isCompleted(status)
        );
    }

    private static boolean isCompleted(String status) {
        if (!hasText(status)) {
            return false;
        }
        return switch (status.trim().toLowerCase()) {
            case "successful", "completed", "complete" -> true;
            default -> false;
        };
    }

    private static String firstText(JsonNode node, String field) {
        if (node == null || node.isNull() || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText();
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
