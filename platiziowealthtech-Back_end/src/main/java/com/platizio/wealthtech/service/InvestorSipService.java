package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.InvestorSipResponse;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Investor-scoped SIP listing (the distributor equivalent lives in
 * DashboardService.getSipDashboard). The caller (investor portal controller)
 * resolves {@code investorId} from the authenticated session, so the session
 * itself proves ownership — same {@code *AsInvestor} convention as the other
 * portal services.
 */
@Service
public class InvestorSipService {

    private final TransactionOrderRepository orderRepository;

    public InvestorSipService(TransactionOrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public List<InvestorSipResponse> listSipsAsInvestor(UUID investorId) {
        return orderRepository.findByInvestorId(investorId).stream()
                .filter(o -> o.getTransactionType() == TransactionType.SIP)
                .filter(o -> !Boolean.TRUE.equals(o.getIsDeleted()))
                .sorted(Comparator.comparing(TransactionOrder::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toResponse)
                .toList();
    }

    private InvestorSipResponse toResponse(TransactionOrder order) {
        boolean cancelled = order.getCancelledAt() != null;
        return new InvestorSipResponse(
                order.getId(),
                sipName(order.getSipFrequency()),
                order.getExternalOrderId(),
                order.getProductSchemeName(),
                order.getProductSchemeAmcName(),
                order.getAmount(),
                order.getSipFrequency(),
                order.getSipStartDate(),
                order.getSipInstalments(),
                order.getMandateMode(),
                order.getMandateStatus(),
                cancelled ? "CANCELLED" : (order.getOrderStatus() == null ? null : order.getOrderStatus().name()),
                order.getFolioNumber(),
                cancelled ? null
                        : nextDueDate(order.getSipStartDate(), order.getSipFrequency(), order.getSipInstalments()));
    }

    /** Same label convention as the holdings/dashboard sipName badge. */
    private static String sipName(String frequency) {
        return frequency == null || frequency.isBlank() ? "SIP" : "SIP (" + frequency + ")";
    }

    /**
     * Next debit date on or after today, stepping from the start date by the plan
     * frequency. Null when the frequency is unknown, the plan has not got a start
     * date, or every instalment has already run.
     */
    static LocalDate nextDueDate(LocalDate startDate, String frequency, Integer instalments) {
        if (startDate == null || frequency == null) {
            return null;
        }
        LocalDate today = LocalDate.now();
        LocalDate due = startDate;
        int step = 0;
        while (due.isBefore(today)) {
            due = advance(startDate, frequency, ++step);
            if (due == null) {
                return null;
            }
            if (instalments != null && step >= instalments) {
                return null; // all instalments have run
            }
        }
        return due;
    }

    /** The {@code step}-th occurrence after the start date, or null for an unknown frequency. */
    private static LocalDate advance(LocalDate startDate, String frequency, int step) {
        return switch (frequency.trim().toUpperCase(Locale.ROOT)) {
            case "DAILY" -> startDate.plusDays(step);
            case "WEEKLY" -> startDate.plusWeeks(step);
            case "MONTHLY" -> startDate.plusMonths(step);
            case "QUARTERLY" -> startDate.plusMonths(3L * step);
            default -> null;
        };
    }
}
