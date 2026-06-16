package com.platizio.wealthtech.dto;

/**
 * Non-secret Cybrilla integration metadata for distributor UI (sandbox vs production, Aadhaar path).
 */
public record CybrillaIntegrationInfoResponse(
        String configuredEnvironment,
        boolean sandboxMode,
        boolean productionMode,
        boolean usesTestCredentials,
        boolean poaSandbox,
        boolean finprimSandbox,
        String poaBaseUrl,
        String finprimBaseUrl,
        String poaTokenUrl,
        String finprimTokenUrl,
        String finprimTenantHeader,
        String guidanceMessage,
        String aadhaarVerificationMethod
) {
}
