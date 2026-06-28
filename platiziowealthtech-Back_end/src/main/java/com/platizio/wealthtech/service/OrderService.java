package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.*;
import com.platizio.wealthtech.dto.BulkOrderCreateRequest;
import com.platizio.wealthtech.dto.InvestorOrderRequest;
import com.platizio.wealthtech.dto.OrderCreateRequest;
import com.platizio.wealthtech.dto.SipCancelRequest;
import com.platizio.wealthtech.dto.WithdrawalRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.integration.CybrillaUnavailableException;
import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.TransactionApprovalChallenge;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.repository.InvestorAccountRepository;
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
    private final TransactionApprovalService transactionApprovalService;
    private final InvestorAccountRepository investorAccountRepository;

    public OrderService(
            TransactionOrderRepository transactionOrderRepository,
            RedemptionRecordRepository redemptionRecordRepository,
            InvestorService investorService,
            AuditService auditService,
            NotificationService notificationService,
            CybrillaClient cybrillaClient,
            PlatformTransactionManager transactionManager,
            ProductSchemeRepository productSchemeRepository,
            TransactionApprovalService transactionApprovalService,
            InvestorAccountRepository investorAccountRepository
    ) {
        this.transactionOrderRepository = transactionOrderRepository;
        this.redemptionRecordRepository = redemptionRecordRepository;
        this.investorService = investorService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.cybrillaClient = cybrillaClient;
        this.transactionTemplate = transactionManager == null ? null : perOrderTransactionTemplate(transactionManager);
        this.productSchemeRepository = productSchemeRepository;
        this.transactionApprovalService = transactionApprovalService;
        this.investorAccountRepository = investorAccountRepository;
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
        // Order-readiness needs a verified bank. The investor-level flag can lag behind (adding a new
        // unverified bank resets it), so also accept the investor having ANY verified bank account.
        if (investor.getBankVerificationStatus() != BankVerificationStatus.VERIFIED
                && !investorService.hasVerifiedBank(request.investorId())) {
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
     * Investor-self order placement (buy funds from the investor portal). The investor
     * session already authorizes {@code investorId}; the owning distributor is resolved as
     * the order actor (orders are distributor-scoped) without a caller-ownership check —
     * mirrors the A1 *AsInvestor pattern. KYC COMPLETED + verified bank are still enforced
     * by {@link #createOrder}.
     */
    @Transactional
    public TransactionOrder createOrderAsInvestor(UUID investorId, InvestorOrderRequest req) {
        Investor investor = investorService.getInvestor(investorId);
        UUID distributorId = investor.getDistributorId();
        if (distributorId == null) {
            throw new IllegalStateException(
                    "Your account is not linked to a distributor yet, so orders cannot be placed.");
        }
        OrderCreateRequest order = new OrderCreateRequest(
                investorId,
                req.productSchemeId(),
                null,
                req.transactionType(),
                req.amount(),
                req.units(),
                req.paymentMode(),
                req.mandateMode(),
                req.sipFrequency(),
                req.sipStartDate(),
                req.sipInstalments(),
                req.externalSchemeCode(),
                req.externalIsin());
        TransactionOrder created = createOrder(order, distributorId);
        // Investor-self purchase: create the transaction-2FA challenge so the investor approves it
        // with an email OTP in their Approval Center before payment — the same hard gate the
        // distributor flow uses. No payment can proceed without an APPROVED challenge.
        InvestorAccount account = resolveInvestorAccount(investorId);
        transactionApprovalService.createChallenge(created.getId(), challengeTypeFor(created), account.getId());
        return created;
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
     * Distributor-initiated request that the investor approve a purchase / SIP order
     * with 2FA (Phase-2 plan §"Endpoint contract" {@code POST /orders/{id}/request-investor-approval}).
     * Ownership-checked, then freezes a {@link TransactionApprovalChallenge} against the
     * order's resolved {@link InvestorAccount}, flips the order to
     * {@link OrderStatus#PENDING_INVESTOR_ACTION}, and audits. NEVER sends or returns an
     * OTP — the investor requests the code themselves from the Approval Center; this only
     * creates the challenge.
     */
    @Transactional
    public ApprovalRequestResult requestInvestorApproval(UUID orderId, JwtAuthPrincipal principal) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        assertOrderOwnership(order.getDistributorId(), principal);

        InvestorAccount account = resolveInvestorAccount(order.getInvestorId());
        TransactionType challengeType = challengeTypeFor(order);

        TransactionApprovalChallenge challenge =
                transactionApprovalService.createChallenge(orderId, challengeType, account.getId());

        order.setOrderStatus(OrderStatus.PENDING_INVESTOR_ACTION);
        transactionOrderRepository.save(order);

        auditService.log("ORDER", orderId, "INVESTOR_APPROVAL_REQUESTED", principal.getDistributorId(),
                "{\"challengeId\":\"" + challenge.getId() + "\",\"type\":\"" + challengeType
                        + "\",\"investorAccountId\":\"" + account.getId() + "\"}");
        notificationService.createForDistributor(order.getDistributorId(), order.getInvestorId(),
                NotificationType.PAYMENT_PENDING, "Approval requested",
                "Investor 2FA approval requested for this order.");

        // Challenge summary only — NEVER the OTP.
        return new ApprovalRequestResult(
                challenge.getId(),
                orderId,
                challengeType.name(),
                challenge.getStatus().name(),
                challenge.getMaskedDestination());
    }

    /**
     * Distributor resend of the approval link/code for an existing challenge
     * (Phase-2 plan §"Endpoint contract" {@code POST /orders/{id}/resend-approval-link}).
     * Ownership-checked, then delegates to
     * {@link TransactionApprovalService#requestApprovalOtp} with {@code isDistributorResend=true}.
     * The returned {@link OtpRequestResponse} already hides the live code (DF-13).
     */
    @Transactional
    public OtpRequestResponse resendApprovalLink(UUID orderId, UUID challengeId, JwtAuthPrincipal principal) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        assertOrderOwnership(order.getDistributorId(), principal);
        OtpRequestResponse response = transactionApprovalService.requestApprovalOtp(
                challengeId, principal.getDistributorId(), true);
        auditService.log("ORDER", orderId, "INVESTOR_APPROVAL_LINK_RESENT", principal.getDistributorId(),
                "{\"challengeId\":\"" + challengeId + "\"}");
        return response;
    }

    private InvestorAccount resolveInvestorAccount(UUID investorId) {
        if (investorId == null) {
            throw new IllegalStateException(
                    "This order has no investor; cannot request investor approval.");
        }
        return investorAccountRepository.findByInvestorId(investorId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No self-service investor account is linked to this order's investor. "
                                + "The investor must register and confirm their profile before approving."));
    }

    /** Maps an order's transaction type to the approval challenge type (PURCHASE | SIP). */
    private static TransactionType challengeTypeFor(TransactionOrder order) {
        return order.getTransactionType() == TransactionType.SIP
                ? TransactionType.SIP
                : TransactionType.PURCHASE;
    }

    /** Challenge summary for the distributor request endpoint — NEVER carries an OTP. */
    public record ApprovalRequestResult(
            UUID challengeId,
            UUID orderId,
            String transactionType,
            String status,
            String maskedDestination) {}

    /**
     * GATE C, part 1 — draft only. Persists the {@link RedemptionRecord} in
     * {@link RedemptionStatus#PENDING_INVESTOR_ACTION} with NO provider call, so the
     * investor can then authorize it (2FA) before any money moves. Returns the draft;
     * a {@link TransactionApprovalChallenge} is created against {@code saved.getId()}
     * and, once APPROVED, {@link #submitRedemptionToProvider(UUID)} performs the real
     * Cybrilla redemption (locked decision #2).
     */
    @Transactional
    public RedemptionRecord createRedemptionDraft(UUID orderId, UUID actorId) {
        // No partial spec supplied (e.g. distributor "request to distributor" path which
        // carries no WithdrawalRequest): redeem the whole holding, preserving prior behavior.
        return createRedemptionDraft(orderId, actorId, null, null, true);
    }

    /**
     * GATE C, part 1 — draft only, honoring the investor-requested redemption shape.
     * Persists the {@link RedemptionRecord} in {@link RedemptionStatus#PENDING_INVESTOR_ACTION}
     * with NO provider call. When {@code fullRedemption} is true (or no {@code value} is given)
     * the whole holding is redeemed; otherwise the draft records exactly the requested
     * {@code value} as an AMOUNT or UNITS partial (the other dimension is left null so the
     * provider quotes it). The frozen 2FA snapshot is computed from this draft, so the
     * investor approves the exact partial they requested.
     *
     * @param mode           AMOUNT or UNITS; ignored when fullRedemption is true
     * @param value          rupee amount or unit count; must be &gt; 0 unless full-redemption
     * @param fullRedemption when true, redeem the entire order holding
     * @throws IllegalArgumentException (→ 400) when a non-full redemption omits a positive value
     */
    @Transactional
    public RedemptionRecord createRedemptionDraft(
            UUID orderId,
            UUID actorId,
            WithdrawalRequest.WithdrawalMode mode,
            BigDecimal value,
            boolean fullRedemption) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        if (!StringUtils.hasText(order.getExternalOrderId())) {
            throw new IllegalStateException(
                    "This holding has no Fintech Primitives purchase id. Redemption requires a purchase that was "
                            + "submitted through Cybrilla POA (not demo-only local rows).");
        }

        RedemptionRecord record = new RedemptionRecord();
        record.setOrderId(orderId);
        record.setInvestorId(order.getInvestorId());
        record.setRedemptionStatus(RedemptionStatus.PENDING_INVESTOR_ACTION);

        if (fullRedemption) {
            // Whole-holding redemption: copy the order's amount and units.
            record.setAmount(order.getAmount());
            record.setUnits(order.getUnits());
        } else {
            if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        "A withdrawal value greater than 0 is required unless redeeming the full holding.");
            }
            if (mode == WithdrawalRequest.WithdrawalMode.UNITS) {
                record.setUnits(value);
            } else {
                // Default to AMOUNT when mode is AMOUNT or unspecified.
                record.setAmount(value);
            }
        }

        RedemptionRecord saved = redemptionRecordRepository.save(record);
        auditService.log("REDEMPTION", saved.getId(), "REDEMPTION_DRAFTED", actorId, "{}");
        notificationService.createForDistributor(order.getDistributorId(), order.getInvestorId(), NotificationType.REDEMPTION_SUBMITTED, "Redemption pending approval", "Redemption draft created and waiting for investor 2FA approval.");
        return saved;
    }

    /**
     * GATE C, part 2 — gated provider submit. {@code @Transactional} at the method
     * boundary so the gate's CONSUMED flip joins THIS transaction and rolls back together
     * with the redemption row if {@code cybrillaClient.createRedemption} throws — matching
     * the retry-safe guarantee documented for Gate A/B and
     * {@link TransactionApprovalService#assertApprovedAndConsume}: a provider failure leaves
     * the challenge APPROVED for a genuine retry, while a successful call commits CONSUMED
     * so any replay is blocked (exactly-once). {@code assertApprovedAndConsume} is the FIRST
     * provider-touching statement: it throws (→ 400) unless a live APPROVED 2FA challenge
     * exists for this redemption whose frozen snapshot still matches, then burns it. Only
     * then does the real Cybrilla redemption fire and the status flips.
     */
    @Transactional
    public RedemptionRecord submitRedemptionToProvider(UUID redemptionId) {
        RedemptionRecord record = redemptionRecordRepository.findById(redemptionId)
                .orElseThrow(() -> new EntityNotFoundException("Redemption not found"));
        TransactionOrder order = transactionOrderRepository.findById(record.getOrderId())
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        if (!StringUtils.hasText(order.getExternalOrderId())) {
            throw new IllegalStateException(
                    "This holding has no Fintech Primitives purchase id. Redemption requires a purchase that was "
                            + "submitted through Cybrilla POA (not demo-only local rows).");
        }

        // GATE C: atomic 2FA gate before the provider write (retry-safe + exactly-once).
        transactionApprovalService.assertApprovedAndConsume(
                record.getId(),
                ConsentRecordService.sha256(transactionApprovalService.renderRedemptionSnapshot(record)));

        Investor investor = investorService.ensureMfInvestmentAccount(order.getInvestorId());
        ProductScheme productScheme = getProductSchemeById(order.getProductSchemeId());

        record.setExternalRedemptionId(cybrillaClient.createRedemption(order, investor, productScheme));
        record.setRedemptionStatus(RedemptionStatus.SUBMITTED);

        RedemptionRecord saved = redemptionRecordRepository.save(record);
        auditService.log("REDEMPTION", saved.getId(), "REDEMPTION_SUBMITTED", order.getInvestorId(), "{}");
        notificationService.createForDistributor(order.getDistributorId(), order.getInvestorId(), NotificationType.REDEMPTION_SUBMITTED, "Redemption submitted", "Redemption submitted to Fintech Primitives after investor approval.");
        return saved;
    }

    /**
     * Distributor-initiated redemption draft (the non-2FA "request to distributor"
     * alternative, locked decision #2). Creates the draft only; the actual provider
     * redemption still requires a 2FA-approved {@link #submitRedemptionToProvider(UUID)}.
     * No longer performs a provider write directly (gate principle).
     */
    public RedemptionRecord createRedemption(UUID orderId, UUID actorId) {
        return createRedemptionDraft(orderId, actorId);
    }

    /**
     * Ownership-checked variant: rejects callers that are not the order's distributor (or ADMIN)
     * BEFORE touching anything. Closes BUG-003. Creates a draft only — no provider write
     * fires without the 2FA-gated {@link #submitRedemptionToProvider(UUID)}.
     */
    public RedemptionRecord createRedemption(UUID orderId, JwtAuthPrincipal principal) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        assertOrderOwnership(order.getDistributorId(), principal);
        return createRedemptionDraft(orderId, principal.getDistributorId());
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
