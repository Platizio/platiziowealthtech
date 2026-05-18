package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.BulkOrderCreateRequest;
import com.platizio.wealthtech.dto.OrderCreateRequest;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.service.OrderService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class OrderControllerTest {

    @Test
    void createOrderUsesAuthenticatedDistributorId() {
        RecordingOrderService orderService = new RecordingOrderService();
        OrderController controller = new OrderController(orderService);
        UUID distributorId = UUID.randomUUID();
        OrderCreateRequest request = new OrderCreateRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                TransactionType.LUMPSUM_PURCHASE,
                BigDecimal.TEN,
                null,
                "NET_BANKING",
                null
        );

        controller.createOrder(request, principal(distributorId));

        assertThat(orderService.createOrderDistributorId).isEqualTo(distributorId);
    }

    @Test
    void createBulkOrdersUsesAuthenticatedDistributorId() {
        RecordingOrderService orderService = new RecordingOrderService();
        OrderController controller = new OrderController(orderService);
        UUID distributorId = UUID.randomUUID();
        BulkOrderCreateRequest request = new BulkOrderCreateRequest(
                List.of(UUID.randomUUID(), UUID.randomUUID()),
                UUID.randomUUID(),
                TransactionType.SIP,
                BigDecimal.TEN,
                null,
                "UPI",
                "E_MANDATE"
        );

        controller.createOrders(request, principal(distributorId));

        assertThat(orderService.createOrdersDistributorId).isEqualTo(distributorId);
    }

    private AuthenticatedDistributorPrincipal principal(UUID distributorId) {
        return new AuthenticatedDistributorPrincipal(
                distributorId,
                "user@example.com",
                "",
                DistributorRole.SUB_DISTRIBUTOR,
                List.of(new SimpleGrantedAuthority("ROLE_SUB_DISTRIBUTOR"))
        );
    }

    private static class RecordingOrderService extends OrderService {

        private UUID createOrderDistributorId;
        private UUID createOrdersDistributorId;

        RecordingOrderService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public TransactionOrder createOrder(OrderCreateRequest request, UUID distributorId) {
            createOrderDistributorId = distributorId;
            return null;
        }

        @Override
        public List<TransactionOrder> createOrders(BulkOrderCreateRequest request, UUID distributorId) {
            createOrdersDistributorId = distributorId;
            return List.of();
        }
    }
}
