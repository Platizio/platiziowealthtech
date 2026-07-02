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
import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.dto.HoldingResponse;
import com.platizio.wealthtech.domain.RedemptionRecord;
import com.platizio.wealthtech.domain.RedemptionStatus;
import org.springframework.security.access.AccessDeniedException;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.RedemptionRecordRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import com.platizio.wealthtech.service.ProductSchemeOrderSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
import org.springframework.util.StringUtils;

@Service
public class PortfolioService {

    private static final Set<OrderStatus> AUM_STATUSES = Set.of(
            OrderStatus.SUCCESSFUL,
            OrderStatus.COMPLETED,
            OrderStatus.ACTIVE
    );

    /**
     * Redemption statuses that have permanently removed units from a holding
     * (units have settled out / left the folio).
     */
    private static final Set<RedemptionStatus> SETTLED_OUT_REDEMPTION_STATUSES = Set.of(
            RedemptionStatus.SUCCESSFUL,
            RedemptionStatus.BANK_CREDIT_COMPLETED
    );
    /**
     * Redemption statuses where a redemption is in flight: the units are committed
     * to a redemption but have not yet settled out, so they are blocked (cannot be
     * redeemed again) but still part of the holding. FAILED releases the units.
     */
    private static final Set<RedemptionStatus> BLOCKING_REDEMPTION_STATUSES = Set.of(
            RedemptionStatus.CREATED,
            RedemptionStatus.PENDING_INVESTOR_ACTION,
            RedemptionStatus.SUBMITTED,
            RedemptionStatus.PROCESSING,
            RedemptionStatus.BANK_CREDIT_PENDING
    );

    private final TransactionOrderRepository orderRepository;
    private final InvestorRepository investorRepository;
    private final ProductSchemeRepository schemeRepository;
    private final InvestorBankAccountRepository bankAccountRepository;
    private final RedemptionRecordRepository redemptionRecordRepository;
    private final ObjectMapper objectMapper;

    public PortfolioService(
            TransactionOrderRepository orderRepository,
            InvestorRepository investorRepository,
            ProductSchemeRepository schemeRepository,
            InvestorBankAccountRepository bankAccountRepository,
            RedemptionRecordRepository redemptionRecordRepository,
            ObjectMapper objectMapper
    ) {
        this.orderRepository = orderRepository;
        this.investorRepository = investorRepository;
        this.schemeRepository = schemeRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.redemptionRecordRepository = redemptionRecordRepository;
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

    /**
     * Investor-scoped holdings for the withdrawal disclosures (Phase-2 plan
     * §"Endpoint contract" {@code GET /investor/holdings}; FR-HLD/FR-RED). Scoped by
     * {@code investorId} (the authenticated investor's linked profile), NOT by
     * distributor — so it never reuses or weakens the distributor-scoped
     * {@link #getPortfolio(UUID, String)} path.
     *
     * <p>Valuation reuses the same scheme-metadata NAV the distributor UI reads
     * (DF-07 canonical {@code nav}). When NAV or units are unavailable the row is
     * surfaced with {@code dataQuality=UNAVAILABLE} and {@code currentValue=null} —
     * never a fall back to the order amount or zero (locked decision #4). A NAV that
     * carries no fresh as-of timestamp is reported {@code STALE}.
     */
    @Transactional(readOnly = true)
    /**
     * Distributor-facing: an individual investor's holdings, but only if that investor
     * belongs to the requesting distributor. ADMIN-scoped callers pass their own checks.
     */
    public List<HoldingResponse> getInvestorHoldingsForDistributor(UUID distributorId, UUID investorId) {
        Investor investor = investorRepository.findById(investorId).orElse(null);
        if (investor == null || !distributorId.equals(investor.getDistributorId())) {
            throw new AccessDeniedException("Investor does not belong to this distributor");
        }
        return getInvestorHoldings(investorId);
    }

    public List<HoldingResponse> getInvestorHoldings(UUID investorId) {
        if (investorId == null) {
            return List.of();
        }
        List<TransactionOrder> orders = orderRepository.findByInvestorId(investorId);

        Set<UUID> schemeIds = orders.stream()
                .map(TransactionOrder::getProductSchemeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, ProductScheme> schemeMap = schemeIds.isEmpty()
                ? Map.of()
                : schemeRepository.findAllById(schemeIds).stream()
                        .collect(Collectors.toMap(ProductScheme::getId, s -> s));

        String maskedPayoutBank = resolveMaskedPayoutBank(investorId);

        // Redemptions are tracked per source holding order. Group them so each holding
        // reports its REAL blocked units (in-flight redemptions) and NET redeemable
        // units (gross units minus blocked minus already-settled-out) — never hardcoded.
        Map<UUID, List<RedemptionRecord>> redemptionsByOrder = redemptionRecordRepository
                .findByInvestorId(investorId).stream()
                .filter(r -> r.getOrderId() != null)
                .collect(Collectors.groupingBy(RedemptionRecord::getOrderId));

        List<HoldingResponse> holdings = new ArrayList<>();
        for (TransactionOrder order : orders) {
            if (!isHoldingOrder(order)) {
                continue;
            }
            ProductScheme scheme = order.getProductSchemeId() == null
                    ? null : schemeMap.get(order.getProductSchemeId());
            boolean schemeKnown = scheme != null;

            NavSnapshot nav = resolveNav(scheme);
            BigDecimal grossUnits = positiveOrNull(order.getUnits());

            List<RedemptionRecord> orderRedemptions =
                    redemptionsByOrder.getOrDefault(order.getId(), List.of());
            BigDecimal blockedUnits = sumRedemptionUnits(orderRedemptions, BLOCKING_REDEMPTION_STATUSES);
            BigDecimal settledOutUnits = sumRedemptionUnits(orderRedemptions, SETTLED_OUT_REDEMPTION_STATUSES);

            // NET redeemable = gross holding units − units still locked in flight − units
            // that have already settled out of the folio. Floored at zero, never negative.
            BigDecimal availableUnits = grossUnits == null
                    ? null
                    : grossUnits.subtract(blockedUnits).subtract(settledOutUnits).max(BigDecimal.ZERO);

            BigDecimal currentValue;
            HoldingResponse.DataQuality quality;
            if (availableUnits == null || nav.value == null) {
                // FR-HLD-005 / locked decision #4: never fall back to order amount/zero.
                currentValue = null;
                quality = HoldingResponse.DataQuality.UNAVAILABLE;
            } else if (nav.asOf == null) {
                // Value the NET redeemable units the investor can actually act on.
                currentValue = nav.value.multiply(availableUnits);
                quality = HoldingResponse.DataQuality.STALE;
            } else {
                currentValue = nav.value.multiply(availableUnits);
                quality = HoldingResponse.DataQuality.OK;
            }

            // Only redeemable when there are NET units left to redeem.
            boolean redeemable = schemeKnown
                    && StringUtils.hasText(order.getExternalOrderId())
                    && order.getOrderStatus() != OrderStatus.CANCELLED
                    && availableUnits != null
                    && availableUnits.signum() > 0;

            holdings.add(new HoldingResponse(
                    order.getId(),
                    order.getExternalOrderId(),
                    order.getProductSchemeId(),
                    schemeKnown ? ProductSchemeOrderSupport.displayName(scheme) : "Unknown fund",
                    schemeKnown ? scheme.getAmcName() : "—",
                    resolveCategory(order, scheme),
                    availableUnits,
                    blockedUnits,
                    nav.value,
                    nav.asOf,
                    currentValue,
                    maskedPayoutBank,
                    quality,
                    redeemable,
                    order.getTransactionType() == TransactionType.SIP ? sipLabel(order.getSipFrequency()) : null,
                    order.getTransactionType() == TransactionType.SIP ? order.getExternalOrderId() : null,
                    order.getTransactionType() == TransactionType.SIP ? order.getSipFrequency() : null,
                    order.getFolioNumber()));
        }

        holdings.sort(Comparator.comparing(HoldingResponse::orderId,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return holdings;
    }

    /** Same SIP label convention as the holdings dashboard badge. */
    private static String sipLabel(String frequency) {
        return frequency == null || frequency.isBlank() ? "SIP" : "SIP (" + frequency + ")";
    }

    /** Only settled, non-SIP-pending holdings are withdrawable disclosures. */
    private static boolean isHoldingOrder(TransactionOrder order) {
        if (order.getProductSchemeId() == null) {
            return false;
        }
        // Only funds the investor actually holds units in are withdrawable — matches the
        // dashboard's holding definition (HoldingsService) and excludes in-flight or
        // zero/null-unit orders that otherwise surfaced as "random funds" to redeem.
        if (order.getUnits() == null || order.getUnits().signum() <= 0) {
            return false;
        }
        // A SIP that produced units is held whether ACTIVE or SUCCESSFUL — units>0 above
        // already excludes mandates that haven't transacted (consistent with the dashboard).
        return AUM_STATUSES.contains(order.getOrderStatus());
    }

    /** Sum the positive units across redemption records whose status is in {@code statuses}. */
    private static BigDecimal sumRedemptionUnits(
            List<RedemptionRecord> records, Set<RedemptionStatus> statuses) {
        BigDecimal total = BigDecimal.ZERO;
        for (RedemptionRecord record : records) {
            if (record.getRedemptionStatus() == null
                    || !statuses.contains(record.getRedemptionStatus())) {
                continue;
            }
            BigDecimal units = positiveOrNull(record.getUnits());
            if (units != null) {
                total = total.add(units);
            }
        }
        return total;
    }

    private String resolveMaskedPayoutBank(UUID investorId) {
        return bankAccountRepository.findByInvestorId(investorId).stream()
                .filter(b -> b.getVerificationStatus() == BankVerificationStatus.VERIFIED)
                .findFirst()
                .or(() -> bankAccountRepository.findByInvestorId(investorId).stream().findFirst())
                .map(PortfolioService::maskBank)
                .orElse(null);
    }

    private static String maskBank(InvestorBankAccount bank) {
        String account = bank.getAccountNumber();
        String masked = "account on file";
        if (StringUtils.hasText(account)) {
            String trimmed = account.trim();
            String last4 = trimmed.length() <= 4 ? trimmed : trimmed.substring(trimmed.length() - 4);
            masked = "••••" + last4;
        }
        return StringUtils.hasText(bank.getBankName()) ? bank.getBankName() + " " + masked : masked;
    }

    private NavSnapshot resolveNav(ProductScheme scheme) {
        if (scheme == null || !StringUtils.hasText(scheme.getMetadataJson())) {
            return NavSnapshot.EMPTY;
        }
        try {
            JsonNode root = objectMapper.readTree(scheme.getMetadataJson());
            BigDecimal nav = decimalOrNull(root, "nav");
            if (nav == null || nav.signum() <= 0) {
                return NavSnapshot.EMPTY;
            }
            OffsetDateTime asOf = parseAsOf(root);
            return new NavSnapshot(nav, asOf);
        } catch (Exception ex) {
            return NavSnapshot.EMPTY;
        }
    }

    private static OffsetDateTime parseAsOf(JsonNode root) {
        for (String field : List.of("nav_date", "navDate", "nav_as_of", "navAsOf", "as_of")) {
            JsonNode node = root.path(field);
            if (node.isTextual() && StringUtils.hasText(node.asText())) {
                try {
                    return OffsetDateTime.parse(node.asText().trim());
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private record NavSnapshot(BigDecimal value, OffsetDateTime asOf) {
        private static final NavSnapshot EMPTY = new NavSnapshot(null, null);
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
