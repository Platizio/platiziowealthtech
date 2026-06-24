package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaUnavailableException;
import com.platizio.wealthtech.service.InvestorActionService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class InvestorActionControllerTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:3000";

    @Test
    void showActionPageRendersPurchaseDetailsAndConfirmForm() {
        InvestorActionController controller =
                new InvestorActionController(new FixedInvestorActionService(), FRONTEND_ORIGIN);

        ResponseEntity<String> response = controller.showActionPage("action-token");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .contains("Review Purchase")
                .contains("Riya Shah")
                .contains("Focused Equity Fund")
                .contains("Confirm Purchase")
                .contains("/investor-actions/action-token/confirm")
                .contains("Technical details (support)");
    }

    @Test
    void confirmPurchaseReturns503WhenProviderUnavailable() {
        InvestorActionController controller = new InvestorActionController(
                new FixedInvestorActionService() {
                    @Override
                    public InvestorActionPage confirmPurchase(String token) {
                        throw new CybrillaUnavailableException("provider down", null);
                    }
                },
                FRONTEND_ORIGIN);

        ResponseEntity<String> response = controller.confirmPurchase("action-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("provider down");
    }

    @Test
    void confirmPurchaseReturns502OnGenericCybrillaError() {
        InvestorActionController controller = new InvestorActionController(
                new FixedInvestorActionService() {
                    @Override
                    public InvestorActionPage confirmPurchase(String token) {
                        throw new CybrillaApiException("provider rejected");
                    }
                },
                FRONTEND_ORIGIN);

        ResponseEntity<String> response = controller.confirmPurchase("action-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).contains("provider rejected");
    }

    @Test
    void paymentCompleteReturns400OnIllegalState() {
        InvestorActionController controller = new InvestorActionController(
                new FixedInvestorActionService() {
                    @Override
                    public InvestorActionPage handlePaymentPostback(String token, String paymentId, String status) {
                        throw new IllegalStateException("order not awaiting payment");
                    }
                },
                FRONTEND_ORIGIN);

        ResponseEntity<String> response = controller.paymentCompletePost("action-token", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("order not awaiting payment");
    }

    private static class FixedInvestorActionService extends InvestorActionService {

        FixedInvestorActionService() {
            super(null, null, null, null, null, null, null, null, null, "http://localhost/payment-complete", "sandbox", "CYBRILLAPOA", true, true);
        }

        @Override
        public InvestorActionPage getPage(String token) {
            return new InvestorActionPage(
                    token,
                    UUID.randomUUID(),
                    "external-order",
                    "Riya Shah",
                    "riya@example.com",
                    "Focused Equity Fund",
                    "Platizio AMC",
                    new BigDecimal("25000.00"),
                    null,
                    "LUMPSUM_PURCHASE",
                    OrderStatus.PENDING_INVESTOR_ACTION,
                    "NET_BANKING",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true,
                    "Review the details and confirm to start payment processing.",
                    null,
                    false,
                    false,
                    "{\"page\":\"investor-action\"}"
            );
        }
    }
}
