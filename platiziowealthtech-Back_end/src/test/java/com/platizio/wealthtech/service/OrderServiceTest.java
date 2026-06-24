package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.domain.Notification;
import com.platizio.wealthtech.domain.NotificationType;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.RedemptionRecord;
import com.platizio.wealthtech.domain.RedemptionStatus;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.BulkOrderCreateRequest;
import com.platizio.wealthtech.dto.OrderCreateRequest;
import com.platizio.wealthtech.dto.WithdrawalRequest;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.RedemptionRecordRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
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
                productSchemeRepository(),
                null,
                null
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
                null,
                null,
                null
        );

        TransactionOrder order = orderService.createOrder(request, distributorId);

        assertThat(order.getInvestorActionToken()).isNotBlank();
        assertThat(order.getInvestorActionUrl()).isEqualTo("/investor-actions/" + order.getInvestorActionToken());
    }

    @Test
    void createOrderRejectsLumpsumWithNullZeroOrNegativeAmount() {
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
                productSchemeRepository(),
                null,
                null
        );

        for (BigDecimal amount : Arrays.asList(null, BigDecimal.ZERO, new BigDecimal("-100"))) {
            OrderCreateRequest request = new OrderCreateRequest(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    TransactionType.LUMPSUM_PURCHASE,
                    amount,
                    null,
                    "NET_BANKING",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            assertThatThrownBy(() -> orderService.createOrder(request, distributorId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Lumpsum purchase amount must be greater than 0");
        }

        // Rejected before any local row is persisted.
        assertThat(savedOrders).isEmpty();
    }

    @Test
    void createOrderMarksOrderFailedAndRethrowsWhenProviderRejectsSubmit() {
        UUID distributorId = UUID.randomUUID();
        List<TransactionOrder> savedOrders = new ArrayList<>();
        OrderService orderService = new OrderService(
                savingOrderRepository(savedOrders),
                null,
                new FixedInvestorService(verifiedInvestor(distributorId)),
                new CountingAuditService(new AtomicInteger()),
                new CountingNotificationService(new AtomicInteger()),
                apiErrorCybrillaClient(),
                null,
                productSchemeRepository(),
                null,
                null
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
                null,
                null,
                null
        );

        assertThatThrownBy(() -> orderService.createOrder(request, distributorId))
                .isInstanceOf(CybrillaApiException.class)
                .hasMessage("provider rejected order");

        TransactionOrder persisted = savedOrders.get(savedOrders.size() - 1);
        assertThat(persisted.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(persisted.getOrderStatus()).isNotEqualTo(OrderStatus.CREATED);
        assertThat(persisted.getInvestorActionUrl()).isNull();
        assertThat(persisted.getFailureReason()).contains("provider rejected order");
    }

    @Test
    void createRedemptionDraftPersistsPendingActionWithoutCallingProvider() {
        UUID distributorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        TransactionOrder order = new TransactionOrder();
        order.setDistributorId(distributorId);
        order.setInvestorId(investorId);
        order.setProductSchemeId(UUID.randomUUID());
        order.setExternalOrderId("mfp-external-1");
        order.setAmount(BigDecimal.TEN);
        List<RedemptionRecord> savedRedemptions = new ArrayList<>();
        AtomicInteger auditCalls = new AtomicInteger();
        AtomicInteger notificationCalls = new AtomicInteger();
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        OrderService orderService = new OrderService(
                singleOrderRepository(order),
                savingRedemptionRepository(savedRedemptions),
                new FixedInvestorService(verifiedInvestor(distributorId)),
                new CountingAuditService(auditCalls),
                new CountingNotificationService(notificationCalls),
                cybrillaClient,
                null,
                productSchemeRepository(),
                approvedApprovalService(),
                null
        );

        RedemptionRecord draft = orderService.createRedemptionDraft(orderId, distributorId);

        // Draft persists in PENDING_INVESTOR_ACTION and performs NO provider redemption.
        assertThat(draft.getRedemptionStatus()).isEqualTo(RedemptionStatus.PENDING_INVESTOR_ACTION);
        assertThat(draft.getExternalRedemptionId()).isNull();
        assertThat(savedRedemptions).hasSize(1);
        assertThat(savedRedemptions.get(0).getOrderId()).isEqualTo(orderId);
        verify(cybrillaClient, never()).createRedemption(any(), any(), any());
        assertThat(auditCalls.get()).isEqualTo(1);
        assertThat(notificationCalls.get()).isEqualTo(1);
    }

    @Test
    void createRedemptionDraftHonorsPartialAmountInsteadOfFullOrder() {
        // FIX 4: a partial AMOUNT request must persist exactly the requested value on the
        // draft (and leave units null for the provider to quote) — NOT the full order amount.
        UUID distributorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        TransactionOrder order = new TransactionOrder();
        order.setDistributorId(distributorId);
        order.setInvestorId(investorId);
        order.setProductSchemeId(UUID.randomUUID());
        order.setExternalOrderId("mfp-external-1");
        order.setAmount(new BigDecimal("25000"));
        order.setUnits(new BigDecimal("1000"));
        List<RedemptionRecord> savedRedemptions = new ArrayList<>();
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        OrderService orderService = new OrderService(
                singleOrderRepository(order),
                savingRedemptionRepository(savedRedemptions),
                new FixedInvestorService(verifiedInvestor(distributorId)),
                new CountingAuditService(new AtomicInteger()),
                new CountingNotificationService(new AtomicInteger()),
                cybrillaClient,
                null,
                productSchemeRepository(),
                approvedApprovalService(),
                null
        );

        RedemptionRecord draft = orderService.createRedemptionDraft(
                orderId, distributorId, WithdrawalRequest.WithdrawalMode.AMOUNT, new BigDecimal("5000"), false);

        assertThat(draft.getAmount()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(draft.getUnits()).isNull();
        assertThat(savedRedemptions).hasSize(1);
        verify(cybrillaClient, never()).createRedemption(any(), any(), any());
    }

    @Test
    void createRedemptionDraftHonorsPartialUnits() {
        // FIX 4: a partial UNITS request persists the requested units (amount left null).
        UUID distributorId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        TransactionOrder order = new TransactionOrder();
        order.setDistributorId(distributorId);
        order.setInvestorId(UUID.randomUUID());
        order.setProductSchemeId(UUID.randomUUID());
        order.setExternalOrderId("mfp-external-1");
        order.setAmount(new BigDecimal("25000"));
        order.setUnits(new BigDecimal("1000"));
        List<RedemptionRecord> savedRedemptions = new ArrayList<>();
        OrderService orderService = new OrderService(
                singleOrderRepository(order),
                savingRedemptionRepository(savedRedemptions),
                new FixedInvestorService(verifiedInvestor(distributorId)),
                new CountingAuditService(new AtomicInteger()),
                new CountingNotificationService(new AtomicInteger()),
                mock(CybrillaClient.class),
                null,
                productSchemeRepository(),
                approvedApprovalService(),
                null
        );

        RedemptionRecord draft = orderService.createRedemptionDraft(
                orderId, distributorId, WithdrawalRequest.WithdrawalMode.UNITS, new BigDecimal("250"), false);

        assertThat(draft.getUnits()).isEqualByComparingTo(new BigDecimal("250"));
        assertThat(draft.getAmount()).isNull();
    }

    @Test
    void createRedemptionDraftFullRedemptionCopiesWholeHolding() {
        // FIX 4: fullRedemption=true copies the order's amount AND units (value ignored).
        UUID distributorId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        TransactionOrder order = new TransactionOrder();
        order.setDistributorId(distributorId);
        order.setInvestorId(UUID.randomUUID());
        order.setProductSchemeId(UUID.randomUUID());
        order.setExternalOrderId("mfp-external-1");
        order.setAmount(new BigDecimal("25000"));
        order.setUnits(new BigDecimal("1000"));
        List<RedemptionRecord> savedRedemptions = new ArrayList<>();
        OrderService orderService = new OrderService(
                singleOrderRepository(order),
                savingRedemptionRepository(savedRedemptions),
                new FixedInvestorService(verifiedInvestor(distributorId)),
                new CountingAuditService(new AtomicInteger()),
                new CountingNotificationService(new AtomicInteger()),
                mock(CybrillaClient.class),
                null,
                productSchemeRepository(),
                approvedApprovalService(),
                null
        );

        RedemptionRecord draft = orderService.createRedemptionDraft(
                orderId, distributorId, WithdrawalRequest.WithdrawalMode.AMOUNT, null, true);

        assertThat(draft.getAmount()).isEqualByComparingTo(new BigDecimal("25000"));
        assertThat(draft.getUnits()).isEqualByComparingTo(new BigDecimal("1000"));
    }

    @Test
    void createRedemptionDraftRejectsNonPositivePartialValue() {
        // FIX 4: a non-full redemption with a missing/zero value is a 400 (IllegalArgumentException).
        UUID distributorId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        TransactionOrder order = new TransactionOrder();
        order.setDistributorId(distributorId);
        order.setInvestorId(UUID.randomUUID());
        order.setProductSchemeId(UUID.randomUUID());
        order.setExternalOrderId("mfp-external-1");
        order.setAmount(new BigDecimal("25000"));
        OrderService orderService = new OrderService(
                singleOrderRepository(order),
                savingRedemptionRepository(new ArrayList<>()),
                new FixedInvestorService(verifiedInvestor(distributorId)),
                new CountingAuditService(new AtomicInteger()),
                new CountingNotificationService(new AtomicInteger()),
                mock(CybrillaClient.class),
                null,
                productSchemeRepository(),
                approvedApprovalService(),
                null
        );

        assertThatThrownBy(() -> orderService.createRedemptionDraft(
                orderId, distributorId, WithdrawalRequest.WithdrawalMode.AMOUNT, BigDecimal.ZERO, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitRedemptionToProviderAssertsGateThenCallsProvider() {
        UUID distributorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID redemptionId = UUID.randomUUID();
        TransactionOrder order = new TransactionOrder();
        order.setDistributorId(distributorId);
        order.setInvestorId(investorId);
        order.setProductSchemeId(UUID.randomUUID());
        order.setExternalOrderId("mfp-external-1");
        order.setAmount(BigDecimal.TEN);
        RedemptionRecord draft = new RedemptionRecord();
        org.springframework.test.util.ReflectionTestUtils.setField(draft, "id", redemptionId);
        draft.setOrderId(orderId);
        draft.setInvestorId(investorId);
        draft.setRedemptionStatus(RedemptionStatus.PENDING_INVESTOR_ACTION);
        draft.setAmount(BigDecimal.TEN);

        List<RedemptionRecord> savedRedemptions = new ArrayList<>();
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        when(cybrillaClient.createRedemption(any(), any(), any())).thenReturn("external-redemption-1");
        TransactionApprovalService approvalService = approvedApprovalService();

        OrderService orderService = new OrderService(
                orderByIdRepository(order),
                findByIdRedemptionRepository(draft, savedRedemptions),
                new FixedInvestorService(verifiedInvestor(distributorId)),
                new CountingAuditService(new AtomicInteger()),
                new CountingNotificationService(new AtomicInteger()),
                cybrillaClient,
                null,
                productSchemeRepository(),
                approvalService,
                null
        );

        RedemptionRecord submitted = orderService.submitRedemptionToProvider(redemptionId);

        // The gate is consulted (and consumed) BEFORE the provider redemption fires.
        verify(approvalService).assertApprovedAndConsume(eq(redemptionId), any());
        verify(cybrillaClient).createRedemption(any(), any(), any());
        assertThat(submitted.getExternalRedemptionId()).isEqualTo("external-redemption-1");
        assertThat(submitted.getRedemptionStatus()).isEqualTo(RedemptionStatus.SUBMITTED);
    }

    @Test
    void submitRedemptionToProviderBlockedByGateNeverCallsProvider() {
        UUID distributorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID redemptionId = UUID.randomUUID();
        TransactionOrder order = new TransactionOrder();
        order.setDistributorId(distributorId);
        order.setInvestorId(investorId);
        order.setProductSchemeId(UUID.randomUUID());
        order.setExternalOrderId("mfp-external-1");
        order.setAmount(BigDecimal.TEN);
        RedemptionRecord draft = new RedemptionRecord();
        org.springframework.test.util.ReflectionTestUtils.setField(draft, "id", redemptionId);
        draft.setOrderId(orderId);
        draft.setInvestorId(investorId);
        draft.setRedemptionStatus(RedemptionStatus.PENDING_INVESTOR_ACTION);

        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        TransactionApprovalService approvalService = mock(TransactionApprovalService.class);
        doThrow(new IllegalStateException(
                "Investor 2FA approval required: no approved approval exists for this transaction."))
                .when(approvalService).assertApprovedAndConsume(any(), any());

        OrderService orderService = new OrderService(
                orderByIdRepository(order),
                findByIdRedemptionRepository(draft, new ArrayList<>()),
                new FixedInvestorService(verifiedInvestor(distributorId)),
                new CountingAuditService(new AtomicInteger()),
                new CountingNotificationService(new AtomicInteger()),
                cybrillaClient,
                null,
                productSchemeRepository(),
                approvalService,
                null
        );

        assertThatThrownBy(() -> orderService.submitRedemptionToProvider(redemptionId))
                .isInstanceOf(IllegalStateException.class);

        // Gate blocked → the real Cybrilla redemption must never fire.
        verify(cybrillaClient, never()).createRedemption(any(), any(), any());
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
                productSchemeRepository(),
                null,
                null
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

    /** A 2FA engine whose gate passes (challenge APPROVED) so the provider submit proceeds. */
    private TransactionApprovalService approvedApprovalService() {
        TransactionApprovalService service = mock(TransactionApprovalService.class);
        doNothing().when(service).assertApprovedAndConsume(any(), any());
        return service;
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
                    case "fetchMfPurchase" -> pendingMfPurchaseNode();
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
                    case "fetchMfPurchase" -> pendingMfPurchaseNode();
                    case "generateInvestorActionUrl" -> {
                        TransactionOrder order = (TransactionOrder) args[0];
                        yield "/investor-actions/" + order.getInvestorActionToken();
                    }
                    case "fetchProductSchemes" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private CybrillaClient apiErrorCybrillaClient() {
        return (CybrillaClient) Proxy.newProxyInstance(
                CybrillaClient.class.getClassLoader(),
                new Class<?>[]{CybrillaClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createOrder" -> throw new CybrillaApiException("provider rejected order");
                    case "fetchMfPurchase" -> pendingMfPurchaseNode();
                    case "generateInvestorActionUrl" -> "https://example.test/action";
                    case "fetchProductSchemes" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private com.fasterxml.jackson.databind.JsonNode pendingMfPurchaseNode() throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"object":"mf_purchase","id":"external-order","state":"pending"}
                """);
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

    private TransactionOrderRepository singleOrderRepository(TransactionOrder order) {
        return (TransactionOrderRepository) Proxy.newProxyInstance(
                TransactionOrderRepository.class.getClassLoader(),
                new Class<?>[]{TransactionOrderRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.of(order);
                    case "save" -> args[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private RedemptionRecordRepository savingRedemptionRepository(List<RedemptionRecord> savedRedemptions) {
        return (RedemptionRecordRepository) Proxy.newProxyInstance(
                RedemptionRecordRepository.class.getClassLoader(),
                new Class<?>[]{RedemptionRecordRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        RedemptionRecord record = (RedemptionRecord) args[0];
                        savedRedemptions.add(record);
                        yield record;
                    }
                    case "findByOrderId" -> List.of();
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    /** Order repository that returns the supplied order for any findById. */
    private TransactionOrderRepository orderByIdRepository(TransactionOrder order) {
        return (TransactionOrderRepository) Proxy.newProxyInstance(
                TransactionOrderRepository.class.getClassLoader(),
                new Class<?>[]{TransactionOrderRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.of(order);
                    case "save" -> args[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    /** Redemption repository whose findById returns the drafted record (for submit). */
    private RedemptionRecordRepository findByIdRedemptionRepository(
            RedemptionRecord draft, List<RedemptionRecord> savedRedemptions) {
        return (RedemptionRecordRepository) Proxy.newProxyInstance(
                RedemptionRecordRepository.class.getClassLoader(),
                new Class<?>[]{RedemptionRecordRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.of(draft);
                    case "save" -> {
                        RedemptionRecord record = (RedemptionRecord) args[0];
                        savedRedemptions.add(record);
                        yield record;
                    }
                    case "findByOrderId" -> List.of();
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
