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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class DashboardService {

    private final InvestorRepository investorRepository;
    private final TransactionOrderRepository orderRepository;
    private final ProductSchemeRepository schemeRepository;

    public DashboardService(InvestorRepository investorRepository, TransactionOrderRepository orderRepository, ProductSchemeRepository schemeRepository) {
        this.investorRepository = investorRepository;
        this.orderRepository = orderRepository;
        this.schemeRepository = schemeRepository;
    }

    public SipDashboardDto getSipDashboard(UUID distributorId) {
        List<TransactionOrder> orders = orderRepository.findByDistributorId(distributorId);
        List<TransactionOrder> sipOrders = orders.stream()
                .filter(o -> o.getTransactionType() == TransactionType.SIP)
                .collect(Collectors.toList());

        Map<UUID, Investor> investorMap = investorRepository.findByDistributorId(distributorId).stream()
                .collect(Collectors.toMap(Investor::getId, i -> i));
        Map<UUID, ProductScheme> schemeMap = schemeRepository.findAll().stream()
                .collect(Collectors.toMap(ProductScheme::getId, s -> s));

        List<SipItemDto> sips = sipOrders.stream().map(o -> {
            Investor inv = investorMap.get(o.getInvestorId());
            ProductScheme scheme = schemeMap.get(o.getProductSchemeId());
            String status = o.getOrderStatus() == OrderStatus.COMPLETED ? "Active" : 
                            (o.getOrderStatus() == OrderStatus.FAILED ? "Failed" : "Paused");
            
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

        return new SipDashboardDto(trend, sips);
    }

    public List<ActionItemDto> getActionCenter(UUID distributorId) {
        List<ActionItemDto> actions = new ArrayList<>();
        List<Investor> investors = investorRepository.findByDistributorId(distributorId);
        List<TransactionOrder> orders = orderRepository.findByDistributorId(distributorId);

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
                Investor inv = investors.stream().filter(i -> i.getId().equals(o.getInvestorId())).findFirst().orElse(null);
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
}
