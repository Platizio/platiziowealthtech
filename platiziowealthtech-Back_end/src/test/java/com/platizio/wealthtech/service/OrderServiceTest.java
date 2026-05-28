package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.domain.Notification;
import com.platizio.wealthtech.domain.NotificationType;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.BulkOrderCreateRequest;
import com.platizio.wealthtech.dto.OrderCreateRequest;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.annotations.SQLRestriction;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

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
    void createOrderStoresInvestorActionTokenAndUrl() {
        UUID distributorId = UUID.randomUUID();
        List<TransactionOrder> savedOrders = new ArrayList<>();
        OrderService orderService = new OrderService(
                savingOrderRepository(savedOrders),
                null,
                new FixedInvestorService(verifiedInvestor(distributorId)),
                new CountingAuditService(new AtomicInteger()),
                new CountingNotificationService(new AtomicInteger()),
                actionUrlCybrillaClient(),
                null,
                productSchemeRepository()
        );
        OrderCreateRequest request = new OrderCreateRequest(
                UUID.randomUUID(),
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

        TransactionOrder order = orderService.createOrder(request, distributorId);

        assertThat(order.getInvestorActionToken()).isNotBlank();
        assertThat(order.getInvestorActionUrl()).isEqualTo("/investor-actions/" + order.getInvestorActionToken());
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
                null,
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

    @Test
    void bulkCreateCommitsEachOrderInItsOwnTransactionBeforeLaterFailure() {
        UUID distributorId = UUID.randomUUID();
        UUID firstInvestorId = UUID.randomUUID();
        UUID secondInvestorId = UUID.randomUUID();
        UUID failingInvestorId = UUID.randomUUID();
        CountingTransactionManager transactionManager = new CountingTransactionManager();
        AtomicInteger externalCreateCalls = new AtomicInteger();
        AtomicInteger notificationCalls = new AtomicInteger();
        AtomicInteger auditCalls = new AtomicInteger();
        List<TransactionOrder> savedOrders = new ArrayList<>();
        BulkOrderCreateRequest request = new BulkOrderCreateRequest(
                List.of(firstInvestorId, secondInvestorId, firstInvestorId, failingInvestorId),
                UUID.randomUUID(),
                TransactionType.LUMPSUM_PURCHASE,
                BigDecimal.TEN,
                null,
                "NET_BANKING",
                null
        );

        OrderService orderService = new OrderService(
                savingOrderRepository(savedOrders),
                null,
                new FixedInvestorService(verifiedInvestor(distributorId)),
                new CountingAuditService(auditCalls),
                new CountingNotificationService(notificationCalls),
                failingCybrillaClient(externalCreateCalls, 3),
                transactionManager,
                productSchemeRepository()
        );

        assertThatThrownBy(() -> orderService.createOrders(request, distributorId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cybrilla failed");

        assertThat(transactionManager.commits()).isEqualTo(2);
        assertThat(transactionManager.rollbacks()).isEqualTo(1);
        assertThat(externalCreateCalls.get()).isEqualTo(3);
        assertThat(auditCalls.get()).isEqualTo(2);
        assertThat(notificationCalls.get()).isEqualTo(2);
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

        @Override
        public Investor ensureMfInvestmentAccount(UUID investorId) {
            return investor;
        }
    }

    private Investor verifiedInvestor(UUID distributorId) {
        Investor investor = new Investor();
        investor.setDistributorId(distributorId);
        investor.setKycStatus(KycStatus.COMPLETED);
        investor.setBankVerificationStatus(BankVerificationStatus.VERIFIED);
        investor.setCybrillaInvestorId("invp-test");
        investor.setExternalMfInvestmentAccountId("mfia-test");
        return investor;
    }

    private TransactionOrderRepository savingOrderRepository(List<TransactionOrder> savedOrders) {
        return (TransactionOrderRepository) Proxy.newProxyInstance(
                TransactionOrderRepository.class.getClassLoader(),
                new Class<?>[]{TransactionOrderRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        TransactionOrder order = (TransactionOrder) args[0];
                        savedOrders.add(order);
                        yield order;
                    }
                    case "findAll", "findByInvestorId", "findByDistributorId" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private CybrillaClient failingCybrillaClient(AtomicInteger externalCreateCalls, int failOnCall) {
        return (CybrillaClient) Proxy.newProxyInstance(
                CybrillaClient.class.getClassLoader(),
                new Class<?>[]{CybrillaClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createOrder" -> {
                        int call = externalCreateCalls.incrementAndGet();
                        if (call == failOnCall) {
                            throw new IllegalStateException("Cybrilla failed");
                        }
                        yield "external-order-" + call;
                    }
                    case "generateInvestorActionUrl" -> "https://example.test/action";
                    case "fetchProductSchemes" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private CybrillaClient actionUrlCybrillaClient() {
        return (CybrillaClient) Proxy.newProxyInstance(
                CybrillaClient.class.getClassLoader(),
                new Class<?>[]{CybrillaClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createOrder" -> "external-order";
                    case "generateInvestorActionUrl" -> {
                        TransactionOrder order = (TransactionOrder) args[0];
                        yield "/investor-actions/" + order.getInvestorActionToken();
                    }
                    case "fetchProductSchemes" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
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

    private ProductSchemeRepository productSchemeRepository() {
        return (ProductSchemeRepository) Proxy.newProxyInstance(
                ProductSchemeRepository.class.getClassLoader(),
                new Class<?>[]{ProductSchemeRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.of(productScheme());
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private ProductScheme productScheme() {
        ProductScheme productScheme = new ProductScheme();
        productScheme.setSchemeName("Test Scheme");
        productScheme.setAmcName("Test AMC");
        productScheme.setCategory(ProductCategory.MF);
        productScheme.setExternalSchemeCode("INF000000001");
        productScheme.setExternalIsin("INF000000001");
        return productScheme;
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

    private static class CountingAuditService extends AuditService {

        private final AtomicInteger calls;

        CountingAuditService(AtomicInteger calls) {
            super(null);
            this.calls = calls;
        }

        @Override
        public void log(String entityType, UUID entityId, String actionType, UUID actorId, String detailsJson) {
            calls.incrementAndGet();
        }
    }

    private static class CountingNotificationService extends NotificationService {

        private final AtomicInteger calls;

        CountingNotificationService(AtomicInteger calls) {
            super(null);
            this.calls = calls;
        }

        @Override
        public Notification createForDistributor(
                UUID distributorId,
                UUID investorId,
                NotificationType type,
                String title,
                String message
        ) {
            calls.incrementAndGet();
            return null;
        }
    }

    private static class CountingTransactionManager extends AbstractPlatformTransactionManager {

        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        protected Object doGetTransaction() throws TransactionException {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
            rollbacks.incrementAndGet();
        }

        int commits() {
            return commits.get();
        }

        int rollbacks() {
            return rollbacks.get();
        }
    }
}
