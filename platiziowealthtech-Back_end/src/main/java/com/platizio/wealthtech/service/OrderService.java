package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.*;
import com.platizio.wealthtech.dto.BulkOrderCreateRequest;
import com.platizio.wealthtech.dto.OrderCreateRequest;
import com.platizio.wealthtech.dto.SipCancelRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.CybrillaUnavailableException;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.RedemptionRecordRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import com.platizio.wealthtech.security.JwtAuthPrincipal;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;


@Service
public class OrderService {

    private final TransactionOrderRepository transactionOrderRepository;
    private final RedemptionRecordRepository redemptionRecordRepository;
    private final InvestorService investorService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private final CybrillaClient cybrillaClient;
    private final TransactionTemplate transactionTemplate;
    private final ProductSchemeRepository productSchemeRepository;

    public OrderService(
            TransactionOrderRepository transactionOrderRepository,
            RedemptionRecordRepository redemptionRecordRepository,
            InvestorService investorService,
            AuditService auditService,
            NotificationService notificationService,
            CybrillaClient cybrillaClient,
            PlatformTransactionManager transactionManager,
            ProductSchemeRepository productSchemeRepository
    ) {
        this.transactionOrderRepository = transactionOrderRepository;
        this.redemptionRecordRepository = redemptionRecordRepository;
        this.investorService = investorService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.cybrillaClient = cybrillaClient;
        this.transactionTemplate = transactionManager == null ? null : perOrderTransactionTemplate(transactionManager);
        this.productSchemeRepository = productSchemeRepository;
    }

    public List<TransactionOrder> listOrdersByInvestor(UUID investorId) {
        return transactionOrderRepository.findByInvestorId(investorId);
    }

    /**
     * Scoped variant: only returns the investor's orders when the requester owns the investor
     * (or is ADMIN). Closes BUG-003 — the controller-facing endpoint was previously an IDOR.
     */
    public List<TransactionOrder> listOrdersByInvestor(UUID investorId, JwtAuthPrincipal principal) {
        if (principal.getRole() == com.platizio.wealthtech.domain.DistributorRole.ADMIN) {
            return listOrdersByInvestor(investorId);
        }
        Investor investor = investorService.getInvestor(investorId);
        assertOrderOwnership(investor.getDistributorId(), principal);
        return transactionOrderRepository.findByInvestorId(investorId);
    }

    @Transactional(readOnly = true)
    public Page<TransactionOrder> listOrders(
            UUID requesterId,
            DistributorRole requesterRole,
            String statusFilter,
            LocalDate fromDate,
            LocalDate toDate,
            int page,
            int size,
            String sortBy,
            String direction
    ) {
        PageRequest pageRequest = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(resolveSortDirection(direction), resolveOrderSortField(sortBy))
        );

        Specification<TransactionOrder> spec = Specification.where(null);
        if (requesterRole != DistributorRole.ADMIN) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("distributorId"), requesterId));
        }

        List<OrderStatus> statuses = resolveOrderStatuses(statusFilter);
        if (!statuses.isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("orderStatus").in(statuses));
        }
        if (fromDate != null) {
            OffsetDateTime from = OffsetDateTime.of(fromDate, LocalTime.MIN, ZoneOffset.UTC);
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (toDate != null) {
            OffsetDateTime to = OffsetDateTime.of(toDate, LocalTime.MAX, ZoneOffset.UTC);
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }

        return transactionOrderRepository.findAll(spec, pageRequest);
    }

    @Transactional(readOnly = true)
    public List<TransactionOrder> listOrdersByDistributor(UUID distributorId) {
        logger.info("Fetching orders for distributor {}", distributorId);
        List<TransactionOrder> orders = transactionOrderRepository.findByDistributorId(distributorId);
        logger.info("Found {} orders for distributor {}", orders.size(), distributorId);
        return orders;
    }

    /**
     * Scoped variant: ADMIN sees any distributor's orders; non-admins can only list their own
     * (or, for MASTER_DISTRIBUTOR, their downline's). Closes BUG-003.
     */
    @Transactional(readOnly = true)
    public List<TransactionOrder> listOrdersByDistributor(UUID distributorId, JwtAuthPrincipal principal) {
        assertOrderOwnership(distributorId, principal);
        return listOrdersByDistributor(distributorId);
    }

    public TransactionOrder getOrder(UUID orderId) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        return syncLumpsumOrderFromProvider(order);
    }

    /**
     * Scoped variant: throws AccessDeniedException when the requester is not the order's
     * distributor and is not an ADMIN. Closes BUG-003 IDOR.
     */
    public TransactionOrder getOrder(UUID orderId, JwtAuthPrincipal principal) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        assertOrderOwnership(order.getDistributorId(), principal);
        return syncLumpsumOrderFromProvider(order);
    }

    @Transactional
    public TransactionOrder syncLumpsumOrderFromProvider(UUID orderId) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        return syncLumpsumOrderFromProvider(order);
    }

    /**
     * Scoped variant: ownership-checked sync. Closes BUG-003.
     */
    @Transactional
    public TransactionOrder syncLumpsumOrderFromProvider(UUID orderId, JwtAuthPrincipal principal) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        assertOrderOwnership(order.getDistributorId(), principal);
        return syncLumpsumOrderFromProvider(order);
    }

    /**
     * Aligns local lumpsum order rows with FP when review fails or completes after create-time polling stopped.
     */
    @Transactional
    public TransactionOrder syncLumpsumOrderFromProvider(TransactionOrder order) {
        if (order == null
                || order.getTransactionType() != TransactionType.LUMPSUM_PURCHASE
                || !StringUtils.hasText(order.getExternalOrderId())) {
            return order;
        }
        if (order.getOrderStatus() == OrderStatus.SUCCESSFUL
                || order.getOrderStatus() == OrderStatus.COMPLETED
                || order.getOrderStatus() == OrderStatus.CANCELLED) {
            return order;
        }
        try {
            JsonNode purchase = cybrillaClient.fetchMfPurchase(order.getExternalOrderId());
            String state = purchase.path("state").asText("");
            if ("failed".equalsIgnoreCase(state) || "cancelled".equalsIgnoreCase(state)) {
                order.setOrderStatus(OrderStatus.FAILED);
                order.setFailureReason(describeMfPurchaseFailure(state, purchase));
                order.setInvestorActionUrl(null);
                logger.warn(
                        "order_provider_sync status='failed' order_id='{}' external_order_id='{}' reason='{}' gateway_remarks='{}' purchase_json='{}'",
                        order.getId(),
                        order.getExternalOrderId(),
                        order.getFailureReason(),
                        purchase.path("gateway_remarks").asText(null),
                        purchase.toString()
                );
                return transactionOrderRepository.save(order);
            }
            if ("pending".equalsIgnoreCase(state)
                    && (order.getOrderStatus() == OrderStatus.CREATED
                    || order.getOrderStatus() == OrderStatus.RETRY_AVAILABLE
                    || order.getOrderStatus() == OrderStatus.PROCESSING)) {
                order.setOrderStatus(OrderStatus.PENDING_INVESTOR_ACTION);
                order.setFailureReason(null);
                return transactionOrderRepository.save(order);
            }
        } catch (RuntimeException ex) {
            logger.debug(
                    "order_provider_sync status='skipped' order_id='{}' reason='{}'",
                    order.getId(),
                    ex.getMessage()
            );
        }
        return order;
    }

    /**
     * Idempotent webhook reconcile for FP {@code mf_purchase.*}/{@code payment.*} events.
     * Mirrors the KYC/bank webhook handlers: look up the local order by its stored FP
     * external id; if there is no local match (e.g. the event carries a payment id we do
     * not track) return an {@code ignored_*} no-op; otherwise re-fetch authoritative
     * provider state via {@link #syncLumpsumOrderFromProvider(UUID)} and audit consistently.
     */
    @Transactional
    public ExternalOrderSyncResult handleOrderWebhook(String externalId, String eventType) {
        if (!StringUtils.hasText(externalId)) {
            return new ExternalOrderSyncResult("ignored_missing_external_id", eventType, externalId, null, null);
        }
        java.util.Optional<TransactionOrder> existing = transactionOrderRepository.findByExternalOrderId(externalId.trim());
        if (existing.isEmpty()) {
            return new ExternalOrderSyncResult("ignored_no_matching_order", eventType, externalId, null, null);
        }
        TransactionOrder synced = syncLumpsumOrderFromProvider(existing.get().getId());
        auditService.log(
                "ORDER",
                synced.getId(),
                "EXTERNAL_ORDER_WEBHOOK_SYNCED",
                synced.getDistributorId(),
                "{\"externalOrderId\":\"" + externalId + "\",\"eventType\":\"" + (eventType == null ? "" : eventType)
                        + "\",\"status\":\"" + synced.getOrderStatus() + "\"}"
        );
        return new ExternalOrderSyncResult(
                "synced",
                eventType,
                externalId,
                synced.getId(),
                synced.getOrderStatus()
        );
    }

    /** Small reconcile result for {@link #handleOrderWebhook(String, String)} (mirrors ExternalKycSyncResponse). */
    public record ExternalOrderSyncResult(
            String status,
            String eventType,
            String externalId,
            UUID orderId,
            OrderStatus orderStatus
    ) {}

    public List<RedemptionRecord> listRedemptionsByOrder(UUID orderId) {
        return redemptionRecordRepository.findByOrderId(orderId);
    }

    /**
     * Scoped variant: ownership-checked redemption listing. Closes BUG-003.
     */
    public List<RedemptionRecord> listRedemptionsByOrder(UUID orderId, JwtAuthPrincipal principal) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        assertOrderOwnership(order.getDistributorId(), principal);
        return redemptionRecordRepository.findByOrderId(orderId);
    }

    private void assertOrderOwnership(UUID orderDistributorId, JwtAuthPrincipal principal) {
        if (principal.getRole() == com.platizio.wealthtech.domain.DistributorRole.ADMIN) {
            return;
        }
        if (!principal.getDistributorId().equals(orderDistributorId)) {
            throw new AccessDeniedException("Order does not belong to the authenticated distributor");
        }
    }

    public List<TransactionOrder> createOrders(BulkOrderCreateRequest request, UUID distributorId) {
        if (transactionTemplate == null) {
            throw new IllegalStateException("Bulk order transaction template is not configured");
        }
        List<TransactionOrder> createdOrders = new ArrayList<>();
        for (UUID investorId : request.investorIds().stream().distinct().toList()) {
            transactionTemplate.executeWithoutResult(status -> createdOrders.add(createOrder(new OrderCreateRequest(
                    investorId,
                    request.productSchemeId(),
                    null,
                    request.transactionType(),
                    request.amount(),
                    request.units(),
                    request.paymentMode(),
                    request.mandateMode(),
                    null,
                    null,
                    null,
                    null,
                    null
            ), distributorId)));
        }
        return createdOrders;
    }

    private TransactionTemplate perOrderTransactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * Intentionally not {@code @Transactional}: Cybrilla/FP pre-flight (profile, MFIA, BAV, purchase
     * create) can take 30–90s on first order. A surrounding transaction would hold a DB connection
     * for the entire provider round-trip and block the Ledger UI.
     */
    public TransactionOrder createOrder(OrderCreateRequest request, UUID distributorId) {
        Investor investor = investorService.getInvestor(request.investorId());

        if (!distributorId.equals(investor.getDistributorId())) {
            throw new AccessDeniedException("Cannot create order for another distributor's investor");
        }
        if (investor.getKycStatus() != KycStatus.COMPLETED) {
            throw new IllegalStateException("KYC must be completed before order creation");
        }
        if (investor.getBankVerificationStatus() != BankVerificationStatus.VERIFIED) {
            throw new IllegalStateException("Verified bank account is required before order creation");
        }
        validateSipRequest(request);
        validateLumpsumRequest(request);
        ProductScheme productScheme = resolveProductScheme(request);
        ProductSchemeOrderSupport.requirePoaOrderable(productScheme);

        TransactionOrder order = new TransactionOrder();
        order.setInvestorId(request.investorId());
        order.setDistributorId(distributorId);
        order.setProductSchemeId(productScheme.getId());
        order.setTransactionType(request.transactionType());
        order.setAmount(request.amount());
        order.setUnits(request.units());
        order.setPaymentMode(request.paymentMode());
        order.setMandateMode(request.mandateMode());
        order.setSipFrequency(normalizeSipFrequency(request.sipFrequency()));
        order.setSipStartDate(request.sipStartDate());
        order.setSipInstalments(request.sipInstalments());
        order.setInvestorActionToken(UUID.randomUUID().toString());
        order.setOrderStatus(OrderStatus.CREATED);

        // Persist the local order first so a later provider failure does not discard
        // the distributor's order; the external sync can then be retried.
        TransactionOrder saved = transactionOrderRepository.save(order);

        Investor investorWithAccount;
        try {
            investorWithAccount = investorService.ensureMfInvestmentAccount(request.investorId());
        } catch (CybrillaUnavailableException ex) {
            persistDeferredOrder(saved,
                    "Order saved locally, but the MF investment account could not be opened because "
                            + "Cybrilla/Fintech Primitives is unreachable (network/DNS). Retry once connectivity is restored.");
            throw new CybrillaUnavailableException(
                    "Order deferred: MF investment account could not be created because the provider is unreachable. "
                            + ex.getMessage(),
                    ex);
        }

        boolean deferSipMandateSubmit = saved.getTransactionType() == TransactionType.SIP
                && isMandatePaymentMode(saved.getPaymentMode());
        String externalOrderId = null;
        if (!deferSipMandateSubmit) {
            try {
                externalOrderId = cybrillaClient.createOrder(saved, investorWithAccount, productScheme);
            } catch (CybrillaUnavailableException ex) {
                persistDeferredOrder(saved,
                        "Order saved locally, but it could not be submitted because Cybrilla/Fintech Primitives "
                                + "is unreachable (network/DNS). Retry once connectivity is restored.");
                throw new CybrillaUnavailableException(
                        "Order deferred: it could not be submitted because the provider is unreachable. " + ex.getMessage(),
                        ex);
            } catch (CybrillaApiException ex) {
                // Provider rejected the submit (validation/business error). The local row was already
                // persisted as CREATED with no action URL; mark it FAILED so it is not left orphaned,
                // then rethrow so the caller still sees the provider error.
                saved.setOrderStatus(OrderStatus.FAILED);
                saved.setFailureReason("Order submission was rejected by Cybrilla/Fintech Primitives: " + ex.getMessage());
                saved.setInvestorActionUrl(null);
                transactionOrderRepository.save(saved);
                throw ex;
            }
            saved.setExternalOrderId(externalOrderId);
            if (saved.getTransactionType() == TransactionType.LUMPSUM_PURCHASE) {
                scheduleLumpsumPurchaseReviewReconciliation(saved.getId());
            }
        }

        if (saved.getOrderStatus() != OrderStatus.FAILED && saved.getOrderStatus() != OrderStatus.PROCESSING) {
            saved.setOrderStatus(OrderStatus.PENDING_INVESTOR_ACTION);
            saved.setInvestorActionUrl(cybrillaClient.generateInvestorActionUrl(saved));
        } else if (saved.getOrderStatus() == OrderStatus.PROCESSING) {
            saved.setInvestorActionUrl(cybrillaClient.generateInvestorActionUrl(saved));
        } else {
            saved.setInvestorActionUrl(null);
        }
        saved = transactionOrderRepository.save(saved);

        String auditDetails = externalOrderId == null
                ? "{\"deferredProviderSubmit\":true,\"reason\":\"sip_mandate_flow\"}"
                : "{\"externalOrderId\":\"" + externalOrderId + "\"}";
        auditService.log("ORDER", saved.getId(), "ORDER_CREATED", distributorId, auditDetails);
        notificationService.createForDistributor(distributorId, request.investorId(), NotificationType.PAYMENT_PENDING, "Investor action pending", "Order created and waiting for investor action.");
        return saved;
    }

    /**
     * Commits the local order in a RETRY_AVAILABLE state in its OWN transaction so it
     * survives the rollback of the surrounding @Transactional createOrder method when we
     * rethrow a provider-unreachable error. Mirrors the deferred-persist used for KYC.
     */
    private static final int LUMPSUM_CREATE_REVIEW_POLL_ATTEMPTS = 24;
    private static final long LUMPSUM_CREATE_REVIEW_POLL_INTERVAL_MS = 1_000L;

    private static final long LUMPSUM_DELAYED_FAILURE_RECHECK_MS = 15_000L;

    private void scheduleLumpsumPurchaseReviewReconciliation(UUID orderId) {
        if (transactionTemplate == null || orderId == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    TransactionOrder order = transactionOrderRepository.findById(orderId).orElse(null);
                    if (order == null || !StringUtils.hasText(order.getExternalOrderId())) {
                        return;
                    }
                    reconcileLumpsumPurchaseAfterProviderCreate(order);
                    transactionOrderRepository.save(order);
                });
            } catch (RuntimeException ex) {
                logger.warn(
                        "order_create_review_poll_async status='failed' order_id='{}' reason='{}'",
                        orderId,
                        ex.getMessage()
                );
            }
        });
    }

    private void scheduleDelayedLumpsumFailureRecheck(UUID orderId) {
        if (transactionTemplate == null || orderId == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(LUMPSUM_DELAYED_FAILURE_RECHECK_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    TransactionOrder order = transactionOrderRepository.findById(orderId).orElse(null);
                    if (order == null || !StringUtils.hasText(order.getExternalOrderId())) {
                        return;
                    }
                    if (order.getOrderStatus() != OrderStatus.PENDING_INVESTOR_ACTION
                            && order.getOrderStatus() != OrderStatus.PROCESSING) {
                        return;
                    }
                    syncLumpsumOrderFromProvider(order);
                });
            } catch (RuntimeException ex) {
                logger.warn(
                        "order_delayed_failure_recheck status='failed' order_id='{}' reason='{}'",
                        orderId,
                        ex.getMessage()
                );
            }
        });
    }

    private void reconcileLumpsumPurchaseAfterProviderCreate(TransactionOrder order) {
        if (!StringUtils.hasText(order.getExternalOrderId())) {
            return;
        }
        String latestState = null;
        JsonNode latest = null;
        for (int attempt = 1; attempt <= LUMPSUM_CREATE_REVIEW_POLL_ATTEMPTS; attempt++) {
            try {
                latest = cybrillaClient.fetchMfPurchase(order.getExternalOrderId());
            } catch (CybrillaApiException ex) {
                logger.warn(
                        "order_create_review_poll status='fetch_failed' order_id='{}' external_order_id='{}' reason='{}'",
                        order.getId(),
                        order.getExternalOrderId(),
                        ex.getMessage()
                );
                return;
            }
            if (latest == null || latest.isNull()) {
                return;
            }
            latestState = latest.path("state").asText("");
            if ("pending".equalsIgnoreCase(latestState)
                    || "confirmed".equalsIgnoreCase(latestState)
                    || "submitted".equalsIgnoreCase(latestState)) {
                scheduleDelayedLumpsumFailureRecheck(order.getId());
                return;
            }
            if ("failed".equalsIgnoreCase(latestState) || "cancelled".equalsIgnoreCase(latestState)) {
                order.setOrderStatus(OrderStatus.FAILED);
                order.setFailureReason(describeMfPurchaseFailure(latestState, latest));
                logger.warn(
                        "order_create_review_poll status='terminal' order_id='{}' external_order_id='{}' provider_state='{}' reason='{}' failure_code='{}' gateway_remarks='{}' purchase_json='{}'",
                        order.getId(),
                        order.getExternalOrderId(),
                        latestState,
                        order.getFailureReason(),
                        latest == null ? null : latest.path("failure_code").asText(null),
                        latest == null ? null : latest.path("gateway_remarks").asText(null),
                        latest == null ? null : latest.toString()
                );
                return;
            }
            if ("under_review".equalsIgnoreCase(latestState)) {
                sleepCreateReviewPollInterval();
                continue;
            }
            return;
        }
        logger.info(
                "order_create_review_poll status='still_under_review' order_id='{}' external_order_id='{}' last_state='{}'",
                order.getId(),
                order.getExternalOrderId(),
                latestState
        );
        order.setOrderStatus(OrderStatus.PROCESSING);
        order.setFailureReason("Fintech Primitives is still reviewing this purchase. Please wait a moment and open the investor action link again.");
    }

    private static String describeMfPurchaseFailure(String state, JsonNode purchase) {
        String providerReason = mfPurchaseFailureReason(purchase);
        String message = "Fintech Primitives purchase review " + state;
        if (StringUtils.hasText(providerReason)) {
            return message + ": " + providerReason;
        }
        return message + ". Use sandbox amount ending in 0 (e.g. ₹5000) for success, or 1 to simulate failure.";
    }

    private static String mfPurchaseFailureReason(JsonNode purchase) {
        if (purchase == null || purchase.isNull()) {
            return null;
        }
        for (String field : List.of("failure_reason", "failure_code", "remarks", "gateway_remarks", "reason")) {
            if (purchase.hasNonNull(field) && StringUtils.hasText(purchase.get(field).asText())) {
                return purchase.get(field).asText().trim();
            }
        }
        JsonNode error = purchase.path("error");
        if (error.hasNonNull("message") && StringUtils.hasText(error.get("message").asText())) {
            return error.get("message").asText().trim();
        }
        return null;
    }

    private void sleepCreateReviewPollInterval() {
        try {
            Thread.sleep(LUMPSUM_CREATE_REVIEW_POLL_INTERVAL_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void persistDeferredOrder(TransactionOrder order, String message) {
        order.setOrderStatus(OrderStatus.RETRY_AVAILABLE);
        order.setFailureReason(message);
        if (transactionTemplate == null) {
            transactionOrderRepository.save(order);
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> transactionOrderRepository.save(order));
        } catch (RuntimeException persistEx) {
            logger.warn("order_deferred_persist_failed order_id='{}' reason='{}'",
                    order.getId(), persistEx.getMessage());
        }
    }

    @Transactional
    public TransactionOrder updateOrderStatus(UUID orderId, OrderStatus status, String failureReason, UUID actorId) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        order.setOrderStatus(status);
        order.setFailureReason(failureReason);
        TransactionOrder saved = transactionOrderRepository.save(order);
        auditService.log("ORDER", saved.getId(), "ORDER_STATUS_UPDATED", actorId, "{\"status\":\"" + status + "\"}");

        if (status == OrderStatus.SUCCESSFUL) {
            notificationService.createForDistributor(saved.getDistributorId(), saved.getInvestorId(), NotificationType.TRANSACTION_SUCCESSFUL, "Transaction successful", "Order completed successfully.");
        } else if (status == OrderStatus.FAILED) {
            notificationService.createForDistributor(saved.getDistributorId(), saved.getInvestorId(), NotificationType.TRANSACTION_FAILED, "Transaction failed", failureReason == null ? "Order failed." : failureReason);
        }
        return saved;
    }

    /**
     * Intentionally not {@code @Transactional} (mirrors {@link #createOrder}): the FP pre-flight
     * {@code ensureMfInvestmentAccount} and {@code cybrillaClient.createRedemption} are a 30–90s
     * provider round-trip. A surrounding transaction would hold a DB connection for that entire
     * round-trip. The only DB write here is the single {@code redemptionRecordRepository.save},
     * so there is no multi-write atomicity requirement to protect.
     */
    public RedemptionRecord createRedemption(UUID orderId, UUID actorId) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        if (!StringUtils.hasText(order.getExternalOrderId())) {
            throw new IllegalStateException(
                    "This holding has no Fintech Primitives purchase id. Redemption requires a purchase that was "
                            + "submitted through Cybrilla POA (not demo-only local rows).");
        }
        Investor investor = investorService.ensureMfInvestmentAccount(order.getInvestorId());
        ProductScheme productScheme = getProductSchemeById(order.getProductSchemeId());

        RedemptionRecord record = new RedemptionRecord();
        record.setOrderId(orderId);
        record.setInvestorId(order.getInvestorId());
        record.setRedemptionStatus(RedemptionStatus.CREATED);
        record.setAmount(order.getAmount());
        record.setUnits(order.getUnits());
        record.setExternalRedemptionId(cybrillaClient.createRedemption(order, investor, productScheme));

        RedemptionRecord saved = redemptionRecordRepository.save(record);
        auditService.log("REDEMPTION", saved.getId(), "REDEMPTION_CREATED", actorId, "{}");
        notificationService.createForDistributor(order.getDistributorId(), order.getInvestorId(), NotificationType.REDEMPTION_SUBMITTED, "Redemption submitted", "Redemption flow has started.");
        return saved;
    }

    /**
     * Ownership-checked variant: rejects callers that are not the order's distributor (or ADMIN)
     * BEFORE calling the FP provider. Closes BUG-003 — the previous path used actorId only for the
     * audit log, leaving a real IDOR.
     */
    public RedemptionRecord createRedemption(UUID orderId, JwtAuthPrincipal principal) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        assertOrderOwnership(order.getDistributorId(), principal);
        return createRedemption(orderId, principal.getDistributorId());
    }

    private static final Set<OrderStatus> CANCELLABLE_SIP_STATUSES = Set.of(
            OrderStatus.ACTIVE,
            OrderStatus.SUCCESSFUL,
            OrderStatus.PROCESSING,
            OrderStatus.SUBMITTED,
            OrderStatus.PAYMENT_PENDING,
            OrderStatus.PENDING_INVESTOR_ACTION,
            OrderStatus.CREATED,
            OrderStatus.RETRY_AVAILABLE
    );

    private static final Set<OrderStatus> TERMINAL_SIP_STATUSES = Set.of(
            OrderStatus.CANCELLED,
            OrderStatus.COMPLETED,
            OrderStatus.FAILED
    );

    /**
     * Cancels a SIP per FP docs: {@code POST /v2/mf_purchase_plans/cancel} with plan id + cancellation_code.
     * Keeps the row visible with {@link OrderStatus#CANCELLED} (never soft-deletes SIPs).
     */
    @Transactional
    public TransactionOrder cancelSipOrder(UUID orderId, UUID actorId, SipCancelRequest request) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        if (!actorId.equals(order.getDistributorId())) {
            throw new AccessDeniedException("Cannot cancel SIP for another distributor");
        }
        if (order.getTransactionType() != TransactionType.SIP) {
            throw new IllegalArgumentException("Only SIP orders can be cancelled through this endpoint");
        }
        return cancelSipOrder(order, actorId, request);
    }

    @Transactional
    public void deleteOrder(UUID orderId, UUID actorId) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        if (!actorId.equals(order.getDistributorId())) {
            throw new AccessDeniedException("Cannot delete order for another distributor");
        }
        if (order.getTransactionType() == TransactionType.SIP) {
            throw new IllegalStateException(
                    "SIP orders cannot be deleted. Use POST /orders/{id}/cancel to cancel the purchase plan with Fintech Primitives.");
        }
        order.setIsDeleted(true);
        order.setDeletedAt(LocalDateTime.now());
        transactionOrderRepository.save(order);
        auditService.log("ORDER", orderId, "DELETED", actorId, "{\"softDeleted\":true,\"reason\":\"User requested deletion\"}");
    }

    private TransactionOrder cancelSipOrder(TransactionOrder order, UUID actorId, SipCancelRequest request) {
        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("This SIP is already cancelled");
        }
        if (TERMINAL_SIP_STATUSES.contains(order.getOrderStatus())) {
            throw new IllegalStateException(
                    "SIP cannot be cancelled in status " + order.getOrderStatus() + ".");
        }
        if (!CANCELLABLE_SIP_STATUSES.contains(order.getOrderStatus())) {
            throw new IllegalStateException(
                    "SIP cannot be cancelled in status " + order.getOrderStatus() + ". Only active or in-progress SIPs can be cancelled.");
        }

        String cancellationCode = request == null || !StringUtils.hasText(request.cancellationCode())
                ? SipCancelRequest.DEFAULT_CANCELLATION_CODE
                : request.cancellationCode().trim();
        String cancellationReason = request == null ? null : request.cancellationReason();
        String providerState = null;

        if (StringUtils.hasText(order.getExternalOrderId()) && !isLocalOnlyPurchasePlanId(order.getExternalOrderId())) {
            JsonNode providerResponse = cybrillaClient.cancelPurchasePlan(
                    order.getExternalOrderId(),
                    cancellationCode,
                    cancellationReason
            );
            providerState = providerResponse.path("state").asText(null);
            if (providerState != null && !"cancelled".equalsIgnoreCase(providerState)) {
                throw new IllegalStateException(
                        "Fintech Primitives did not confirm cancellation (provider state='" + providerState + "').");
            }
        } else {
            logger.info(
                    "sip_cancel_local_only order_id='{}' reason='{}'",
                    order.getId(),
                    StringUtils.hasText(order.getExternalOrderId())
                            ? "demo_or_stub_plan"
                            : "no_fp_purchase_plan_id_yet"
            );
        }

        order.setOrderStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(LocalDateTime.now());
        order.setInvestorActionUrl(null);
        order.setFailureReason(null);
        TransactionOrder saved = transactionOrderRepository.save(order);
        auditService.log(
                "ORDER",
                order.getId(),
                "ORDER_CANCELLED",
                actorId,
                "{\"status\":\"CANCELLED\",\"externalOrderId\":\""
                        + (order.getExternalOrderId() == null ? "" : order.getExternalOrderId())
                        + "\",\"cancellationCode\":\""
                        + cancellationCode
                        + "\",\"providerState\":\""
                        + (providerState == null ? "local_only" : providerState)
                        + "\",\"provider\":\"POST /v2/mf_purchase_plans/cancel\"}"
        );
        notificationService.createForDistributor(
                order.getDistributorId(),
                order.getInvestorId(),
                NotificationType.RECURRING_PLAN_EVENT,
                "SIP cancelled",
                StringUtils.hasText(order.getExternalOrderId())
                        ? "SIP purchase plan cancelled with Fintech Primitives."
                        : "SIP registration cancelled before FP plan submission."
        );
        return saved;
    }

    /**
     * Demo seeders and early local-only SIP rows use stub ids (e.g. {@code fp_sip_demo_001}) that are not
     * real FP {@code mfpp_} purchase plans. Skip live cancel calls for those to avoid 502s in dev.
     */
    private static boolean isLocalOnlyPurchasePlanId(String externalOrderId) {
        if (!StringUtils.hasText(externalOrderId)) {
            return true;
        }
        String normalized = externalOrderId.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("mfpp_")) {
            return false;
        }
        return normalized.startsWith("fp_") || normalized.contains("_demo_") || normalized.startsWith("sandbox");
    }

    private boolean isMandatePaymentMode(String paymentMode) {
        return paymentMode != null && "MANDATE".equalsIgnoreCase(paymentMode.trim());
    }

    private void validateSipRequest(OrderCreateRequest request) {
        if (request.transactionType() != TransactionType.SIP) {
            return;
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.valueOf(500)) < 0) {
            throw new IllegalArgumentException("SIP amount must be at least 500");
        }
        if (request.sipStartDate() == null || !request.sipStartDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("SIP start date must be in the future");
        }
        String frequency = normalizeSipFrequency(request.sipFrequency());
        if (frequency == null) {
            frequency = "MONTHLY";
        }
        if (!"MONTHLY".equals(frequency) && !"QUARTERLY".equals(frequency)) {
            throw new IllegalArgumentException("SIP frequency must be MONTHLY or QUARTERLY");
        }
        if (request.sipInstalments() != null && request.sipInstalments() < 1) {
            throw new IllegalArgumentException("SIP instalments must be greater than 0");
        }
    }

    private void validateLumpsumRequest(OrderCreateRequest request) {
        if (request.transactionType() != TransactionType.LUMPSUM_PURCHASE) {
            return;
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Lumpsum purchase amount must be greater than 0");
        }
    }

    private String normalizeSipFrequency(String frequency) {
        return frequency == null ? null : frequency.trim().toUpperCase();
    }

    private ProductScheme getProductSchemeById(UUID productSchemeId) {
        if (productSchemeId == null) {
            throw new IllegalArgumentException("Product scheme is required");
        }
        if (productSchemeRepository == null) {
            throw new IllegalStateException("Product scheme repository is not configured");
        }
        return productSchemeRepository.findById(productSchemeId)
                .orElseThrow(() -> new EntityNotFoundException("Product scheme not found"));
    }

    private ProductScheme resolveProductScheme(OrderCreateRequest request) {
        if (productSchemeRepository == null) {
            throw new IllegalStateException("Product scheme repository is not configured");
        }
        if (request.productSchemeId() != null) {
            return productSchemeRepository.findById(request.productSchemeId())
                    .or(() -> resolveProductSchemeByExternalReference(request))
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Product scheme not found. Reload the fund catalogue and try again."));
        }
        return resolveProductSchemeByExternalReference(request)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Product scheme not found. Reload the fund catalogue and try again."));
    }

    private java.util.Optional<ProductScheme> resolveProductSchemeByExternalReference(OrderCreateRequest request) {
        if (org.springframework.util.StringUtils.hasText(request.externalSchemeCode())) {
            return productSchemeRepository.findFirstByExternalSchemeCodeIgnoreCase(request.externalSchemeCode().trim());
        }
        if (org.springframework.util.StringUtils.hasText(request.externalIsin())) {
            return productSchemeRepository.findFirstByExternalIsinIgnoreCase(request.externalIsin().trim());
        }
        return java.util.Optional.empty();
    }

    private Sort.Direction resolveSortDirection(String direction) {
        return "ASC".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
    }

    private String resolveOrderSortField(String sortBy) {
        Set<String> allowed = Set.of("investorId", "productSchemeId", "transactionType", "amount", "orderStatus", "createdAt");
        return allowed.contains(sortBy) ? sortBy : "createdAt";
    }

    private List<OrderStatus> resolveOrderStatuses(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank() || "All".equalsIgnoreCase(statusFilter)) {
            return List.of();
        }

        String normalized = statusFilter.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "PENDING" -> List.of(OrderStatus.DRAFT, OrderStatus.CREATED, OrderStatus.PENDING_INVESTOR_ACTION, OrderStatus.PAYMENT_PENDING);
            case "PROCESSING" -> List.of(OrderStatus.SUBMITTED, OrderStatus.PROCESSING);
            case "COMPLETED" -> List.of(OrderStatus.COMPLETED, OrderStatus.SUCCESSFUL);
            case "FAILED" -> List.of(OrderStatus.FAILED, OrderStatus.RETRY_AVAILABLE);
            case "CANCELLED" -> List.of(OrderStatus.CANCELLED);
            default -> {
                List<OrderStatus> statuses = new ArrayList<>();
                try {
                    statuses.add(OrderStatus.valueOf(normalized));
                } catch (IllegalArgumentException ignored) {
                    yield List.of();
                }
                yield statuses;
            }
        };
    }
}
