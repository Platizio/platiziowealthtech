package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.*;
import com.platizio.wealthtech.dto.BulkOrderCreateRequest;
import com.platizio.wealthtech.dto.OrderCreateRequest;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.RedemptionRecordRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class OrderService {

    private final TransactionOrderRepository transactionOrderRepository;
    private final RedemptionRecordRepository redemptionRecordRepository;
    private final InvestorService investorService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private final CybrillaClient cybrillaClient;

    public OrderService(
            TransactionOrderRepository transactionOrderRepository,
            RedemptionRecordRepository redemptionRecordRepository,
            InvestorService investorService,
            AuditService auditService,
            NotificationService notificationService,
            CybrillaClient cybrillaClient
    ) {
        this.transactionOrderRepository = transactionOrderRepository;
        this.redemptionRecordRepository = redemptionRecordRepository;
        this.investorService = investorService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.cybrillaClient = cybrillaClient;
    }

    public List<TransactionOrder> listOrdersByInvestor(UUID investorId) {
        return transactionOrderRepository.findByInvestorId(investorId);
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

    @Transactional
    public List<TransactionOrder> createOrders(BulkOrderCreateRequest request) {
        return request.investorIds().stream()
                .distinct()
                .map(investorId -> createOrder(new OrderCreateRequest(
                        investorId,
                        request.distributorId(),
                        request.productSchemeId(),
                        request.transactionType(),
                        request.amount(),
                        request.units(),
                        request.paymentMode(),
                        request.mandateMode()
                )))
                .toList();
    }

    @Transactional
    public TransactionOrder createOrder(OrderCreateRequest request) {
        Investor investor = investorService.getInvestor(request.investorId());

        if (investor.getKycStatus() != KycStatus.COMPLETED) {
            throw new IllegalStateException("KYC must be completed before order creation");
        }
        if (investor.getBankVerificationStatus() != BankVerificationStatus.VERIFIED) {
            throw new IllegalStateException("Verified bank account is required before order creation");
        }

        TransactionOrder order = new TransactionOrder();
        order.setInvestorId(request.investorId());
        order.setDistributorId(request.distributorId());
        order.setProductSchemeId(request.productSchemeId());
        order.setTransactionType(request.transactionType());
        order.setAmount(request.amount());
        order.setUnits(request.units());
        order.setPaymentMode(request.paymentMode());
        order.setMandateMode(request.mandateMode());
        order.setOrderStatus(OrderStatus.CREATED);

        TransactionOrder saved = transactionOrderRepository.save(order);
        String externalOrderId = cybrillaClient.createOrder(saved, investor);
        saved.setExternalOrderId(externalOrderId);
        saved.setOrderStatus(OrderStatus.PENDING_INVESTOR_ACTION);
        saved.setInvestorActionUrl(cybrillaClient.generateInvestorActionUrl(saved));
        saved = transactionOrderRepository.save(saved);

        auditService.log("ORDER", saved.getId(), "ORDER_CREATED", request.distributorId(), "{\"externalOrderId\":\"" + externalOrderId + "\"}");
        notificationService.createForDistributor(request.distributorId(), request.investorId(), NotificationType.PAYMENT_PENDING, "Investor action pending", "Order created and waiting for investor action.");
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
        transactionOrderRepository.delete(order);
        auditService.log("ORDER", orderId, "DELETED", actorId, "{\"reason\":\"User requested deletion\"}");
    }
}
