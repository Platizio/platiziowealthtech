package com.platizio.wealthtech.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.dto.ExternalKycSyncResponse;
import com.platizio.wealthtech.service.InvestorKycFormService;
import com.platizio.wealthtech.service.InvestorKycService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/cybrilla/webhooks")
public class CybrillaWebhookController {

    private final InvestorKycService investorKycService;
    private final InvestorKycFormService investorKycFormService;
    private final String webhookSecret;

    public CybrillaWebhookController(
            InvestorKycService investorKycService,
            InvestorKycFormService investorKycFormService,
            @Value("${cybrilla.webhook.secret:}") String webhookSecret
    ) {
        this.investorKycService = investorKycService;
        this.investorKycFormService = investorKycFormService;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping
    public ExternalKycSyncResponse receive(
            @RequestBody JsonNode payload,
            @RequestHeader(name = "X-Cybrilla-Webhook-Secret", required = false) String receivedSecret
    ) {
        if (StringUtils.hasText(webhookSecret) && !webhookSecret.equals(receivedSecret)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid Cybrilla webhook secret");
        }
        if (isKycFormEvent(payload)) {
            return investorKycFormService.handleKycFormWebhook(payload);
        }
        return investorKycService.handleExternalKycWebhook(payload);
    }

    private boolean isKycFormEvent(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return false;
        }
        String eventType = payload.path("type").asText("");
        if (eventType.toLowerCase().startsWith("kyc_form")) {
            return true;
        }
        JsonNode object = payload.path("data").path("object");
        if (object.isMissingNode() || object.isNull()) {
            object = payload.path("data");
        }
        String objectType = object.path("object").asText("");
        String id = object.path("id").asText("");
        return "kyc_form".equalsIgnoreCase(objectType) || id.startsWith("kycf_");
    }
}
