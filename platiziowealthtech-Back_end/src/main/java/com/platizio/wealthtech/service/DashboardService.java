package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.LifeEventReminder;
import com.platizio.wealthtech.domain.LifeEventType;
import com.platizio.wealthtech.dto.*;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.LifeEventReminderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    private static final int ACTION_CENTER_BUCKET_LIMIT = 50;
    private static final int ONBOARDING_COLUMN_LIMIT = 50;

    private static final Map<OrderStatus, String> SIP_STATUS_LABEL = Map.of(
            OrderStatus.ACTIVE, "Active SIPs",
            OrderStatus.PROCESSING, "Active SIPs",
            OrderStatus.PAUSED, "Paused SIPs",
            OrderStatus.COMPLETED, "Completed SIPs",
            OrderStatus.FAILED, "Failed SIPs",
            OrderStatus.CANCELLED, "Cancelled SIPs"
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
            Map.entry(OrderStatus.DRAFT, OrderStatus.PAUSED),
            Map.entry(OrderStatus.CANCELLED, OrderStatus.CANCELLED)
    );
    /** Established SIPs — mf_purchase_plan exists (Cybrilla: only after mandate + plan confirm). */
    private static final Set<OrderStatus> ESTABLISHED_SIP_STATUSES = Set.of(
            OrderStatus.ACTIVE,
            OrderStatus.SUCCESSFUL,
            OrderStatus.COMPLETED,
            OrderStatus.PAUSED,
            OrderStatus.FAILED,
            OrderStatus.CANCELLED
    );
    /** In-flight mandate / payment setup — not yet a live SIP. */
    private static final Set<OrderStatus> PENDING_SETUP_STATUSES = Set.of(
            OrderStatus.PENDING_INVESTOR_ACTION,
            OrderStatus.PAYMENT_PENDING,
            OrderStatus.CREATED,
            OrderStatus.SUBMITTED,
            OrderStatus.PROCESSING,
            OrderStatus.RETRY_AVAILABLE,
            OrderStatus.DRAFT
    );
    /** Row-level labels for the SIP dashboard table (must match React `SipDashboard` filters). */
    private static final Map<OrderStatus, String> SIP_ITEM_STATUS = Map.ofEntries(
            Map.entry(OrderStatus.ACTIVE, "Active"),
            Map.entry(OrderStatus.SUCCESSFUL, "Active"),
            Map.entry(OrderStatus.PROCESSING, "Pending"),
            Map.entry(OrderStatus.CREATED, "Pending"),
            Map.entry(OrderStatus.PENDING_INVESTOR_ACTION, "Pending"),
            Map.entry(OrderStatus.PAYMENT_PENDING, "Pending"),
            Map.entry(OrderStatus.SUBMITTED, "Pending"),
            Map.entry(OrderStatus.RETRY_AVAILABLE, "Pending"),
            Map.entry(OrderStatus.PAUSED, "Paused"),
            Map.entry(OrderStatus.DRAFT, "Pending"),
            Map.entry(OrderStatus.COMPLETED, "Paused"),
            Map.entry(OrderStatus.FAILED, "Failed"),
            Map.entry(OrderStatus.CANCELLED, "Cancelled")
    );
    private static final List<String> SIP_STATUS_LABEL_ORDER = List.of(
            "Active SIPs",
            "Paused SIPs",
            "Completed SIPs",
            "Failed SIPs",
            "Cancelled SIPs"
    );

    private final InvestorRepository investorRepository;
    private final TransactionOrderRepository orderRepository;
    private final ProductSchemeRepository schemeRepository;
    private final LifeEventReminderRepository lifeEventReminderRepository;

    @Autowired
    public DashboardService(
            InvestorRepository investorRepository,
            TransactionOrderRepository orderRepository,
            ProductSchemeRepository schemeRepository,
            LifeEventReminderRepository lifeEventReminderRepository
    ) {
        this.investorRepository = investorRepository;
        this.orderRepository = orderRepository;
        this.schemeRepository = schemeRepository;
        this.lifeEventReminderRepository = lifeEventReminderRepository;
    }

    public DashboardService(InvestorRepository investorRepository, TransactionOrderRepository orderRepository, ProductSchemeRepository schemeRepository) {
        this.investorRepository = investorRepository;
        this.orderRepository = orderRepository;
        this.schemeRepository = schemeRepository;
        this.lifeEventReminderRepository = null;
    }

    public SipDashboardDto getSipDashboard(UUID distributorId) {
        // B-71: previously this used PageRequest.of(0, 50), which silently
        // truncated the SIP list at 50 rows — a distributor with 51+ SIPs
        // never saw orders 51..N in the dashboard. The new unbounded
        // overload matches the sibling trend query in this same method
        // (findByDistributorIdAndTransactionTypeAndCreatedAtAfter is also
        // unpaginated) and is safe for realistic distributor SIP volumes
        // (low hundreds at most). If true server-side pagination is wanted
        // for scale, that's a separate API + UI feature.
        List<TransactionOrder> sipOrders = orderRepository
                .findByDistributorIdAndTransactionType(distributorId, TransactionType.SIP);

        Set<UUID> sipInvestorIds = sipOrders.stream()
                .map(TransactionOrder::getInvestorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Investor> investorMap = sipInvestorIds.isEmpty()
                ? Map.of()
                : investorRepository.findAllById(sipInvestorIds).stream()
                        .collect(Collectors.toMap(Investor::getId, i -> i));

        // Collect only the scheme IDs actually referenced by this distributor's SIP orders,
        // then fetch just those rows — avoids a full table scan of all 482+ schemes.
        Set<UUID> neededSchemeIds = sipOrders.stream()
                .map(TransactionOrder::getProductSchemeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, ProductScheme> schemeMap = schemeRepository.findAllById(neededSchemeIds).stream()
                .collect(Collectors.toMap(ProductScheme::getId, s -> s));

        List<SipItemDto> sips = sipOrders.stream()
                .filter(o -> ESTABLISHED_SIP_STATUSES.contains(o.getOrderStatus()))
                .map(o -> toSipItem(o, investorMap, schemeMap))
                .collect(Collectors.toList());
        List<SipItemDto> pendingSetup = sipOrders.stream()
                .filter(o -> PENDING_SETUP_STATUSES.contains(o.getOrderStatus()))
                .map(o -> toSipItem(o, investorMap, schemeMap))
                .collect(Collectors.toList());
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
                .filter(o -> o.getOrderStatus() == OrderStatus.ACTIVE)
                .filter(o -> o.getAmount() != null)
                .collect(Collectors.groupingBy(
                        o -> YearMonth.from(o.getCreatedAt().toLocalDate()),
                        Collectors.reducing(BigDecimal.ZERO,
                                TransactionOrder::getAmount,
                                BigDecimal::add)));

        // Group by YearMonth → count active SIPs only
        Map<YearMonth, Long> countByMonth = recentSipOrders.stream()
                .filter(o -> o.getOrderStatus() == OrderStatus.ACTIVE)
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

        return new SipDashboardDto(trend, sips, pendingSetup, statusCounts);
    }

    private SipItemDto toSipItem(
            TransactionOrder order,
            Map<UUID, Investor> investorMap,
            Map<UUID, ProductScheme> schemeMap
    ) {
        Investor inv = investorMap.get(order.getInvestorId());
        ProductScheme scheme = schemeMap.get(order.getProductSchemeId());
        String status = sipItemStatus(order.getOrderStatus());

        String rawCat = "OTHER";
        if (order.getProductCategory() != null) {
            rawCat = order.getProductCategory().toString();
        } else if (scheme != null && scheme.getCategory() != null) {
            rawCat = scheme.getCategory().toString();
        }
        rawCat = rawCat.toUpperCase();
        String category = (rawCat.contains("MF") || rawCat.contains("MUTUAL")) ? "MF" : "SIF";

        return new SipItemDto(
                order.getId().toString(),
                inv != null ? inv.getFullName() : "Unknown",
                ProductSchemeOrderSupport.displayName(scheme),
                "₹" + (order.getAmount() != null ? order.getAmount().toString() : "0"),
                status,
                "N/A",
                formatMandate(order),
                order.getMandateStatus(),
                order.getExternalMandateId(),
                order.getExternalOrderId(),
                category
        );
    }

    private static String formatMandate(TransactionOrder order) {
        if (order.getExternalMandateId() == null || order.getExternalMandateId() <= 0) {
            return order.getMandateMode() != null ? order.getMandateMode() : "—";
        }
        String mode = order.getMandateMode() != null ? order.getMandateMode() : "MANDATE";
        String mandateStatus = order.getMandateStatus() != null ? order.getMandateStatus() : "CREATED";
        return mode + " #" + order.getExternalMandateId() + " · " + mandateStatus;
    }

    public List<ActionItemDto> getActionCenter(UUID distributorId) {
        List<ActionItemDto> actions = new ArrayList<>();
        PageRequest actionPage = dashboardPage(ACTION_CENTER_BUCKET_LIMIT);
        List<Investor> kycPendingInvestors = investorRepository.findByDistributorIdAndKycStatusNot(
                distributorId,
                KycStatus.COMPLETED,
                actionPage);
        List<Investor> bankPendingInvestors = investorRepository.findByDistributorIdAndBankVerificationStatusNot(
                distributorId,
                BankVerificationStatus.VERIFIED,
                actionPage);
        List<TransactionOrder> failedOrders = orderRepository.findByDistributorIdAndOrderStatus(
                distributorId,
                OrderStatus.FAILED,
                actionPage);

        // Load only investors referenced by the bounded failed-order page.
        Set<UUID> failedOrderInvestorIds = failedOrders.stream()
                .map(TransactionOrder::getInvestorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Investor> investorById = failedOrderInvestorIds.isEmpty()
                ? Map.of()
                : investorRepository.findAllById(failedOrderInvestorIds).stream()
                        .collect(Collectors.toMap(Investor::getId, i -> i));

        int idCounter = 1;

        for (Investor inv : kycPendingInvestors) {
            actions.add(new ActionItemDto(String.valueOf(idCounter++), "KYC", "High", inv.getFullName(), "KYC verification is pending.", "Today"));
        }

        for (Investor inv : bankPendingInvestors) {
            actions.add(new ActionItemDto(String.valueOf(idCounter++), "Bank", "Medium", inv.getFullName(), "Bank account not verified.", "1 day"));
        }

        for (TransactionOrder o : failedOrders) {
            if (o.getOrderStatus() == OrderStatus.FAILED) {
                Investor inv = investorById.get(o.getInvestorId());
                String name = inv != null ? inv.getFullName() : "Unknown";
                String cat = o.getTransactionType() == TransactionType.SIP ? "SIP" : "Transaction";
                actions.add(new ActionItemDto(String.valueOf(idCounter++), cat, "High", name, "Transaction failed: " + (o.getFailureReason() != null ? o.getFailureReason() : "Unknown reason"), "Today"));
            }
        }

        if (lifeEventReminderRepository != null) {
            List<LifeEventReminder> lifeEventReminders = lifeEventReminderRepository
                    .findByDistributorIdAndStatusOrderByEventDateAsc(
                            distributorId,
                            com.platizio.wealthtech.domain.LifeEventReminderStatus.OPEN,
                            actionPage);
            Set<UUID> reminderInvestorIds = lifeEventReminders.stream()
                    .map(LifeEventReminder::getInvestorId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<UUID, Investor> reminderInvestorById = reminderInvestorIds.isEmpty()
                    ? Map.of()
                    : investorRepository.findAllById(reminderInvestorIds).stream()
                            .collect(Collectors.toMap(Investor::getId, i -> i));
            for (LifeEventReminder reminder : lifeEventReminders) {
                Investor inv = reminderInvestorById.get(reminder.getInvestorId());
                String name = inv != null ? inv.getFullName() : "Unknown";
                actions.add(new ActionItemDto(
                        String.valueOf(idCounter++),
                        lifeEventCategory(reminder.getEventType()),
                        lifeEventPriority(reminder.getEventDate()),
                        name,
                        reminder.getMessage(),
                        dueIn(reminder.getEventDate())));
            }
        }

        return actions;
    }

    public OnboardingPipelineDto getOnboardingPipeline(UUID distributorId) {
        PageRequest columnPage = dashboardPage(ONBOARDING_COLUMN_LIMIT);

        List<OnboardingCardDto> kycPending = investorRepository
                .findByDistributorIdAndKycStatusNot(distributorId, KycStatus.COMPLETED, columnPage)
                .stream()
                .map(inv -> onboardingCard(inv, "KYC Pending", "kyc"))
                .collect(Collectors.toList());
        List<OnboardingCardDto> bankPending = investorRepository
                .findByDistributorIdAndKycStatusAndBankVerificationStatusNot(
                        distributorId,
                        KycStatus.COMPLETED,
                        BankVerificationStatus.VERIFIED,
                        columnPage)
                .stream()
                .map(inv -> onboardingCard(inv, "Bank Pending", "bank"))
                .collect(Collectors.toList());
        List<OnboardingCardDto> ready = investorRepository
                .findByDistributorIdAndKycStatusAndBankVerificationStatus(
                        distributorId,
                        KycStatus.COMPLETED,
                        BankVerificationStatus.VERIFIED,
                        columnPage)
                .stream()
                .map(inv -> onboardingCard(inv, "Ready to Invest", "ready"))
                .collect(Collectors.toList());

        return new OnboardingPipelineDto(kycPending, bankPending, ready);
    }

    private PageRequest dashboardPage(int size) {
        return PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private OnboardingCardDto onboardingCard(Investor investor, String detail, String status) {
        long daysAgo = ChronoUnit.DAYS.between(investor.getCreatedAt().toLocalDate(), LocalDate.now());
        String daysStr = daysAgo == 0 ? "Today" : daysAgo + " days ago";
        return new OnboardingCardDto(
                investor.getId().toString(),
                investor.getFullName(),
                detail,
                daysStr,
                status);
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

    private String sipItemStatus(OrderStatus orderStatus) {
        return SIP_ITEM_STATUS.getOrDefault(orderStatus, "Active");
    }

    private String lifeEventCategory(LifeEventType eventType) {
        return eventType == LifeEventType.GOAL_MATURITY ? "Maturing" : "Life Event";
    }

    private String lifeEventPriority(LocalDate eventDate) {
        long daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), eventDate);
        return daysUntil <= 1 ? "High" : "Medium";
    }

    private String dueIn(LocalDate eventDate) {
        long daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), eventDate);
        if (daysUntil <= 0) {
            return "Today";
        }
        if (daysUntil == 1) {
            return "1 day";
        }
        return daysUntil + " days";
    }
}
