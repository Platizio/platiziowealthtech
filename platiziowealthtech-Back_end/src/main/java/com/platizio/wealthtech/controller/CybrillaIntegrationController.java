package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.dto.CybrillaIntegrationInfoResponse;
import com.platizio.wealthtech.integration.CybrillaIntegrationEnvironment;
import com.platizio.wealthtech.integration.auth.CybrillaPreVerificationProperties;
import com.platizio.wealthtech.integration.auth.FinprimTenantProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cybrilla")
public class CybrillaIntegrationController {

    private static final String AADHAAR_METHOD =
            "Fresh KYC Aadhaar verification uses Cybrilla Fintech Primitives POST /v2/identity_documents "
                    + "(type=aadhaar). The investor completes OTP/biometric on the Cybrilla Digilocker redirect; "
                    + "Platizio never stores Aadhaar numbers or runs local OTP.";

    private final CybrillaIntegrationEnvironment environment;
    private final CybrillaPreVerificationProperties poaProperties;
    private final FinprimTenantProperties finprimProperties;

    public CybrillaIntegrationController(
            CybrillaIntegrationEnvironment environment,
            CybrillaPreVerificationProperties poaProperties,
            FinprimTenantProperties finprimProperties
    ) {
        this.environment = environment;
        this.poaProperties = poaProperties;
        this.finprimProperties = finprimProperties;
    }

    @GetMapping("/integration-info")
    public CybrillaIntegrationInfoResponse integrationInfo() {
        return new CybrillaIntegrationInfoResponse(
                environment.configuredEnvironment(),
                environment.isSandboxMode(),
                environment.isProductionMode(),
                environment.usesTestCredentials(),
                environment.isPoaSandbox(),
                environment.isFinprimSandbox(),
                environment.poaBaseUrl(),
                environment.finprimBaseUrl(),
                poaProperties.getAuth().getTokenUrl(),
                finprimProperties.resolvedTokenUrl(),
                finprimProperties.tenantHeaderValue(),
                environment.sandboxGuidanceMessage(),
                AADHAAR_METHOD
        );
    }
}
