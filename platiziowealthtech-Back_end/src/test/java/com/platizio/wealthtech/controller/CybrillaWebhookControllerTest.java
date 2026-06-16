package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.dto.ExternalBankSyncResponse;
import com.platizio.wealthtech.dto.ExternalKycSyncResponse;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.service.InvestorKycFormService;
import com.platizio.wealthtech.service.InvestorKycService;
import com.platizio.wealthtech.service.InvestorService;
import com.platizio.wealthtech.service.OrderService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

class CybrillaWebhookControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void webhookPathIsTheKnownPublicPath() {
        RequestMapping mapping = CybrillaWebhookController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/api/v1/cybrilla/webhooks");
    }

    @Test
    void receiveRejectsInvalidSecretWhenConfigured() throws Exception {
        CybrillaWebhookController controller = controller("expected-secret", "prod");
        assertThatThrownBy(() -> controller.receive(json("{}"), "wrong-secret"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid Cybrilla webhook secret");
    }

    @Test
    void receiveRejectsMissingSecretOutsideLocalProfile() throws Exception {
        CybrillaWebhookController controller = controller("", "prod");
        assertThatThrownBy(() -> controller.receive(json("{}"), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Cybrilla webhook secret is not configured");
    }

    @Test
    void receiveRoutesBankPreVerificationBeforeKycLookup() throws Exception {
        UUID investorId = UUID.randomUUID();
        InvestorService investorService = mock(InvestorService.class);
        InvestorKycService investorKycService = mock(InvestorKycService.class);
        InvestorKycFormService investorKycFormService = mock(InvestorKycFormService.class);
        when(investorService.handleBankPreVerificationWebhook(any())).thenReturn(
                new ExternalBankSyncResponse("synced", "pre_verification.completed", "pv_bank_1", investorId, BankVerificationStatus.VERIFIED)
        );

        CybrillaWebhookController controller = new CybrillaWebhookController(
                investorKycService,
                investorKycFormService,
                investorService,
                mock(OrderService.class),
                environment("local"),
                ""
        );

        ExternalKycSyncResponse response = controller.receive(json("""
                {
                  "type":"pre_verification.completed",
                  "data":{"object":{"object":"pre_verification","id":"pv_bank_1","status":"completed"}}
                }
                """), null);

        assertThat(response.status()).isEqualTo("synced");
        assertThat(response.investorId()).isEqualTo(investorId);
        assertThat(response.kycStatus()).isNull();
        verify(investorService).handleBankPreVerificationWebhook(any());
        verify(investorKycService, never()).handleExternalKycWebhook(any());
    }

    @Test
    void receiveFallsBackToKycWhenNoBankAccountMatches() throws Exception {
        InvestorService investorService = mock(InvestorService.class);
        InvestorKycService investorKycService = mock(InvestorKycService.class);
        InvestorKycFormService investorKycFormService = mock(InvestorKycFormService.class);
        when(investorService.handleBankPreVerificationWebhook(any())).thenReturn(
                new ExternalBankSyncResponse("ignored_no_matching_bank_account", "pre_verification.completed", "pv_kyc_1", null, null)
        );
        when(investorKycService.handleExternalKycWebhook(any())).thenReturn(
                new ExternalKycSyncResponse("synced", "pre_verification.completed", "pv_kyc_1", UUID.randomUUID(), KycStatus.COMPLETED)
        );

        CybrillaWebhookController controller = new CybrillaWebhookController(
                investorKycService,
                investorKycFormService,
                investorService,
                mock(OrderService.class),
                environment("local"),
                ""
        );

        ExternalKycSyncResponse response = controller.receive(json("""
                {
                  "type":"pre_verification.completed",
                  "data":{"object":{"object":"pre_verification","id":"pv_kyc_1","status":"completed"}}
                }
                """), null);

        assertThat(response.status()).isEqualTo("synced");
        assertThat(response.kycStatus()).isEqualTo(KycStatus.COMPLETED);
        verify(investorKycService).handleExternalKycWebhook(any());
    }

    @Test
    void receiveRoutesMfPurchaseEventToOrderReconcile() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderService orderService = mock(OrderService.class);
        InvestorService investorService = mock(InvestorService.class);
        InvestorKycService investorKycService = mock(InvestorKycService.class);
        InvestorKycFormService investorKycFormService = mock(InvestorKycFormService.class);
        when(orderService.handleOrderWebhook(any(), any())).thenReturn(
                new OrderService.ExternalOrderSyncResult("synced", "mf_purchase.failed", "mfp_1", orderId, OrderStatus.FAILED)
        );

        CybrillaWebhookController controller = new CybrillaWebhookController(
                investorKycService,
                investorKycFormService,
                investorService,
                orderService,
                environment("local"),
                ""
        );

        ExternalKycSyncResponse response = controller.receive(json("""
                {
                  "type":"mf_purchase.failed",
                  "data":{"object":{"object":"mf_purchase","id":"mfp_1","state":"failed"}}
                }
                """), null);

        assertThat(response.status()).isEqualTo("synced");
        verify(orderService).handleOrderWebhook("mfp_1", "mf_purchase.failed");
        verify(investorKycService, never()).handleExternalKycWebhook(any());
    }

    @Test
    void receiveReturnsNoOpForUnknownOrderExternalId() throws Exception {
        OrderService orderService = mock(OrderService.class);
        InvestorKycService investorKycService = mock(InvestorKycService.class);
        when(orderService.handleOrderWebhook(any(), any())).thenReturn(
                new OrderService.ExternalOrderSyncResult("ignored_no_matching_order", "payment.success", "mfp_unknown", null, null)
        );

        CybrillaWebhookController controller = new CybrillaWebhookController(
                investorKycService,
                mock(InvestorKycFormService.class),
                mock(InvestorService.class),
                orderService,
                environment("local"),
                ""
        );

        ExternalKycSyncResponse response = controller.receive(json("""
                {
                  "type":"payment.success",
                  "data":{"object":{"object":"payment","id":"mfp_unknown"}}
                }
                """), null);

        assertThat(response.status()).isEqualTo("ignored_no_matching_order");
        verify(orderService).handleOrderWebhook("mfp_unknown", "payment.success");
        verify(investorKycService, never()).handleExternalKycWebhook(any());
    }

    private CybrillaWebhookController controller(String secret, String activeProfile) {
        return new CybrillaWebhookController(
                mock(InvestorKycService.class),
                mock(InvestorKycFormService.class),
                mock(InvestorService.class),
                mock(OrderService.class),
                environment(activeProfile),
                secret
        );
    }

    private Environment environment(String activeProfile) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {activeProfile});
        return environment;
    }

    private com.fasterxml.jackson.databind.JsonNode json(String value) throws Exception {
        return OBJECT_MAPPER.readTree(value);
    }
}
