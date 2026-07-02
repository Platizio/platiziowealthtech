package com.platizio.wealthtech.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One SIP plan as shown on the INVESTOR dashboard (a SIP is a transaction_orders
 * row with transaction_type=SIP; there is no separate plan entity).
 * {@code sipNumber} is the provider order id; {@code nextDueDate} is derived from
 * the start date + frequency (null once all instalments have run or the SIP is
 * cancelled).
 */
public record InvestorSipResponse(
        UUID orderId,
        String sipName,
        String sipNumber,
        String schemeName,
        String amcName,
        BigDecimal amount,
        String sipFrequency,
        LocalDate sipStartDate,
        Integer sipInstalments,
        String mandateMode,
        String mandateStatus,
        String status,
        String folioNumber,
        LocalDate nextDueDate
) {}
