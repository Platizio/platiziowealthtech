package com.platizio.wealthtech.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.RedemptionRecord;
import com.platizio.wealthtech.domain.RedemptionStatus;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.InvestorDashboardResponse;
import com.platizio.wealthtech.dto.InvestorDashboardResponse.DashboardHolding;
import com.platizio.wealthtech.dto.InvestorDashboardResponse.DashboardTotals;
import com.platizio.wealthtech.dto.InvestorDashboardResponse.DataQuality;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.RedemptionRecordRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Phase-3 investor holdings dashboard (FR-DASH). Computes, per investor and
 * grouped by scheme:
 * <ul>
 *   <li><b>Net units</b> — settled purchase units (PURCHASE/LUMPSUM_PURCHASE/SIP in
 *       SUCCESSFUL/COMPLETED/ACTIVE) minus successfully redeemed units. Units are
 *       NEVER derived from the order amount.</li>
 *   <li><b>Valuation</b> — net units × latest NAV from scheme metadata. When units
 *       or NAV are unavailable, {@code currentValue} is {@code null} and
 *       {@code dataQuality=UNAVAILABLE}; a NAV with no fresh as-of is {@code STALE}
 *       (locked decision #4 — no fall back to amount/zero).</li>
 *   <li><b>Cost basis</b> — weighted-average invested cost of the units currently
 *       held (invested purchase cost reduced pro-rata as units are redeemed).</li>
 *   <li><b>Returns</b> — absolute (currentValue − invested), percent, a 1-day return
 *       when a previous-day NAV is present (else left {@code null}, never zeroed),
 *       and money-weighted {@link XirrCalculator XIRR} per holding and for the
 *       portfolio.</li>
 * </ul>
 *
 * <p>Read-only and investor-scoped: it never reuses the distributor-scoped portfolio
 * path and does not touch the Phase-2 {@code GET /investor/holdings} contract.
 */
@Service
public class HoldingsService {

    /** Statuses whose purchase units count toward holdings (SIP must additionally be ACTIVE). */
    private static final Set<OrderStatus> HELD_STATUSES = Set.of(
            OrderStatus.SUCCESSFUL,
            OrderStatus.COMPLETED,
            OrderStatus.ACTIVE);

    private static final Set<TransactionType> PURCHASE_TYPES = Set.of(
            TransactionType.PURCHASE,
            TransactionType.LUMPSUM_PURCHASE,
            TransactionType.SIP);

    private static final int UNIT_SCALE = 4;
    private static final int MONEY_SCALE = 2;
    private static final int NAV_SCALE = 4;
    private static final int PERCENT_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final TransactionOrderRepository orderRepository;
    private final RedemptionRecordRepository redemptionRepository;
    private final ProductSchemeRepository schemeRepository;
    private final ObjectMapper objectMapper;

    public HoldingsService(
            TransactionOrderRepository orderRepository,
            RedemptionRecordRepository redemptionRepository,
            ProductSchemeRepository schemeRepository,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.redemptionRepository = redemptionRepository;
        this.schemeRepository = schemeRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public InvestorDashboardResponse getDashboard(UUID investorId) {
        if (investorId == null) {
            return new InvestorDashboardResponse(List.of(), emptyTotals());
        }

        List<TransactionOrder> orders = orderRepository.findByInvestorId(investorId);
        List<RedemptionRecord> redemptions = redemptionRepository.findByInvestorId(investorId).stream()
                .filter(r -> r.getRedemptionStatus() == RedemptionStatus.SUCCESSFUL)
                .toList();

        Map<UUID, TransactionOrder> orderById = orders.stream()
                .filter(o -> o.getId() != null)
                .collect(Collectors.toMap(TransactionOrder::getId, o -> o, (a, b) -> a));

        Set<UUID> schemeIds = orders.stream()
                .map(TransactionOrder::getProductSchemeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, ProductScheme> schemeMap = schemeIds.isEmpty()
                ? Map.of()
                : schemeRepository.findAllById(schemeIds).stream()
                        .collect(Collectors.toMap(ProductScheme::getId, s -> s));

        // Group purchases and redemptions by scheme (the holding key). Insertion
        // order is preserved for a stable response ordering before the final sort.
        Map<UUID, SchemeAccumulator> bySchema = new LinkedHashMap<>();
        for (TransactionOrder order : orders) {
            if (!isHeldPurchase(order)) {
                continue;
            }
            bySchema.computeIfAbsent(order.getProductSchemeId(), SchemeAccumulator::new)
                    .addPurchase(order);
        }
        for (RedemptionRecord redemption : redemptions) {
            UUID schemeId = resolveRedemptionScheme(redemption, orderById);
            if (schemeId == null || !bySchema.containsKey(schemeId)) {
                continue;
            }
            bySchema.get(schemeId).addRedemption(redemption);
        }

        LocalDate today = LocalDate.now();
        List<DashboardHolding> holdings = new ArrayList<>();
        List<XirrCalculator.DecimalCashflow> portfolioCashflows = new ArrayList<>();

        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalCurrentValue = BigDecimal.ZERO;
        BigDecimal totalOneDayReturn = BigDecimal.ZERO;
        boolean anyCurrentValue = false;
        boolean anyOneDayReturn = false;

        for (SchemeAccumulator acc : bySchema.values()) {
            BigDecimal netUnits = acc.netUnits();
            if (netUnits.signum() <= 0) {
                continue; // fully redeemed — no live holding to show
            }

            ProductScheme scheme = schemeMap.get(acc.schemeId);
            NavSnapshot nav = resolveNav(scheme);
            BigDecimal invested = acc.investedCostForHeldUnits().setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            BigDecimal currentValue;
            DataQuality quality;
            if (nav.value == null) {
                currentValue = null; // locked decision #4 — never fabricate value
                quality = DataQuality.UNAVAILABLE;
            } else if (nav.asOf == null) {
                currentValue = nav.value.multiply(netUnits).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                quality = DataQuality.STALE;
            } else {
                currentValue = nav.value.multiply(netUnits).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                quality = DataQuality.OK;
            }

            BigDecimal absoluteReturn = currentValue == null ? null
                    : currentValue.subtract(invested).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal percentReturn = (currentValue == null || invested.signum() == 0) ? null
                    : currentValue.subtract(invested)
                            .divide(invested, 6, RoundingMode.HALF_UP)
                            .multiply(HUNDRED)
                            .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);

            // 1-day return needs a previous-day NAV; absent → unavailable (null), never zero.
            BigDecimal oneDayReturn = null;
            BigDecimal oneDayReturnPercent = null;
            if (nav.value != null && nav.previousValue != null && nav.previousValue.signum() > 0) {
                BigDecimal navDelta = nav.value.subtract(nav.previousValue);
                oneDayReturn = navDelta.multiply(netUnits).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                oneDayReturnPercent = navDelta
                        .divide(nav.previousValue, 6, RoundingMode.HALF_UP)
                        .multiply(HUNDRED)
                        .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
            }

            // Per-holding XIRR: purchases out, redemptions in, plus current value today.
            List<XirrCalculator.DecimalCashflow> holdingCashflows = acc.cashflows();
            if (currentValue != null && currentValue.signum() != 0) {
                holdingCashflows.add(new XirrCalculator.DecimalCashflow(today, currentValue));
                portfolioCashflows.addAll(acc.cashflows());
                portfolioCashflows.add(new XirrCalculator.DecimalCashflow(today, currentValue));
            }
            Double xirr = XirrCalculator.computeDecimal(holdingCashflows);

            String folio = acc.representativeFolio();
            holdings.add(new DashboardHolding(
                    acc.schemeId,
                    scheme != null ? ProductSchemeOrderSupport.displayName(scheme) : "Unknown fund",
                    scheme != null ? scheme.getAmcName() : "—",
                    resolveCategory(acc.representativePurchase(), scheme),
                    folio,
                    acc.sipName(),
                    netUnits.setScale(UNIT_SCALE, RoundingMode.HALF_UP),
                    nav.value == null ? null : nav.value.setScale(NAV_SCALE, RoundingMode.HALF_UP),
                    nav.asOf,
                    acc.averageCostNav(),
                    invested,
                    currentValue,
                    absoluteReturn,
                    percentReturn,
                    oneDayReturn,
                    oneDayReturnPercent,
                    xirr,
                    quality));

            totalInvested = totalInvested.add(invested);
            if (currentValue != null) {
                totalCurrentValue = totalCurrentValue.add(currentValue);
                anyCurrentValue = true;
            }
            if (oneDayReturn != null) {
                totalOneDayReturn = totalOneDayReturn.add(oneDayReturn);
                anyOneDayReturn = true;
            }
        }

        holdings.sort(Comparator.comparing(
                (DashboardHolding h) -> h.currentValue() == null ? BigDecimal.valueOf(-1) : h.currentValue())
                .reversed()
                .thenComparing(DashboardHolding::schemeName, Comparator.nullsLast(Comparator.naturalOrder())));

        totalInvested = totalInvested.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal totalCurrentValueOut = anyCurrentValue
                ? totalCurrentValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal totalReturn = totalCurrentValueOut == null ? null
                : totalCurrentValueOut.subtract(totalInvested).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal totalReturnPercent = (totalCurrentValueOut == null || totalInvested.signum() == 0) ? null
                : totalCurrentValueOut.subtract(totalInvested)
                        .divide(totalInvested, 6, RoundingMode.HALF_UP)
                        .multiply(HUNDRED)
                        .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
        BigDecimal totalOneDayReturnOut = anyOneDayReturn
                ? totalOneDayReturn.setScale(MONEY_SCALE, RoundingMode.HALF_UP) : null;
        Double portfolioXirr = XirrCalculator.computeDecimal(portfolioCashflows);

        DashboardTotals totals = new DashboardTotals(
                totalInvested,
                totalCurrentValueOut,
                totalReturn,
                totalReturnPercent,
                totalOneDayReturnOut,
                portfolioXirr);

        return new InvestorDashboardResponse(holdings, totals);
    }

    private static InvestorDashboardResponse.DashboardTotals emptyTotals() {
        return new DashboardTotals(
                BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                null, null, null, null, null);
    }

    private static boolean isHeldPurchase(TransactionOrder order) {
        if (order.getProductSchemeId() == null
                || !PURCHASE_TYPES.contains(order.getTransactionType())
                || order.getUnits() == null
                || order.getUnits().signum() <= 0) {
            return false;
        }
        boolean isSip = order.getTransactionType() == TransactionType.SIP;
        if (isSip && order.getOrderStatus() != OrderStatus.ACTIVE) {
            return false;
        }
        return HELD_STATUSES.contains(order.getOrderStatus());
    }

    /** A successful redemption maps to its originating order's scheme. */
    private static UUID resolveRedemptionScheme(RedemptionRecord redemption, Map<UUID, TransactionOrder> orderById) {
        if (redemption.getOrderId() == null) {
            return null;
        }
        TransactionOrder order = orderById.get(redemption.getOrderId());
        return order == null ? null : order.getProductSchemeId();
    }

    private static String resolveCategory(TransactionOrder order, ProductScheme scheme) {
        if (order != null && order.getProductCategory() != null) {
            return order.getProductCategory() == ProductCategory.MF ? "MF" : "SIF";
        }
        if (scheme != null && scheme.getCategory() != null) {
            String raw = scheme.getCategory().name().toUpperCase();
            return raw.contains("MF") || raw.contains("MUTUAL") ? "MF" : "SIF";
        }
        return "MF";
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
            java.time.OffsetDateTime asOf = parseAsOf(root);
            BigDecimal previous = parsePreviousNav(root);
            return new NavSnapshot(nav, asOf, previous);
        } catch (Exception ex) {
            return NavSnapshot.EMPTY;
        }
    }

    private static java.time.OffsetDateTime parseAsOf(JsonNode root) {
        for (String field : List.of("nav_date", "navDate", "nav_as_of", "navAsOf", "as_of")) {
            JsonNode node = root.path(field);
            if (node.isTextual() && StringUtils.hasText(node.asText())) {
                try {
                    return java.time.OffsetDateTime.parse(node.asText().trim());
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Previous-day NAV for the 1-day return. Absent → null (return is unavailable, not zero). */
    private static BigDecimal parsePreviousNav(JsonNode root) {
        for (String field : List.of(
                "previous_nav", "previousNav", "prev_nav", "prevNav",
                "nav_previous", "prev_day_nav", "previousDayNav", "nav_t1", "last_nav")) {
            BigDecimal value = decimalOrNull(root, field);
            if (value != null && value.signum() > 0) {
                return value;
            }
        }
        return null;
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isTextual() && StringUtils.hasText(value.asText())) {
            try {
                return new BigDecimal(value.asText().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private record NavSnapshot(BigDecimal value, java.time.OffsetDateTime asOf, BigDecimal previousValue) {
        private static final NavSnapshot EMPTY = new NavSnapshot(null, null, null);
    }

    /**
     * Accumulates one scheme's settled purchases and successful redemptions, and
     * derives net units + the weighted-average cost basis of the units still held.
     *
     * <p>Cost basis is reduced pro-rata as units are redeemed (a FIFO-equivalent for
     * a single weighted-average pool): the average purchase cost per unit is held
     * constant and applied to the remaining net units, which is the standard
     * average-cost convention for an open holding.
     */
    private static final class SchemeAccumulator {
        private final UUID schemeId;
        private final List<TransactionOrder> purchases = new ArrayList<>();
        private final List<RedemptionRecord> redemptions = new ArrayList<>();

        private SchemeAccumulator(UUID schemeId) {
            this.schemeId = schemeId;
        }

        private void addPurchase(TransactionOrder order) {
            purchases.add(order);
        }

        private void addRedemption(RedemptionRecord redemption) {
            redemptions.add(redemption);
        }

        private BigDecimal totalPurchaseUnits() {
            return purchases.stream()
                    .map(TransactionOrder::getUnits)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private BigDecimal totalRedeemedUnits() {
            return redemptions.stream()
                    .map(RedemptionRecord::getUnits)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private BigDecimal netUnits() {
            return totalPurchaseUnits().subtract(totalRedeemedUnits());
        }

        private BigDecimal totalInvestedAmount() {
            return purchases.stream()
                    .map(TransactionOrder::getAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** Weighted-average purchase NAV across all purchase lots (invested / units). */
        private BigDecimal averageCostNav() {
            BigDecimal units = totalPurchaseUnits();
            if (units.signum() <= 0) {
                return null;
            }
            return totalInvestedAmount().divide(units, NAV_SCALE, RoundingMode.HALF_UP);
        }

        /** Invested cost attributed to the units still held = avgCostNav × netUnits. */
        private BigDecimal investedCostForHeldUnits() {
            BigDecimal net = netUnits();
            if (net.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal avg = averageCostNav();
            if (avg == null) {
                return BigDecimal.ZERO;
            }
            return avg.multiply(net);
        }

        /**
         * Dated cashflows for XIRR (caller appends the current value dated today):
         * each purchase is a negative cashflow on its createdAt date, each successful
         * redemption a positive cashflow on its createdAt date.
         */
        private List<XirrCalculator.DecimalCashflow> cashflows() {
            List<XirrCalculator.DecimalCashflow> flows = new ArrayList<>();
            for (TransactionOrder purchase : purchases) {
                if (purchase.getAmount() == null || purchase.getCreatedAt() == null) {
                    continue;
                }
                flows.add(new XirrCalculator.DecimalCashflow(
                        purchase.getCreatedAt().toLocalDate(),
                        purchase.getAmount().negate()));
            }
            for (RedemptionRecord redemption : redemptions) {
                if (redemption.getAmount() == null || redemption.getCreatedAt() == null) {
                    continue;
                }
                flows.add(new XirrCalculator.DecimalCashflow(
                        redemption.getCreatedAt().toLocalDate(),
                        redemption.getAmount()));
            }
            return flows;
        }

        private TransactionOrder representativePurchase() {
            return purchases.isEmpty() ? null : purchases.get(0);
        }

        private String representativeFolio() {
            return purchases.stream()
                    .map(TransactionOrder::getExternalOrderId)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
        }

        /** A SIP label from the first SIP purchase, if any (else null). */
        private String sipName() {
            return purchases.stream()
                    .filter(p -> p.getTransactionType() == TransactionType.SIP)
                    .map(p -> StringUtils.hasText(p.getSipFrequency())
                            ? "SIP (" + p.getSipFrequency() + ")" : "SIP")
                    .findFirst()
                    .orElse(null);
        }
    }
}
