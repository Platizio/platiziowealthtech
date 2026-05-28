package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class InvestorActionService {

    private final TransactionOrderRepository orderRepository;
    private final InvestorRepository investorRepository;
    private final ProductSchemeRepository schemeRepository;
    private final AuditService auditService;

    public InvestorActionService(
            TransactionOrderRepository orderRepository,
            InvestorRepository investorRepository,
            ProductSchemeRepository schemeRepository,
            AuditService auditService
    ) {
        this.orderRepository = orderRepository;
        this.investorRepository = investorRepository;
        this.schemeRepository = schemeRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public InvestorActionPage getPage(String token) {
        TransactionOrder order = findOrder(token);
        return pageFor(order, messageFor(order));
    }

    @Transactional
    public InvestorActionPage confirmPurchase(String token) {
        TransactionOrder order = findOrder(token);
        String message;
        if (order.getOrderStatus() == OrderStatus.PENDING_INVESTOR_ACTION) {
            order.setOrderStatus(OrderStatus.PAYMENT_PENDING);
            order = orderRepository.save(order);
            auditService.log(
                    "ORDER",
                    order.getId(),
                    "INVESTOR_ACTION_CONFIRMED",
                    order.getDistributorId(),
                    "{\"status\":\"PAYMENT_PENDING\"}"
            );
            message = "Purchase confirmed. Payment is now pending provider confirmation.";
        } else {
            message = messageFor(order);
        }
        return pageFor(order, message);
    }

    private TransactionOrder findOrder(String token) {
        if (!StringUtils.hasText(token)) {
            throw new IllegalArgumentException("Investor action token is required");
        }
        return orderRepository.findByInvestorActionToken(token.trim())
                .orElseThrow(() -> new EntityNotFoundException("Investor action link not found"));
    }

    private InvestorActionPage pageFor(TransactionOrder order, String message) {
        Investor investor = investorRepository.findById(order.getInvestorId()).orElse(null);
        ProductScheme scheme = schemeRepository.findById(order.getProductSchemeId()).orElse(null);
        return new InvestorActionPage(
                order.getInvestorActionToken(),
                order.getId(),
                order.getExternalOrderId(),
                investor == null ? "Investor" : investor.getFullName(),
                investor == null ? null : investor.getEmail(),
                scheme == null ? "Selected scheme" : scheme.getSchemeName(),
                scheme == null ? null : scheme.getAmcName(),
                order.getAmount(),
                order.getUnits(),
                order.getTransactionType() == null ? null : order.getTransactionType().name(),
                order.getOrderStatus(),
                order.getPaymentMode(),
                order.getMandateMode(),
                order.getSipFrequency(),
                order.getSipStartDate(),
                order.getSipInstalments(),
                order.getOrderStatus() == OrderStatus.PENDING_INVESTOR_ACTION,
                message
        );
    }

    private String messageFor(TransactionOrder order) {
        return switch (order.getOrderStatus()) {
            case PENDING_INVESTOR_ACTION -> "Review the details and confirm to start payment processing.";
            case PAYMENT_PENDING -> "Payment is pending provider confirmation.";
            case SUBMITTED, PROCESSING -> "Your purchase is being processed.";
            case SUCCESSFUL, COMPLETED -> "This purchase is complete.";
            case FAILED -> StringUtils.hasText(order.getFailureReason())
                    ? "This purchase failed: " + order.getFailureReason()
                    : "This purchase failed.";
            case RETRY_AVAILABLE -> "This purchase needs another attempt. Please contact your distributor.";
            case CREATED, DRAFT -> "This purchase is not ready for investor action yet.";
            case ACTIVE -> "This recurring purchase is active.";
            case PAUSED -> "This recurring purchase is paused.";
        };
    }

    public record InvestorActionPage(
            String token,
            UUID orderId,
            String externalOrderId,
            String investorName,
            String investorEmail,
            String schemeName,
            String amcName,
            BigDecimal amount,
            BigDecimal units,
            String transactionType,
            OrderStatus orderStatus,
            String paymentMode,
            String mandateMode,
            String sipFrequency,
            LocalDate sipStartDate,
            Integer sipInstalments,
            boolean confirmationAllowed,
            String message
    ) {
    }
}
