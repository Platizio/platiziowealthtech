package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.*;
import com.platizio.wealthtech.dto.BulkOrderCreateRequest;
import com.platizio.wealthtech.dto.OrderCreateRequest;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.RedemptionRecordRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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

    public OrderService(
            TransactionOrderRepository transactionOrderRepository,
            RedemptionRecordRepository redemptionRecordRepository,
            InvestorService investorService,
            AuditService auditService,
            NotificationService notificationService,
            CybrillaClient cybrillaClient,
            PlatformTransactionManager transactionManager
    ) {
        this.transactionOrderRepository = transactionOrderRepository;
        this.redemptionRecordRepository = redemptionRecordRepository;
        this.investorService = investorService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.cybrillaClient = cybrillaClient;
        this.transactionTemplate = transactionManager == null ? null : perOrderTransactionTemplate(transactionManager);
    }

    public List<TransactionOrder> listOrdersByInvestor(UUID investorId) {
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

    public TransactionOrder getOrder(UUID orderId) {
        return transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
    }

    public List<RedemptionRecord> listRedemptionsByOrder(UUID orderId) {
        return redemptionRecordRepository.findByOrderId(orderId);
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

    @Transactional
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

        TransactionOrder order = new TransactionOrder();
        order.setInvestorId(request.investorId());
        order.setDistributorId(distributorId);
        order.setProductSchemeId(request.productSchemeId());
        order.setTransactionType(request.transactionType());
        order.setAmount(request.amount());
        order.setUnits(request.units());
        order.setPaymentMode(request.paymentMode());
        order.setMandateMode(request.mandateMode());
        order.setSipFrequency(normalizeSipFrequency(request.sipFrequency()));
        order.setSipStartDate(request.sipStartDate());
        order.setSipInstalments(request.sipInstalments());
        order.setOrderStatus(OrderStatus.CREATED);

        TransactionOrder saved = transactionOrderRepository.save(order);
        String externalOrderId = cybrillaClient.createOrder(saved, investor);
        saved.setExternalOrderId(externalOrderId);
        saved.setOrderStatus(OrderStatus.PENDING_INVESTOR_ACTION);
        saved.setInvestorActionUrl(cybrillaClient.generateInvestorActionUrl(saved));
        saved = transactionOrderRepository.save(saved);

        auditService.log("ORDER", saved.getId(), "ORDER_CREATED", distributorId, "{\"externalOrderId\":\"" + externalOrderId + "\"}");
        notificationService.createForDistributor(distributorId, request.investorId(), NotificationType.PAYMENT_PENDING, "Investor action pending", "Order created and waiting for investor action.");
        return saved;
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

    @Transactional
    public RedemptionRecord createRedemption(UUID orderId, UUID actorId) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));

        RedemptionRecord record = new RedemptionRecord();
        record.setOrderId(orderId);
        record.setInvestorId(order.getInvestorId());
        record.setRedemptionStatus(RedemptionStatus.CREATED);
        record.setAmount(order.getAmount());
        record.setUnits(order.getUnits());
        record.setExternalRedemptionId(cybrillaClient.createRedemption(order));

        RedemptionRecord saved = redemptionRecordRepository.save(record);
        auditService.log("REDEMPTION", saved.getId(), "REDEMPTION_CREATED", actorId, "{}");
        notificationService.createForDistributor(order.getDistributorId(), order.getInvestorId(), NotificationType.REDEMPTION_SUBMITTED, "Redemption submitted", "Redemption flow has started.");
        return saved;
    }

    @Transactional
    public void deleteOrder(UUID orderId, UUID actorId) {
        TransactionOrder order = transactionOrderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        if (!actorId.equals(order.getDistributorId())) {
            throw new AccessDeniedException("Cannot delete order for another distributor");
        }
        if (order.getTransactionType() == TransactionType.SIP) {
            cybrillaClient.cancelOrder(order);
        }
        order.setIsDeleted(true);
        order.setDeletedAt(LocalDateTime.now());
        transactionOrderRepository.save(order);
        auditService.log("ORDER", orderId, order.getTransactionType() == TransactionType.SIP ? "ORDER_CANCELLED" : "DELETED", actorId, "{\"softDeleted\":true,\"reason\":\"User requested deletion\"}");
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
        if (!"MONTHLY".equals(frequency) && !"QUARTERLY".equals(frequency)) {
            throw new IllegalArgumentException("SIP frequency must be MONTHLY or QUARTERLY");
        }
        if (request.sipInstalments() != null && request.sipInstalments() < 1) {
            throw new IllegalArgumentException("SIP instalments must be greater than 0");
        }
    }

    private String normalizeSipFrequency(String frequency) {
        return frequency == null ? null : frequency.trim().toUpperCase();
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
