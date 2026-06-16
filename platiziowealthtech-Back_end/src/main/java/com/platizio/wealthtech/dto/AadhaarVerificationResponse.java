package com.platizio.wealthtech.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.domain.Investor;

/**
 * Stable Platizio view of Cybrilla FP {@code /v2/identity_documents} Aadhaar fetch state.
 * Redirect URLs are returned only when the provider includes them (typically on create).
 */
public record AadhaarVerificationResponse(
        Investor investor,
        JsonNode externalResponse,
        String identityDocumentId,
        String fetchStatus,
        String fetchReason,
        String redirectUrl,
        boolean fetchComplete,
        boolean proofsAttachedToKycRequest
) {
    public static AadhaarVerificationResponse from(
            Investor investor,
            JsonNode externalResponse,
            boolean proofsAttachedToKycRequest
    ) {
        String identityDocumentId = firstText(externalResponse, "id");
        if (identityDocumentId == null || identityDocumentId.isBlank()) {
            identityDocumentId = investor == null ? null : investor.getExternalIdentityDocumentId();
        }
        String fetchStatus = nestedText(externalResponse, "fetch", "status");
        if (fetchStatus == null || fetchStatus.isBlank()) {
            fetchStatus = investor == null ? null : investor.getAadhaarFetchStatus();
        }
        String fetchReason = nestedText(externalResponse, "fetch", "reason");
        if (fetchReason == null || fetchReason.isBlank()) {
            fetchReason = investor == null ? null : investor.getAadhaarFetchReason();
        }
        String redirectUrl = nestedText(externalResponse, "fetch", "redirect_url");
        return new AadhaarVerificationResponse(
                investor,
                externalResponse,
                identityDocumentId,
                fetchStatus,
                fetchReason,
                redirectUrl,
                isFetchComplete(fetchStatus),
                proofsAttachedToKycRequest
        );
    }

    private static boolean isFetchComplete(String fetchStatus) {
        if (fetchStatus == null || fetchStatus.isBlank()) {
            return false;
        }
        return switch (fetchStatus.trim().toLowerCase()) {
            case "successful", "completed", "verified" -> true;
            default -> false;
        };
    }

    private static String firstText(JsonNode node, String field) {
        if (node == null || node.isNull() || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static String nestedText(JsonNode node, String objectField, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode nested = node.path(objectField);
        if (nested.isMissingNode() || nested.isNull() || !nested.has(field) || nested.get(field).isNull()) {
            return null;
        }
        return nested.get(field).asText();
    }
}
