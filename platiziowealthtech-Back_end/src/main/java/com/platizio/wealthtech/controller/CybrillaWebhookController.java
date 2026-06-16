package com.platizio.wealthtech.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.dto.ExternalBankSyncResponse;
import com.platizio.wealthtech.dto.ExternalKycSyncResponse;
import com.platizio.wealthtech.service.InvestorKycFormService;
import com.platizio.wealthtech.service.InvestorKycService;
import com.platizio.wealthtech.service.InvestorService;
import com.platizio.wealthtech.service.OrderService;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
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
    private final InvestorService investorService;
    private final OrderService orderService;
    private final Environment environment;
    private final String webhookSecret;

    public CybrillaWebhookController(
            InvestorKycService investorKycService,
            InvestorKycFormService investorKycFormService,
            InvestorService investorService,
            OrderService orderService,
            Environment environment,
            @Value("${cybrilla.webhook.secret:}") String webhookSecret
    ) {
        this.investorKycService = investorKycService;
        this.investorKycFormService = investorKycFormService;
        this.investorService = investorService;
        this.orderService = orderService;
        this.environment = environment;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping
    public ExternalKycSyncResponse receive(
            @RequestBody JsonNode payload,
            @RequestHeader(name = "X-Cybrilla-Webhook-Secret", required = false) String receivedSecret
    ) {
        validateWebhookSecret(receivedSecret);
        if (isKycFormEvent(payload)) {
            return investorKycFormService.handleKycFormWebhook(payload);
        }
        if (isPreVerificationEvent(payload)) {
            ExternalBankSyncResponse bankResponse = investorService.handleBankPreVerificationWebhook(payload);
            if (!"ignored_no_matching_bank_account".equals(bankResponse.status())) {
                return toKycSyncResponse(bankResponse);
            }
        }
        if (isMandateEvent(payload)) {
            // Mandate reconcile is keyed by FP's int mandate id (not the order's externalOrderId)
            // and would require substantial new lookup/apply logic, so acknowledge without
            // reconciling here. Follow-up: wire a mandate reconcile (see BUG-009 summary).
            return new ExternalKycSyncResponse(
                    "acknowledged_no_reconcile",
                    eventType(payload),
                    externalObjectId(payload),
                    null,
                    null
            );
        }
        if (isOrderEvent(payload)) {
            return toKycSyncResponse(orderService.handleOrderWebhook(externalObjectId(payload), eventType(payload)));
        }
        return investorKycService.handleExternalKycWebhook(payload);
    }

    private void validateWebhookSecret(String receivedSecret) {
        if (StringUtils.hasText(webhookSecret)) {
            if (!webhookSecret.equals(receivedSecret)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid Cybrilla webhook secret");
            }
            return;
        }
        if (!isLocalProfile()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Cybrilla webhook secret is not configured for this environment"
            );
        }
    }

    private boolean isLocalProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "local".equalsIgnoreCase(profile));
    }

    private ExternalKycSyncResponse toKycSyncResponse(ExternalBankSyncResponse bankResponse) {
        return new ExternalKycSyncResponse(
                bankResponse.status(),
                bankResponse.eventType(),
                bankResponse.externalId(),
                bankResponse.investorId(),
                null
        );
    }

    private ExternalKycSyncResponse toKycSyncResponse(OrderService.ExternalOrderSyncResult orderResult) {
        return new ExternalKycSyncResponse(
                orderResult.status(),
                orderResult.eventType(),
                orderResult.externalId(),
                null,
                null
        );
    }

    private String eventType(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        String eventType = payload.path("type").asText("");
        return StringUtils.hasText(eventType) ? eventType : null;
    }

    private String externalObjectId(JsonNode payload) {
        JsonNode object = webhookDataObject(payload);
        String id = object == null ? "" : object.path("id").asText("");
        return StringUtils.hasText(id) ? id : null;
    }

    private JsonNode webhookDataObject(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        JsonNode object = payload.path("data").path("object");
        if (object.isMissingNode() || object.isNull()) {
            object = payload.path("data");
        }
        return object;
    }

    private boolean isOrderEvent(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return false;
        }
        String eventType = payload.path("type").asText("");
        String lowered = eventType.toLowerCase();
        if (lowered.startsWith("mf_purchase.") || lowered.startsWith("payment.")) {
            return true;
        }
        JsonNode object = webhookDataObject(payload);
        String objectType = object.path("object").asText("");
        String id = object.path("id").asText("");
        return "mf_purchase".equalsIgnoreCase(objectType)
                || "payment".equalsIgnoreCase(objectType)
                || id.startsWith("mfp_");
    }

    private boolean isMandateEvent(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return false;
        }
        String eventType = payload.path("type").asText("");
        if (eventType.toLowerCase().startsWith("mandate.")) {
            return true;
        }
        JsonNode object = webhookDataObject(payload);
        return "mandate".equalsIgnoreCase(object.path("object").asText(""));
    }

    private boolean isPreVerificationEvent(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return false;
        }
        String eventType = payload.path("type").asText("");
        if (eventType.toLowerCase().startsWith("pre_verification.")) {
            return true;
        }
        JsonNode object = payload.path("data").path("object");
        if (object.isMissingNode() || object.isNull()) {
            object = payload.path("data");
        }
        return "pre_verification".equalsIgnoreCase(object.path("object").asText(""));
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
