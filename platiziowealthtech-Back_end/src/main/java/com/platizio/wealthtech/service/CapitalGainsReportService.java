package com.platizio.wealthtech.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.CapitalGainType;
import com.platizio.wealthtech.dto.CapitalGainsExportFormat;
import com.platizio.wealthtech.dto.CapitalGainsLineItemResponse;
import com.platizio.wealthtech.dto.CapitalGainsReportResponse;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CapitalGainsReportService {

    private static final List<OrderStatus> REALIZED_STATUSES = List.of(OrderStatus.COMPLETED, OrderStatus.SUCCESSFUL);
    private static final Set<TransactionType> PURCHASE_TYPES = Set.of(
            TransactionType.PURCHASE,
            TransactionType.LUMPSUM_PURCHASE,
            TransactionType.SIP
    );
    private static final Set<TransactionType> REDEMPTION_TYPES = Set.of(TransactionType.REDEMPTION, TransactionType.SWP);
    private static final Pattern FINANCIAL_YEAR_PATTERN = Pattern.compile("^(?:FY)?\\s*(\\d{4})(?:\\s*[-/]\\s*(\\d{2}|\\d{4}))?$", Pattern.CASE_INSENSITIVE);
    private static final LocalDate GRANDFATHERING_CUTOFF = LocalDate.of(2018, 1, 31);
    private static final int EQUITY_LONG_TERM_DAYS = 365;
    private static final int DEFAULT_LONG_TERM_DAYS = 1095;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final TransactionOrderRepository orderRepository;
    private final ProductSchemeRepository schemeRepository;
    private final InvestorRepository investorRepository;
    private final ObjectMapper objectMapper;

    public CapitalGainsReportService(
            TransactionOrderRepository orderRepository,
            ProductSchemeRepository schemeRepository,
            InvestorRepository investorRepository,
            ObjectMapper objectMapper
    ) {
        this.orderRepository = orderRepository;
        this.schemeRepository = schemeRepository;
        this.investorRepository = investorRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CapitalGainsReportResponse generate(UUID distributorId, String financialYear) {
        FinancialYear fy = FinancialYear.parse(financialYear);
        OffsetDateTime endExclusive = fy.endExclusive().atStartOfDay().atOffset(ZoneOffset.UTC);

        List<TransactionOrder> orders = orderRepository
                .findByDistributorIdAndOrderStatusInAndCreatedAtBefore(distributorId, REALIZED_STATUSES, endExclusive)
                .stream()
                .filter(order -> order.getCreatedAt() != null)
                .sorted(Comparator.comparing(TransactionOrder::getCreatedAt))
                .toList();

        Map<UUID, ProductScheme> schemes = schemesById(orders);
        Map<UUID, Investor> investors = investorsById(orders);
        Map<HoldingKey, List<PurchaseLot>> lotsByHolding = purchaseLots(orders);

        List<CapitalGainsLineItemResponse> lines = new ArrayList<>();
        for (TransactionOrder redemption : redemptionsInFinancialYear(orders, fy)) {
            allocateRedemption(redemption, lotsByHolding, schemes, investors, lines);
        }

        return new CapitalGainsReportResponse(
                distributorId,
                fy.label(),
                fy.start(),
                fy.endInclusive(),
                sum(lines, CapitalGainsLineItemResponse::saleValue),
                sum(lines, CapitalGainsLineItemResponse::purchaseCost),
                sum(lines, CapitalGainsLineItemResponse::grandfatheredCost),
                sum(lines.stream().filter(line -> line.gainType() == CapitalGainType.STCG).toList(), CapitalGainsLineItemResponse::capitalGain),
                sum(lines.stream().filter(line -> line.gainType() == CapitalGainType.LTCG).toList(), CapitalGainsLineItemResponse::capitalGain),
                lines
        );
    }

    @Transactional(readOnly = true)
    public String exportCsv(UUID distributorId, String financialYear, CapitalGainsExportFormat format) {
        CapitalGainsReportResponse report = generate(distributorId, financialYear);
        return switch (format) {
            case QUICKO -> quickoCsv(report);
            case CLEARTAX -> clearTaxCsv(report);
        };
    }

    private Map<UUID, ProductScheme> schemesById(List<TransactionOrder> orders) {
        List<UUID> ids = orders.stream()
                .map(TransactionOrder::getProductSchemeId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<UUID, ProductScheme> schemes = new HashMap<>();
        schemeRepository.findAllById(ids).forEach(scheme -> schemes.put(scheme.getId(), scheme));
        return schemes;
    }

    private Map<UUID, Investor> investorsById(List<TransactionOrder> orders) {
        List<UUID> ids = orders.stream()
                .map(TransactionOrder::getInvestorId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<UUID, Investor> investors = new HashMap<>();
        investorRepository.findAllById(ids).forEach(investor -> investors.put(investor.getId(), investor));
        return investors;
    }

    private Map<HoldingKey, List<PurchaseLot>> purchaseLots(List<TransactionOrder> orders) {
        Map<HoldingKey, List<PurchaseLot>> lotsByHolding = new LinkedHashMap<>();
        orders.stream()
                .filter(order -> PURCHASE_TYPES.contains(order.getTransactionType()))
                .filter(order -> positive(order.getUnits()) && positive(order.getAmount()))
                .forEach(order -> lotsByHolding
                        .computeIfAbsent(HoldingKey.from(order), ignored -> new ArrayList<>())
                        .add(new PurchaseLot(order, order.getUnits())));
        return lotsByHolding;
    }

    private List<TransactionOrder> redemptionsInFinancialYear(List<TransactionOrder> orders, FinancialYear fy) {
        return orders.stream()
                .filter(order -> REDEMPTION_TYPES.contains(order.getTransactionType()))
                .filter(order -> !orderDate(order).isBefore(fy.start()) && orderDate(order).isBefore(fy.endExclusive()))
                .filter(order -> positive(order.getUnits()) && positive(order.getAmount()))
                .sorted(Comparator.comparing(TransactionOrder::getCreatedAt))
                .toList();
    }

    private void allocateRedemption(
            TransactionOrder redemption,
            Map<HoldingKey, List<PurchaseLot>> lotsByHolding,
            Map<UUID, ProductScheme> schemes,
            Map<UUID, Investor> investors,
            List<CapitalGainsLineItemResponse> lines
    ) {
        HoldingKey key = HoldingKey.from(redemption);
        List<PurchaseLot> lots = lotsByHolding.getOrDefault(key, List.of());
        ProductScheme scheme = schemes.get(redemption.getProductSchemeId());
        Investor investor = investors.get(redemption.getInvestorId());
        BigDecimal remainingUnits = redemption.getUnits();
        BigDecimal salePricePerUnit = divide(redemption.getAmount(), redemption.getUnits(), 8);

        for (PurchaseLot lot : lots) {
            if (!positive(remainingUnits)) {
                break;
            }
            if (!orderDate(lot.order()).isBefore(orderDate(redemption)) && !orderDate(lot.order()).isEqual(orderDate(redemption))) {
                continue;
            }
            BigDecimal allocatedUnits = lot.consume(remainingUnits);
            if (!positive(allocatedUnits)) {
                continue;
            }
            remainingUnits = remainingUnits.subtract(allocatedUnits);
            lines.add(lineItem(redemption, lot.order(), allocatedUnits, salePricePerUnit, scheme, investor));
        }

        if (positive(remainingUnits)) {
            lines.add(unmatchedLineItem(redemption, remainingUnits, salePricePerUnit, scheme, investor));
        }
    }

    private CapitalGainsLineItemResponse lineItem(
            TransactionOrder redemption,
            TransactionOrder purchase,
            BigDecimal units,
            BigDecimal salePricePerUnit,
            ProductScheme scheme,
            Investor investor
    ) {
        BigDecimal purchasePricePerUnit = divide(purchase.getAmount(), purchase.getUnits(), 8);
        BigDecimal saleValue = money(salePricePerUnit.multiply(units));
        BigDecimal purchaseCost = money(purchasePricePerUnit.multiply(units));
        CapitalGainType gainType = gainType(purchase, redemption, scheme);
        BigDecimal fairMarketValue = fairMarketValue(scheme, units);
        BigDecimal grandfatheredCost = grandfatheredCost(purchase, redemption, scheme, units, salePricePerUnit, purchasePricePerUnit);
        BigDecimal taxableCost = gainType == CapitalGainType.LTCG && grandfatheredCost.compareTo(purchaseCost) > 0
                ? grandfatheredCost
                : purchaseCost;

        return new CapitalGainsLineItemResponse(
                redemption.getInvestorId(),
                investor == null ? null : investor.getFullName(),
                investor == null ? null : investor.getPan(),
                redemption.getProductSchemeId(),
                scheme == null ? null : scheme.getSchemeName(),
                scheme == null ? null : scheme.getExternalIsin(),
                scheme == null ? null : scheme.getCategory(),
                orderDate(purchase),
                orderDate(redemption),
                units(units),
                saleValue,
                purchaseCost,
                fairMarketValue,
                grandfatheredCost,
                taxableCost,
                money(saleValue.subtract(taxableCost)),
                gainType
        );
    }

    private CapitalGainsLineItemResponse unmatchedLineItem(
            TransactionOrder redemption,
            BigDecimal units,
            BigDecimal salePricePerUnit,
            ProductScheme scheme,
            Investor investor
    ) {
        BigDecimal saleValue = money(salePricePerUnit.multiply(units));
        return new CapitalGainsLineItemResponse(
                redemption.getInvestorId(),
                investor == null ? null : investor.getFullName(),
                investor == null ? null : investor.getPan(),
                redemption.getProductSchemeId(),
                scheme == null ? null : scheme.getSchemeName(),
                scheme == null ? null : scheme.getExternalIsin(),
                scheme == null ? null : scheme.getCategory(),
                null,
                orderDate(redemption),
                units(units),
                saleValue,
                ZERO,
                ZERO,
                ZERO,
                ZERO,
                saleValue,
                CapitalGainType.STCG
        );
    }

    private CapitalGainType gainType(TransactionOrder purchase, TransactionOrder redemption, ProductScheme scheme) {
        long holdingDays = ChronoUnit.DAYS.between(orderDate(purchase), orderDate(redemption));
        int longTermDays = isEquityOriented(scheme) ? EQUITY_LONG_TERM_DAYS : DEFAULT_LONG_TERM_DAYS;
        return holdingDays > longTermDays ? CapitalGainType.LTCG : CapitalGainType.STCG;
    }

    private boolean isEquityOriented(ProductScheme scheme) {
        if (scheme == null || scheme.getCategory() == null) {
            return false;
        }
        return scheme.getCategory() == ProductCategory.EQUITY
                || scheme.getCategory() == ProductCategory.MF
                || scheme.getCategory() == ProductCategory.MUTUAL_FUND;
    }

    private BigDecimal grandfatheredCost(
            TransactionOrder purchase,
            TransactionOrder redemption,
            ProductScheme scheme,
            BigDecimal units,
            BigDecimal salePricePerUnit,
            BigDecimal purchasePricePerUnit
    ) {
        if (!isEquityOriented(scheme)
                || !orderDate(purchase).isBefore(GRANDFATHERING_CUTOFF.plusDays(1))
                || !orderDate(redemption).isAfter(GRANDFATHERING_CUTOFF)) {
            return money(purchasePricePerUnit.multiply(units));
        }

        BigDecimal fmvPerUnit = fairMarketValuePerUnit(scheme).orElse(purchasePricePerUnit);
        BigDecimal grandfatheredUnitCost = salePricePerUnit.min(purchasePricePerUnit.max(fmvPerUnit));
        return money(grandfatheredUnitCost.multiply(units));
    }

    private BigDecimal fairMarketValue(ProductScheme scheme, BigDecimal units) {
        return fairMarketValuePerUnit(scheme)
                .map(value -> money(value.multiply(units)))
                .orElse(ZERO);
    }

    private Optional<BigDecimal> fairMarketValuePerUnit(ProductScheme scheme) {
        if (scheme == null || !StringUtils.hasText(scheme.getMetadataJson())) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(scheme.getMetadataJson());
            for (String field : List.of(
                    "grandfatheredNav",
                    "grandfathered_nav",
                    "nav_2018_01_31",
                    "navAsOn20180131",
                    "fairMarketValueAsOn20180131",
                    "fmv_2018_01_31"
            )) {
                JsonNode value = root.path(field);
                if (value.isNumber()) {
                    return Optional.of(value.decimalValue());
                }
                if (value.isTextual() && StringUtils.hasText(value.asText())) {
                    return Optional.of(new BigDecimal(value.asText().trim()));
                }
            }
        } catch (RuntimeException ex) {
            return Optional.empty();
        } catch (Exception ex) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private String quickoCsv(CapitalGainsReportResponse report) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(
                "Financial Year",
                "Investor PAN",
                "Investor Name",
                "ISIN",
                "Scheme Name",
                "Asset Type",
                "Purchase Date",
                "Sale Date",
                "Units",
                "Sale Value",
                "Purchase Cost",
                "FMV as on 31-Jan-2018",
                "Grandfathered Cost",
                "Taxable Cost",
                "Capital Gain",
                "Gain Type"
        ));
        for (CapitalGainsLineItemResponse line : report.lineItems()) {
            rows.add(List.of(
                    report.financialYear(),
                    text(line.investorPan()),
                    text(line.investorName()),
                    text(line.isin()),
                    text(line.schemeName()),
                    text(line.assetCategory()),
                    text(line.purchaseDate()),
                    text(line.saleDate()),
                    amount(line.units()),
                    amount(line.saleValue()),
                    amount(line.purchaseCost()),
                    amount(line.fairMarketValueAsOf20180131()),
                    amount(line.grandfatheredCost()),
                    amount(line.taxableCost()),
                    amount(line.capitalGain()),
                    line.gainType().name()
            ));
        }
        return csv(rows);
    }

    private String clearTaxCsv(CapitalGainsReportResponse report) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(
                "Asset Type",
                "ISIN",
                "Security Name",
                "Date of Purchase",
                "Date of Sale",
                "Sale Consideration",
                "Cost of Acquisition",
                "FMV as on 31-Jan-2018",
                "Grandfathered Cost",
                "Expense on Transfer",
                "STCG",
                "LTCG",
                "Investor PAN"
        ));
        for (CapitalGainsLineItemResponse line : report.lineItems()) {
            rows.add(List.of(
                    text(line.assetCategory()),
                    text(line.isin()),
                    text(line.schemeName()),
                    text(line.purchaseDate()),
                    text(line.saleDate()),
                    amount(line.saleValue()),
                    amount(line.purchaseCost()),
                    amount(line.fairMarketValueAsOf20180131()),
                    amount(line.grandfatheredCost()),
                    "0.00",
                    line.gainType() == CapitalGainType.STCG ? amount(line.capitalGain()) : "0.00",
                    line.gainType() == CapitalGainType.LTCG ? amount(line.capitalGain()) : "0.00",
                    text(line.investorPan())
            ));
        }
        return csv(rows);
    }

    private String csv(List<List<String>> rows) {
        return rows.stream()
                .map(row -> row.stream().map(this::escapeCsv).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\n")) + "\n";
    }

    private String escapeCsv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private BigDecimal sum(Collection<CapitalGainsLineItemResponse> lines, java.util.function.Function<CapitalGainsLineItemResponse, BigDecimal> valueExtractor) {
        return lines.stream()
                .map(valueExtractor)
                .filter(value -> value != null)
                .reduce(ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal divide(BigDecimal dividend, BigDecimal divisor, int scale) {
        return dividend.divide(divisor, scale, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal units(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private String amount(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private LocalDate orderDate(TransactionOrder order) {
        return order.getCreatedAt().toLocalDate();
    }

    private record HoldingKey(UUID investorId, UUID schemeId) {
        static HoldingKey from(TransactionOrder order) {
            return new HoldingKey(order.getInvestorId(), order.getProductSchemeId());
        }
    }

    private static final class PurchaseLot {
        private final TransactionOrder order;
        private BigDecimal remainingUnits;

        private PurchaseLot(TransactionOrder order, BigDecimal remainingUnits) {
            this.order = order;
            this.remainingUnits = remainingUnits;
        }

        private TransactionOrder order() {
            return order;
        }

        private BigDecimal consume(BigDecimal requestedUnits) {
            BigDecimal allocated = remainingUnits.min(requestedUnits);
            remainingUnits = remainingUnits.subtract(allocated);
            return allocated;
        }
    }

    private record FinancialYear(String label, LocalDate start, LocalDate endExclusive) {
        static FinancialYear parse(String rawFinancialYear) {
            if (!StringUtils.hasText(rawFinancialYear)) {
                LocalDate today = LocalDate.now();
                int startYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
                return fromStartYear(startYear);
            }

            Matcher matcher = FINANCIAL_YEAR_PATTERN.matcher(rawFinancialYear.trim());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("financialYear must be like 2024-2025 or FY2024-25");
            }

            int startYear = Integer.parseInt(matcher.group(1));
            String endYearText = matcher.group(2);
            if (StringUtils.hasText(endYearText)) {
                int expectedEndYear = startYear + 1;
                int suppliedEndYear = endYearText.length() == 2
                        ? Integer.parseInt(String.valueOf(startYear).substring(0, 2) + endYearText)
                        : Integer.parseInt(endYearText);
                if (suppliedEndYear != expectedEndYear) {
                    throw new IllegalArgumentException("financialYear end year must be the year after the start year");
                }
            }
            return fromStartYear(startYear);
        }

        private static FinancialYear fromStartYear(int startYear) {
            LocalDate start = LocalDate.of(startYear, 4, 1);
            LocalDate endExclusive = LocalDate.of(startYear + 1, 4, 1);
            return new FinancialYear(startYear + "-" + (startYear + 1), start, endExclusive);
        }

        private LocalDate endInclusive() {
            return endExclusive.minusDays(1);
        }
    }
}
