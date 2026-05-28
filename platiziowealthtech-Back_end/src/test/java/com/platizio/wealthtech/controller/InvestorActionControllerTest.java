package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.service.InvestorActionService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class InvestorActionControllerTest {

    @Test
    void showActionPageRendersPurchaseDetailsAndConfirmForm() {
        InvestorActionController controller = new InvestorActionController(new FixedInvestorActionService());

        ResponseEntity<String> response = controller.showActionPage("action-token");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .contains("Review Purchase")
                .contains("Riya Shah")
                .contains("Focused Equity Fund")
                .contains("Confirm Purchase")
                .contains("/investor-actions/action-token/confirm");
    }

    private static class FixedInvestorActionService extends InvestorActionService {

        FixedInvestorActionService() {
            super(null, null, null, null);
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
                    true,
                    "Review the details and confirm to start payment processing."
            );
        }
    }
}
