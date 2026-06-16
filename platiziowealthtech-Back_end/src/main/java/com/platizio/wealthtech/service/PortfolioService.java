package com.platizio.wealthtech.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.PortfolioDto;
import com.platizio.wealthtech.dto.PortfolioDto.PortfolioHoldingDto;
import com.platizio.wealthtech.dto.PortfolioDto.PortfolioInvestorAumDto;
import com.platizio.wealthtech.dto.PortfolioDto.PortfolioSchemeAumDto;
import com.platizio.wealthtech.dto.PortfolioDto.PortfolioSummaryDto;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import com.platizio.wealthtech.service.ProductSchemeOrderSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioService {

    private static final Set<OrderStatus> AUM_STATUSES = Set.of(
            OrderStatus.SUCCESSFUL,
            OrderStatus.COMPLETED,
            OrderStatus.ACTIVE
    );

    private final TransactionOrderRepository orderRepository;
    private final InvestorRepository investorRepository;
    private final ProductSchemeRepository schemeRepository;
    private final ObjectMapper objectMapper;

    public PortfolioService(
            TransactionOrderRepository orderRepository,
            InvestorRepository investorRepository,
            ProductSchemeRepository schemeRepository,
            ObjectMapper objectMapper
    ) {
        this.orderRepository = orderRepository;
        this.investorRepository = investorRepository;
        this.schemeRepository = schemeRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PortfolioDto getPortfolio(UUID distributorId, String categoryFilter) {
        List<TransactionOrder> orders = orderRepository.findByDistributorId(distributorId);

        Set<UUID> investorIds = orders.stream().map(TransactionOrder::getInvestorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> schemeIds = orders.stream().map(TransactionOrder::getProductSchemeId).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<UUID, Investor> investorMap = investorIds.isEmpty()
                ? Map.of()
                : investorRepository.findAllById(investorIds).stream().collect(Collectors.toMap(Investor::getId, i -> i));
        Map<UUID, ProductScheme> schemeMap = schemeIds.isEmpty()
                ? Map.of()
                : schemeRepository.findAllById(schemeIds).stream().collect(Collectors.toMap(ProductScheme::getId, s -> s));

        List<PortfolioHoldingDto> holdings = new ArrayList<>();
        List<PortfolioDto.PortfolioSipMandateDto> sipMandates = new ArrayList<>();
        BigDecimal totalAum = BigDecimal.ZERO;
        BigDecimal mfAum = BigDecimal.ZERO;
        BigDecimal sifAum = BigDecimal.ZERO;
        long failedOrUnknown = 0;

        Map<UUID, PortfolioInvestorAumDto> investorAumScratch = new HashMap<>();
        Map<UUID, SchemeAccumulator> schemeScratch = new HashMap<>();

        for (TransactionOrder order : orders) {
            ProductScheme scheme = order.getProductSchemeId() == null ? null : schemeMap.get(order.getProductSchemeId());
            boolean schemeKnown = scheme != null && order.getProductSchemeId() != null;
            String category = resolveCategory(order, scheme);
            String displayStatus = resolveDisplayStatus(order, schemeKnown);
            boolean countsTowardAum = schemeKnown && AUM_STATUSES.contains(order.getOrderStatus());

            if (!schemeKnown || order.getOrderStatus() == OrderStatus.FAILED) {
                failedOrUnknown++;
            }

            Investor investor = investorMap.get(order.getInvestorId());
            BigDecimal amount = order.getAmount() == null ? BigDecimal.ZERO : order.getAmount();
            boolean isSip = order.getTransactionType() == TransactionType.SIP;
            boolean isMandateSip = isSip && "MANDATE".equalsIgnoreCase(order.getPaymentMode());

            if (isMandateSip && order.getOrderStatus() != OrderStatus.CANCELLED) {
                sipMandates.add(new PortfolioDto.PortfolioSipMandateDto(
                        order.getId(),
                        order.getInvestorId(),
                        investor == null ? "Unknown" : investor.getFullName(),
                        order.getProductSchemeId(),
                        ProductSchemeOrderSupport.displayName(order, scheme),
                        ProductSchemeOrderSupport.amcName(order, scheme),
                        category,
                        amount,
                        order.getSipFrequency(),
                        order.getSipInstalments(),
                        order.getExternalMandateId(),
                        order.getMandateStatus(),
                        order.getMandateMode(),
                        order.getExternalOrderId(),
                        resolveSipSetupStatus(order),
                        order.getOrderStatus() == null ? null : order.getOrderStatus().name()
                ));
            }

            if (isSip && order.getOrderStatus() != OrderStatus.ACTIVE) {
                continue;
            }

            holdings.add(new PortfolioHoldingDto(
                    order.getId(),
                    order.getInvestorId(),
                    investor == null ? "Unknown" : investor.getFullName(),
                    order.getProductSchemeId(),
                    ProductSchemeOrderSupport.displayName(order, scheme),
                    ProductSchemeOrderSupport.amcName(order, scheme),
                    category,
                    amount,
                    order.getOrderStatus() == null ? null : order.getOrderStatus().name(),
                    displayStatus,
                    schemeKnown,
                    countsTowardAum,
                    order.getTransactionType() == null ? null : order.getTransactionType().name(),
                    order.getPaymentMode(),
                    order.getMandateStatus(),
                    order.getExternalMandateId(),
                    order.getSipFrequency(),
                    order.getSipInstalments(),
                    order.getFailureReason()
            ));

            if (!matchesCategoryFilter(categoryFilter, category)) {
                continue;
            }

            if (countsTowardAum) {
                totalAum = totalAum.add(amount);
                if ("MF".equals(category)) {
                    mfAum = mfAum.add(amount);
                } else {
                    sifAum = sifAum.add(amount);
                }

                PortfolioInvestorAumDto existingInvestor = investorAumScratch.get(order.getInvestorId());
                BigDecimal invTotal = amount.add(existingInvestor == null ? BigDecimal.ZERO : existingInvestor.totalAum());
                BigDecimal invMf = ("MF".equals(category) ? amount : BigDecimal.ZERO)
                        .add(existingInvestor == null ? BigDecimal.ZERO : existingInvestor.mfAum());
                BigDecimal invSif = ("SIF".equals(category) ? amount : BigDecimal.ZERO)
                        .add(existingInvestor == null ? BigDecimal.ZERO : existingInvestor.sifAum());
                investorAumScratch.put(order.getInvestorId(), new PortfolioInvestorAumDto(
                        order.getInvestorId(),
                        investor == null ? "Unknown" : investor.getFullName(),
                        invTotal,
                        invMf,
                        invSif
                ));

                if (schemeKnown) {
                    SchemeAccumulator acc = schemeScratch.computeIfAbsent(
                            order.getProductSchemeId(),
                            id -> new SchemeAccumulator(scheme, category)
                    );
                    acc.total = acc.total.add(amount);
                    acc.investors.add(order.getInvestorId());
                }
            }
        }

        holdings.sort(Comparator.comparing(PortfolioHoldingDto::orderId, Comparator.nullsLast(Comparator.reverseOrder())));
        sipMandates.sort(Comparator.comparing(PortfolioDto.PortfolioSipMandateDto::orderId, Comparator.nullsLast(Comparator.reverseOrder())));

        List<PortfolioInvestorAumDto> investors = investorAumScratch.values().stream()
                .filter(i -> i.totalAum().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(PortfolioInvestorAumDto::totalAum).reversed())
                .limit(20)
                .toList();

        List<PortfolioSchemeAumDto> schemes = schemeScratch.values().stream()
                .map(acc -> toSchemeAum(acc))
                .sorted(Comparator.comparing(PortfolioSchemeAumDto::investedValue).reversed())
                .limit(10)
                .toList();

        PortfolioSummaryDto summary = new PortfolioSummaryDto(
                totalAum,
                mfAum,
                sifAum,
                investors.size(),
                holdings.stream().filter(PortfolioHoldingDto::countsTowardAum).count(),
                failedOrUnknown
        );

        return new PortfolioDto(summary, holdings, sipMandates, investors, schemes);
    }

    private static String resolveSipSetupStatus(TransactionOrder order) {
        OrderStatus status = order.getOrderStatus();
        if (status == null) {
            return "Setup pending";
        }
        return switch (status) {
            case PENDING_INVESTOR_ACTION -> "Awaiting investor confirmation";
            case PAYMENT_PENDING -> mandateApproved(order) ? "Plan submission pending" : "Mandate authorization pending";
            case ACTIVE -> "SIP active";
            case SUCCESSFUL, COMPLETED -> "SIP completed";
            case FAILED -> "Setup failed";
            case CANCELLED -> "Cancelled";
            case SUBMITTED, PROCESSING -> "Processing";
            default -> status.name().replace('_', ' ');
        };
    }

    private static boolean mandateApproved(TransactionOrder order) {
        return order.getMandateStatus() != null
                && "APPROVED".equalsIgnoreCase(order.getMandateStatus());
    }

    private PortfolioSchemeAumDto toSchemeAum(SchemeAccumulator acc) {
        ReturnsSnapshot returns = parseReturns(acc.scheme.getMetadataJson());
        return new PortfolioSchemeAumDto(
                acc.scheme.getId(),
                ProductSchemeOrderSupport.displayName(acc.scheme),
                acc.scheme.getAmcName(),
                acc.category,
                acc.total,
                acc.investors.size(),
                returns.daily,
                returns.ytd,
                returns.oneYear,
                returns.fiveYear
        );
    }

    private ReturnsSnapshot parseReturns(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return ReturnsSnapshot.EMPTY;
        }
        try {
            JsonNode root = objectMapper.readTree(metadataJson);
            JsonNode returns = root.path("returns");
            return new ReturnsSnapshot(
                    decimalOrNull(returns, "daily"),
                    decimalOrNull(returns, "ytd"),
                    decimalOrNull(returns, "1y"),
                    decimalOrNull(returns, "5y")
            );
        } catch (Exception ignored) {
            return ReturnsSnapshot.EMPTY;
        }
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).decimalValue();
    }

    private static String resolveDisplayStatus(TransactionOrder order, boolean schemeKnown) {
        if (!schemeKnown) {
            return "Payment Failed";
        }
        OrderStatus status = order.getOrderStatus();
        if (status == null) {
            return "Payment Failed";
        }
        return switch (status) {
            case FAILED -> "Payment Failed";
            case CANCELLED -> "Cancelled";
            case PAYMENT_PENDING -> "Payment Pending";
            case PENDING_INVESTOR_ACTION -> "Pending Investor Action";
            case SUCCESSFUL -> "Successful";
            case COMPLETED -> "Completed";
            case ACTIVE -> "Active";
            case SUBMITTED -> "Submitted";
            case PROCESSING -> "Processing";
            default -> status.name().replace('_', ' ');
        };
    }

    private static String resolveCategory(TransactionOrder order, ProductScheme scheme) {
        ProductCategory category = order.getProductCategory();
        if (category != null) {
            return category == ProductCategory.MF ? "MF" : "SIF";
        }
        if (scheme != null && scheme.getCategory() != null) {
            String raw = scheme.getCategory().name().toUpperCase();
            return raw.contains("MF") || raw.contains("MUTUAL") ? "MF" : "SIF";
        }
        return "MF";
    }

    private static boolean matchesCategoryFilter(String filter, String category) {
        if (filter == null || filter.isBlank() || "ALL".equalsIgnoreCase(filter)) {
            return true;
        }
        return filter.equalsIgnoreCase(category);
    }

    private static final class SchemeAccumulator {
        private final ProductScheme scheme;
        private final String category;
        private BigDecimal total = BigDecimal.ZERO;
        private final Set<UUID> investors = new HashSet<>();

        private SchemeAccumulator(ProductScheme scheme, String category) {
            this.scheme = scheme;
            this.category = category;
        }
    }

    private record ReturnsSnapshot(BigDecimal daily, BigDecimal ytd, BigDecimal oneYear, BigDecimal fiveYear) {
        private static final ReturnsSnapshot EMPTY = new ReturnsSnapshot(null, null, null, null);
    }
}
