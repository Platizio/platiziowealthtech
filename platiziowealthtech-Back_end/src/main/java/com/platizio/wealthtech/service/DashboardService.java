package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.dto.*;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class DashboardService {

    private static final Map<OrderStatus, String> SIP_STATUS_LABEL = Map.of(
            OrderStatus.ACTIVE, "Active SIPs",
            OrderStatus.PROCESSING, "Active SIPs",
            OrderStatus.PAUSED, "Paused SIPs",
            OrderStatus.COMPLETED, "Completed SIPs",
            OrderStatus.FAILED, "Failed SIPs"
    );
    private static final Map<OrderStatus, OrderStatus> SIP_STATUS_GROUP = Map.ofEntries(
            Map.entry(OrderStatus.ACTIVE, OrderStatus.ACTIVE),
            Map.entry(OrderStatus.PROCESSING, OrderStatus.PROCESSING),
            Map.entry(OrderStatus.PAUSED, OrderStatus.PAUSED),
            Map.entry(OrderStatus.COMPLETED, OrderStatus.COMPLETED),
            Map.entry(OrderStatus.FAILED, OrderStatus.FAILED),
            Map.entry(OrderStatus.SUCCESSFUL, OrderStatus.ACTIVE),
            Map.entry(OrderStatus.CREATED, OrderStatus.PROCESSING),
            Map.entry(OrderStatus.PENDING_INVESTOR_ACTION, OrderStatus.PROCESSING),
            Map.entry(OrderStatus.PAYMENT_PENDING, OrderStatus.PROCESSING),
            Map.entry(OrderStatus.SUBMITTED, OrderStatus.PROCESSING),
            Map.entry(OrderStatus.RETRY_AVAILABLE, OrderStatus.PROCESSING),
            Map.entry(OrderStatus.DRAFT, OrderStatus.PAUSED)
    );
    private static final List<String> SIP_STATUS_LABEL_ORDER = List.of(
            "Active SIPs",
            "Paused SIPs",
            "Completed SIPs",
            "Failed SIPs"
    );

    private final InvestorRepository investorRepository;
    private final TransactionOrderRepository orderRepository;
    private final ProductSchemeRepository schemeRepository;

    public DashboardService(InvestorRepository investorRepository, TransactionOrderRepository orderRepository, ProductSchemeRepository schemeRepository) {
        this.investorRepository = investorRepository;
        this.orderRepository = orderRepository;
        this.schemeRepository = schemeRepository;
    }

    public SipDashboardDto getSipDashboard(UUID distributorId) {
        List<TransactionOrder> sipOrders = orderRepository
                .findByDistributorIdAndTransactionType(
                        distributorId,
                        TransactionType.SIP,
                        PageRequest.of(0, 50))
                .getContent();

        Map<UUID, Investor> investorMap = investorRepository.findByDistributorId(distributorId).stream()
                .collect(Collectors.toMap(Investor::getId, i -> i));

        // Collect only the scheme IDs actually referenced by this distributor's SIP orders,
        // then fetch just those rows — avoids a full table scan of all 482+ schemes.
        Set<UUID> neededSchemeIds = sipOrders.stream()
                .map(TransactionOrder::getProductSchemeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, ProductScheme> schemeMap = schemeRepository.findAllById(neededSchemeIds).stream()
                .collect(Collectors.toMap(ProductScheme::getId, s -> s));

        List<SipItemDto> sips = sipOrders.stream().map(o -> {
            Investor inv = investorMap.get(o.getInvestorId());
            ProductScheme scheme = schemeMap.get(o.getProductSchemeId());
            String status = sipStatusLabel(o.getOrderStatus());
            
            // Apply robust bucketing logic
            String rawCat = "OTHER";
            if (o.getProductCategory() != null) {
                rawCat = o.getProductCategory().toString();
            } else if (scheme != null && scheme.getCategory() != null) {
                rawCat = scheme.getCategory().toString();
            }
            rawCat = rawCat.toUpperCase();
            String category = (rawCat.contains("MF") || rawCat.contains("MUTUAL")) ? "MF" : "SIF";

            return new SipItemDto(
                    o.getId().toString(),
                    inv != null ? inv.getFullName() : "Unknown",
                    scheme != null ? scheme.getSchemeName() : "Unknown",
                    "₹" + (o.getAmount() != null ? o.getAmount().toString() : "0"),
                    status,
                    "N/A",
                    o.getMandateMode() != null ? o.getMandateMode() : "Unknown",
                    category
            );
        }).collect(Collectors.toList());
        Map<String, Long> statusCounts = sipStatusCounts(distributorId);

        // ── Real SIP trend: aggregate transaction_orders by createdAt month ──────
        // Build a rolling 6-month window anchored to today (e.g. Jan–Jun when run in June)
        YearMonth currentMonth = YearMonth.now();
        List<YearMonth> window = IntStream.rangeClosed(0, 5)
                .mapToObj(i -> currentMonth.minusMonths(5 - i))   // oldest → newest
                .collect(Collectors.toList());

        // Fetch only SIP orders created within the 6-month window (single DB query)
        OffsetDateTime windowStart = window.get(0)
                .atDay(1)
                .atStartOfDay()
                .atOffset(ZoneOffset.UTC);

        List<TransactionOrder> recentSipOrders = orderRepository
                .findByDistributorIdAndTransactionTypeAndCreatedAtAfter(
                        distributorId, TransactionType.SIP, windowStart);

        // Group by YearMonth → sum amounts (null-safe)
        Map<YearMonth, BigDecimal> amountByMonth = recentSipOrders.stream()
                .filter(o -> o.getAmount() != null)
                .collect(Collectors.groupingBy(
                        o -> YearMonth.from(o.getCreatedAt().toLocalDate()),
                        Collectors.reducing(BigDecimal.ZERO,
                                TransactionOrder::getAmount,
                                BigDecimal::add)));

        // Group by YearMonth → count orders
        Map<YearMonth, Long> countByMonth = recentSipOrders.stream()
                .collect(Collectors.groupingBy(
                        o -> YearMonth.from(o.getCreatedAt().toLocalDate()),
                        Collectors.counting()));

        // Map each slot in the window to a SipTrendDto (zero-fill months with no data)
        List<SipTrendDto> trend = window.stream()
                .map(ym -> new SipTrendDto(
                        ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), // "Jan", "Feb" …
                        amountByMonth.getOrDefault(ym, BigDecimal.ZERO),
                        countByMonth.getOrDefault(ym, 0L).intValue()))
                .collect(Collectors.toList());

        return new SipDashboardDto(trend, sips, statusCounts);
    }

    public List<ActionItemDto> getActionCenter(UUID distributorId) {
        List<ActionItemDto> actions = new ArrayList<>();
        List<Investor> investors = investorRepository.findByDistributorId(distributorId);
        List<TransactionOrder> orders = orderRepository.findByDistributorId(distributorId);

        // O(1) investor lookup keyed by id. Replaces the previous per-order
        // investors.stream().filter().findFirst() linear scan, which made the
        // failed-order loop O(orders ├ù investors). Same idiom as getSipDashboard().
        Map<UUID, Investor> investorById = investors.stream()
                .collect(Collectors.toMap(Investor::getId, i -> i));

        int idCounter = 1;

        for (Investor inv : investors) {
            if (inv.getKycStatus() != KycStatus.COMPLETED) {
                actions.add(new ActionItemDto(String.valueOf(idCounter++), "KYC", "High", inv.getFullName(), "KYC verification is pending.", "Today"));
            }
            if (inv.getBankVerificationStatus() != BankVerificationStatus.VERIFIED) {
                actions.add(new ActionItemDto(String.valueOf(idCounter++), "Bank", "Medium", inv.getFullName(), "Bank account not verified.", "1 day"));
            }
        }

        for (TransactionOrder o : orders) {
            if (o.getOrderStatus() == OrderStatus.FAILED) {
                Investor inv = investorById.get(o.getInvestorId());
                String name = inv != null ? inv.getFullName() : "Unknown";
                String cat = o.getTransactionType() == TransactionType.SIP ? "SIP" : "Transaction";
                actions.add(new ActionItemDto(String.valueOf(idCounter++), cat, "High", name, "Transaction failed: " + (o.getFailureReason() != null ? o.getFailureReason() : "Unknown reason"), "Today"));
            }
        }

        return actions;
    }

    public OnboardingPipelineDto getOnboardingPipeline(UUID distributorId) {
        List<Investor> investors = investorRepository.findByDistributorId(distributorId);
        
        List<OnboardingCardDto> kycPending = new ArrayList<>();
        List<OnboardingCardDto> bankPending = new ArrayList<>();
        List<OnboardingCardDto> ready = new ArrayList<>();

        for (Investor inv : investors) {
            long daysAgo = ChronoUnit.DAYS.between(inv.getCreatedAt().toLocalDate(), LocalDate.now());
            String daysStr = daysAgo == 0 ? "Today" : daysAgo + " days ago";
            
            if (inv.getKycStatus() != KycStatus.COMPLETED) {
                kycPending.add(new OnboardingCardDto(inv.getId().toString(), inv.getFullName(), "KYC Pending", daysStr, "kyc"));
            } else if (inv.getBankVerificationStatus() != BankVerificationStatus.VERIFIED) {
                bankPending.add(new OnboardingCardDto(inv.getId().toString(), inv.getFullName(), "Bank Pending", daysStr, "bank"));
            } else {
                ready.add(new OnboardingCardDto(inv.getId().toString(), inv.getFullName(), "Ready to Invest", daysStr, "ready"));
            }
        }

        return new OnboardingPipelineDto(kycPending, bankPending, ready);
    }

    private Map<String, Long> sipStatusCounts(UUID distributorId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        SIP_STATUS_LABEL_ORDER.forEach(label -> counts.put(label, 0L));
        orderRepository
                .countByDistributorIdAndTransactionTypeGroupedByOrderStatus(distributorId, TransactionType.SIP)
                .forEach(row -> counts.merge(sipStatusLabel(row.getOrderStatus()), row.getTotal(), Long::sum));
        return counts;
    }

    private String sipStatusLabel(OrderStatus orderStatus) {
        OrderStatus groupedStatus = SIP_STATUS_GROUP.getOrDefault(orderStatus, OrderStatus.PROCESSING);
        return SIP_STATUS_LABEL.get(groupedStatus);
    }
}
