package com.platizio.wealthtech.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PortfolioDto(
        PortfolioSummaryDto summary,
        List<PortfolioHoldingDto> holdings,
        List<PortfolioSipMandateDto> sipMandates,
        List<PortfolioInvestorAumDto> investors,
        List<PortfolioSchemeAumDto> schemes
) {
    public record PortfolioSummaryDto(
            BigDecimal totalAum,
            BigDecimal mfAum,
            BigDecimal sifAum,
            long investorCount,
            long holdingCount,
            long failedOrUnknownCount
    ) {}

    public record PortfolioSipMandateDto(
            UUID orderId,
            UUID investorId,
            String investorName,
            UUID productSchemeId,
            String schemeName,
            String amcName,
            String category,
            BigDecimal amount,
            String sipFrequency,
            Integer sipInstalments,
            Integer mandateId,
            String mandateStatus,
            String mandateMode,
            String planId,
            String setupStatus,
            String orderStatus
    ) {}

    public record PortfolioHoldingDto(
            UUID orderId,
            UUID investorId,
            String investorName,
            UUID productSchemeId,
            String schemeName,
            String amcName,
            String category,
            BigDecimal amount,
            String orderStatus,
            String displayStatus,
            boolean schemeKnown,
            boolean countsTowardAum,
            String transactionType,
            String paymentMode,
            String mandateStatus,
            Integer externalMandateId,
            String sipFrequency,
            Integer sipInstalments,
            String failureReason
    ) {}

    public record PortfolioInvestorAumDto(
            UUID investorId,
            String investorName,
            BigDecimal totalAum,
            BigDecimal mfAum,
            BigDecimal sifAum
    ) {}

    public record PortfolioSchemeAumDto(
            UUID productSchemeId,
            String schemeName,
            String amcName,
            String category,
            BigDecimal investedValue,
            long investorCount,
            BigDecimal dailyReturn,
            BigDecimal ytdReturn,
            BigDecimal oneYearReturn,
            BigDecimal fiveYearReturn
    ) {}
}
