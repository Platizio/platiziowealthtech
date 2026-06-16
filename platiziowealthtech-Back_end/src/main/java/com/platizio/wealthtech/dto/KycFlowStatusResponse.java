package com.platizio.wealthtech.dto;



import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.platizio.wealthtech.domain.Investor;

import com.platizio.wealthtech.domain.KycStatus;

import java.util.ArrayList;

import java.util.Collections;

import java.util.List;

import java.util.Locale;



public record KycFlowStatusResponse(

        Investor investor,

        String stage,

        NextAction nextAction,

        String message,

        String aadhaarRedirectUrl,

        String esignRedirectUrl,

        List<String> fieldsNeeded

) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();



    public KycFlowStatusResponse {

        fieldsNeeded = fieldsNeeded == null ? List.of() : List.copyOf(fieldsNeeded);

    }



    public enum NextAction {

        RUN_PRE_VERIFICATION,

        CREATE_KYC_REQUEST,

        START_AADHAAR,

        REFRESH_AADHAAR,

        START_ESIGN,

        REFRESH_ESIGN,

        WAIT,

        COMPLETE

    }



    public static KycFlowStatusResponse from(Investor investor) {

        return from(investor, null, null, List.of());

    }



    public static KycFlowStatusResponse from(

            Investor investor,

            String aadhaarRedirectUrl,

            String esignRedirectUrl,

            List<String> fieldsNeeded

    ) {

        if (investor == null) {

            return new KycFlowStatusResponse(

                    null,

                    "KYC_NOT_STARTED",

                    NextAction.RUN_PRE_VERIFICATION,

                    "KYC is not started.",

                    aadhaarRedirectUrl,

                    esignRedirectUrl,

                    fieldsNeeded

            );

        }

        KycStatus kycStatus = investor.getKycStatus();

        if (kycStatus == KycStatus.COMPLETED) {

            return new KycFlowStatusResponse(

                    investor,

                    "KYC_COMPLETED",

                    NextAction.COMPLETE,

                    "KYC flow is complete.",

                    aadhaarRedirectUrl,

                    esignRedirectUrl,

                    fieldsNeeded

            );

        }



        String readinessStatus = lower(investor.getKycReadinessStatus());

        String readinessCode = lower(investor.getKycReadinessCode());

        String externalKycRequestId = investor.getExternalKycRequestId();

        String aadhaarFetchStatus = lower(investor.getAadhaarFetchStatus());

        String esignStatus = lower(investor.getEsignStatus());



        if ("verified".equals(readinessStatus) || Boolean.TRUE.equals(investor.getKycComplianceStatus())) {

            return new KycFlowStatusResponse(

                    investor,

                    "KYC_ALREADY_VERIFIED",

                    NextAction.COMPLETE,

                    "Investor is already KYC compliant.",

                    aadhaarRedirectUrl,

                    esignRedirectUrl,

                    fieldsNeeded

            );

        }

        if (!hasText(investor.getExternalKycCheckId())) {

            return new KycFlowStatusResponse(

                    investor,

                    "PRE_VERIFICATION_REQUIRED",

                    NextAction.RUN_PRE_VERIFICATION,

                    "Run pre-verification before continuing KYC.",

                    aadhaarRedirectUrl,

                    esignRedirectUrl,

                    fieldsNeeded

            );

        }

        if (!hasText(externalKycRequestId)) {

            if ("failed".equals(readinessStatus)

                    && ("kyc_unavailable".equals(readinessCode) || "unavailable".equals(readinessCode))) {

                return new KycFlowStatusResponse(

                        investor,

                        "KYC_REQUEST_REQUIRED",

                        NextAction.CREATE_KYC_REQUEST,

                        cybrillaReadinessMessage(investor, "Create a fresh KYC request."),

                        aadhaarRedirectUrl,

                        esignRedirectUrl,

                        fieldsNeeded

                );

            }

            if ("failed".equals(readinessStatus)

                    && ("kyc_incomplete".equals(readinessCode) || "unknown".equals(readinessCode))) {

                return new KycFlowStatusResponse(

                        investor,

                        "KYC_REQUEST_REQUIRED",

                        NextAction.CREATE_KYC_REQUEST,

                        cybrillaReadinessMessage(investor, "Complete or update the investor KYC application."),

                        aadhaarRedirectUrl,

                        esignRedirectUrl,

                        fieldsNeeded

                );

            }

            if (!hasText(readinessStatus) && "verified".equalsIgnoreCase(investor.getPanVerificationStatus())) {

                return new KycFlowStatusResponse(

                        investor,

                        "KYC_REQUEST_REQUIRED",

                        NextAction.CREATE_KYC_REQUEST,

                        "PAN details verified. Create a Cybrilla KYC application.",

                        aadhaarRedirectUrl,

                        esignRedirectUrl,

                        fieldsNeeded

                );

            }

            if ("accepted".equalsIgnoreCase(lower(investor.getExternalKycStatus()))) {

                return new KycFlowStatusResponse(

                        investor,

                        "PRE_VERIFICATION_PENDING",

                        NextAction.WAIT,

                        "Cybrilla is processing pre-verification.",

                        aadhaarRedirectUrl,

                        esignRedirectUrl,

                        fieldsNeeded

                );

            }

            return new KycFlowStatusResponse(

                    investor,

                    "PRE_VERIFICATION_PENDING",

                    NextAction.WAIT,

                    "Waiting for pre-verification result.",

                    aadhaarRedirectUrl,

                    esignRedirectUrl,

                    fieldsNeeded

            );

        }



        if (!fieldsNeeded.isEmpty()) {

            return new KycFlowStatusResponse(

                    investor,

                    "KYC_FIELDS_REQUIRED",

                    NextAction.WAIT,

                    "Cybrilla requires additional KYC fields before eSign: " + String.join(", ", fieldsNeeded),

                    aadhaarRedirectUrl,

                    esignRedirectUrl,

                    fieldsNeeded

            );

        }



        if (!hasText(investor.getExternalIdentityDocumentId())) {

            return new KycFlowStatusResponse(

                    investor,

                    "AADHAAR_FETCH_REQUIRED",

                    NextAction.START_AADHAAR,

                    "Start Aadhaar fetch via identity document flow.",

                    aadhaarRedirectUrl,

                    esignRedirectUrl,

                    fieldsNeeded

            );

        }

        if (!isAadhaarFetchComplete(aadhaarFetchStatus)) {

            return new KycFlowStatusResponse(

                    investor,

                    "AADHAAR_FETCH_PENDING",

                    NextAction.REFRESH_AADHAAR,

                    "Refresh Aadhaar fetch status.",

                    aadhaarRedirectUrl,

                    esignRedirectUrl,

                    fieldsNeeded

            );

        }

        if (!Boolean.TRUE.equals(investor.getAadhaarProofsAttached())) {

            return new KycFlowStatusResponse(

                    investor,

                    "AADHAAR_PROOFS_ATTACH_PENDING",

                    NextAction.REFRESH_AADHAAR,

                    "Refresh Aadhaar to attach proofs to KYC request.",

                    aadhaarRedirectUrl,

                    esignRedirectUrl,

                    fieldsNeeded

            );

        }

        if (!hasText(investor.getExternalEsignId())) {

            return new KycFlowStatusResponse(

                    investor,

                    "ESIGN_REQUIRED",

                    NextAction.START_ESIGN,

                    "Start eSign for the KYC request.",

                    aadhaarRedirectUrl,

                    esignRedirectUrl,

                    fieldsNeeded

            );

        }

        if (!isEsignComplete(esignStatus)) {

            return new KycFlowStatusResponse(

                    investor,

                    "ESIGN_PENDING",

                    NextAction.REFRESH_ESIGN,

                    "Refresh eSign status.",

                    aadhaarRedirectUrl,

                    esignRedirectUrl,

                    fieldsNeeded

            );

        }



        if (kycStatus == KycStatus.NOT_STARTED) {

            return new KycFlowStatusResponse(

                    investor,

                    "KYC_SUBMITTED_WAITING_PROVIDER",

                    NextAction.WAIT,

                    "KYC is submitted; waiting for provider finalization.",

                    aadhaarRedirectUrl,

                    esignRedirectUrl,

                    fieldsNeeded

            );

        }

        return new KycFlowStatusResponse(

                investor,

                "KYC_IN_PROGRESS",

                NextAction.WAIT,

                "KYC is in progress.",

                aadhaarRedirectUrl,

                esignRedirectUrl,

                fieldsNeeded

        );

    }



    public static List<String> extractFieldsNeeded(Investor investor) {

        if (investor == null || !hasText(investor.getExternalKycPayloadJson())) {

            return List.of();

        }

        try {

            JsonNode root = OBJECT_MAPPER.readTree(investor.getExternalKycPayloadJson());

            JsonNode fieldsNeeded = root.path("requirements").path("fields_needed");

            if (!fieldsNeeded.isArray() || fieldsNeeded.isEmpty()) {

                return List.of();

            }

            List<String> fields = new ArrayList<>();

            fieldsNeeded.forEach(node -> {

                if (node != null && node.isTextual() && hasText(node.asText())) {

                    fields.add(node.asText().trim());

                }

            });

            return Collections.unmodifiableList(fields);

        } catch (Exception ex) {

            return List.of();

        }

    }



    public static String extractAadhaarRedirectUrl(Investor investor) {

        return extractNestedText(investor, "fetch", "redirect_url");

    }



    public static String extractEsignRedirectUrl(Investor investor) {

        return extractTopLevelText(investor, "redirect_url");

    }



    private static String extractNestedText(Investor investor, String objectField, String field) {

        if (investor == null || !hasText(investor.getExternalKycPayloadJson())) {

            return null;

        }

        try {

            JsonNode root = OBJECT_MAPPER.readTree(investor.getExternalKycPayloadJson());

            JsonNode nested = root.path(objectField);

            if (nested.has(field) && !nested.get(field).isNull()) {

                return nested.get(field).asText();

            }

        } catch (Exception ignored) {

            return null;

        }

        return null;

    }



    private static String extractTopLevelText(Investor investor, String field) {

        if (investor == null || !hasText(investor.getExternalKycPayloadJson())) {

            return null;

        }

        try {

            JsonNode root = OBJECT_MAPPER.readTree(investor.getExternalKycPayloadJson());

            if (root.has(field) && !root.get(field).isNull()) {

                return root.get(field).asText();

            }

        } catch (Exception ignored) {

            return null;

        }

        return null;

    }



    private static boolean isAadhaarFetchComplete(String status) {

        return "successful".equals(status) || "completed".equals(status) || "verified".equals(status);

    }



    private static boolean isEsignComplete(String status) {

        return "successful".equals(status) || "completed".equals(status) || "complete".equals(status);

    }



    private static String lower(String value) {

        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);

    }



    private static boolean hasText(String value) {

        return value != null && !value.trim().isEmpty();

    }



    /** Prefer Cybrilla's verbatim readiness {@code reason} for investor-facing copy. */

    private static String cybrillaReadinessMessage(Investor investor, String fallback) {

        if (investor == null) {

            return fallback;

        }

        String reason = investor.getKycReadinessReason();

        if (hasText(reason)) {

            return reason.trim();

        }

        return fallback;

    }

}


