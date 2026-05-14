package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.integration.auth.ExternalBearerTokenService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/debug/external-auth")
@ConditionalOnProperty(prefix = "external-auth.debug", name = "enabled", havingValue = "true")
public class ExternalAuthDebugController {

    private final ExternalBearerTokenService tokenService;

    public ExternalAuthDebugController(ExternalBearerTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping("/cybrilla-pre-verification")
    public Map<String, String> triggerCybrillaPreVerificationToken() {
        tokenService.getCybrillaPreVerificationAccessToken();
        return Map.of("status", "Cybrilla pre-verification token was requested. Check backend console logs.");
    }

    @GetMapping("/finprim-tenant")
    public Map<String, String> triggerFinprimTenantToken() {
        tokenService.getFinprimTenantAccessToken();
        return Map.of("status", "Finprim tenant token was requested. Check backend console logs.");
    }
}
