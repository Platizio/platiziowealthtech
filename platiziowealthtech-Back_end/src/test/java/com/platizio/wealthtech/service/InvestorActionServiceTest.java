package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class InvestorActionServiceTest {

    @Test
    void confirmPurchaseMovesPendingInvestorActionToPaymentPending() {
        TransactionOrderRepository orderRepository = org.mockito.Mockito.mock(TransactionOrderRepository.class);
        InvestorRepository investorRepository = org.mockito.Mockito.mock(InvestorRepository.class);
        ProductSchemeRepository schemeRepository = org.mockito.Mockito.mock(ProductSchemeRepository.class);
        RecordingAuditService auditService = new RecordingAuditService();
        TransactionOrder order = pendingOrder();
        Investor investor = investor(order.getInvestorId());
        ProductScheme scheme = scheme(order.getProductSchemeId());
        InvestorActionService service = new InvestorActionService(
                orderRepository,
                investorRepository,
                schemeRepository,
                auditService
        );

        when(orderRepository.findByInvestorActionToken("action-token")).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(investorRepository.findById(order.getInvestorId())).thenReturn(Optional.of(investor));
        when(schemeRepository.findById(order.getProductSchemeId())).thenReturn(Optional.of(scheme));

        InvestorActionService.InvestorActionPage page = service.confirmPurchase("action-token");

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(page.confirmationAllowed()).isFalse();
        assertThat(page.message()).contains("Payment is now pending");
        assertThat(page.investorName()).isEqualTo("Riya Shah");
        assertThat(page.schemeName()).isEqualTo("Focused Equity Fund");
        assertThat(auditService.entityType.get()).isEqualTo("ORDER");
        assertThat(auditService.entityId.get()).isEqualTo(order.getId());
        assertThat(auditService.actionType.get()).isEqualTo("INVESTOR_ACTION_CONFIRMED");
        assertThat(auditService.actorId.get()).isEqualTo(order.getDistributorId());
        assertThat(auditService.details.get()).isEqualTo("{\"status\":\"PAYMENT_PENDING\"}");
    }

    private TransactionOrder pendingOrder() {
        TransactionOrder order = new TransactionOrder();
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.setInvestorActionToken("action-token");
        order.setInvestorId(UUID.randomUUID());
        order.setDistributorId(UUID.randomUUID());
        order.setProductSchemeId(UUID.randomUUID());
        order.setTransactionType(TransactionType.LUMPSUM_PURCHASE);
        order.setAmount(new BigDecimal("25000.00"));
        order.setPaymentMode("NET_BANKING");
        order.setOrderStatus(OrderStatus.PENDING_INVESTOR_ACTION);
        return order;
    }

    private Investor investor(UUID investorId) {
        Investor investor = new Investor();
        ReflectionTestUtils.setField(investor, "id", investorId);
        investor.setFullName("Riya Shah");
        investor.setEmail("riya@example.com");
        return investor;
    }

    private ProductScheme scheme(UUID schemeId) {
        ProductScheme scheme = new ProductScheme();
        ReflectionTestUtils.setField(scheme, "id", schemeId);
        scheme.setSchemeName("Focused Equity Fund");
        scheme.setAmcName("Platizio AMC");
        scheme.setCategory(ProductCategory.MF);
        scheme.setExternalSchemeCode("MF-FOCUSED");
        return scheme;
    }

    private static class RecordingAuditService extends AuditService {
        private final AtomicReference<String> entityType = new AtomicReference<>();
        private final AtomicReference<UUID> entityId = new AtomicReference<>();
        private final AtomicReference<String> actionType = new AtomicReference<>();
        private final AtomicReference<UUID> actorId = new AtomicReference<>();
        private final AtomicReference<String> details = new AtomicReference<>();

        RecordingAuditService() {
            super(null);
        }

        @Override
        public void log(String entityType, UUID entityId, String actionType, UUID actorId, String detailsJson) {
            this.entityType.set(entityType);
            this.entityId.set(entityId);
            this.actionType.set(actionType);
            this.actorId.set(actorId);
            this.details.set(detailsJson);
        }
    }
}
