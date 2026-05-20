package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.OrderCreateRequest;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.annotations.SQLRestriction;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class OrderServiceTest {

    @Test
    void createOrderRejectsInvestorOwnedByAnotherDistributor() {
        UUID investorDistributorId = UUID.randomUUID();
        UUID authenticatedDistributorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        Investor investor = new Investor();
        investor.setDistributorId(investorDistributorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFIED);

        OrderService orderService = new OrderService(
                null,
                null,
                new FixedInvestorService(investor),
                null,
                null,
                null
        );

        OrderCreateRequest request = new OrderCreateRequest(
                investorId,
                UUID.randomUUID(),
                null,
                TransactionType.LUMPSUM_PURCHASE,
                BigDecimal.TEN,
                null,
                "NET_BANKING",
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> orderService.createOrder(request, authenticatedDistributorId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void transactionOrderEntityFiltersSoftDeletedRows() {
        SQLRestriction restriction = TransactionOrder.class.getAnnotation(SQLRestriction.class);

        assertThat(restriction).isNotNull();
        assertThat(restriction.value()).isEqualTo("is_deleted = false");
    }

    @Test
    void deleteOrderMarksDeletedInsteadOfHardDeleting() {
        UUID orderId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        TransactionOrder order = new TransactionOrder();
        order.setDistributorId(actorId);
        AtomicReference<TransactionOrder> savedOrder = new AtomicReference<>();
        AtomicBoolean hardDeleteCalled = new AtomicBoolean(false);
        AtomicReference<String> auditDetails = new AtomicReference<>();
        OrderService orderService = new OrderService(
                orderRepository(order, savedOrder, hardDeleteCalled),
                null,
                null,
                new CapturingAuditService(auditDetails),
                null,
                null
        );

        orderService.deleteOrder(orderId, actorId);

        assertThat(hardDeleteCalled).isFalse();
        assertThat(savedOrder.get()).isSameAs(order);
        assertThat(savedOrder.get().getIsDeleted()).isTrue();
        assertThat(savedOrder.get().getDeletedAt()).isNotNull();
        assertThat(auditDetails.get()).isEqualTo("{\"softDeleted\":true,\"reason\":\"User requested deletion\"}");
    }

    private static class FixedInvestorService extends InvestorService {

        private final Investor investor;

        FixedInvestorService(Investor investor) {
            super(null, null, null, null, null);
            this.investor = investor;
        }

        @Override
        public Investor getInvestor(UUID investorId) {
            return investor;
        }
    }

    private TransactionOrderRepository orderRepository(
            TransactionOrder order,
            AtomicReference<TransactionOrder> savedOrder,
            AtomicBoolean hardDeleteCalled
    ) {
        return (TransactionOrderRepository) Proxy.newProxyInstance(
                TransactionOrderRepository.class.getClassLoader(),
                new Class<?>[]{TransactionOrderRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.of(order);
                    case "save" -> {
                        savedOrder.set((TransactionOrder) args[0]);
                        yield args[0];
                    }
                    case "delete", "deleteById", "deleteAll" -> {
                        hardDeleteCalled.set(true);
                        yield null;
                    }
                    case "findAll", "findByInvestorId", "findByDistributorId" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        return 0;
    }

    private static class CapturingAuditService extends AuditService {

        private final AtomicReference<String> auditDetails;

        CapturingAuditService(AtomicReference<String> auditDetails) {
            super(null);
            this.auditDetails = auditDetails;
        }

        @Override
        public void log(String entityType, UUID entityId, String actionType, UUID actorId, String detailsJson) {
            assertThat(entityType).isEqualTo("ORDER");
            assertThat(actionType).isEqualTo("DELETED");
            auditDetails.set(detailsJson);
        }
    }
}
