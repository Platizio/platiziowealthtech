package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorRelationshipType;
import com.platizio.wealthtech.dto.HouseholdMemberResponse;
import com.platizio.wealthtech.dto.HouseholdReportResponse;
import com.platizio.wealthtech.dto.HouseholdSummaryResponse;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HouseholdReportService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final InvestorRepository investorRepository;
    private final TransactionOrderRepository orderRepository;

    public HouseholdReportService(
            InvestorRepository investorRepository,
            TransactionOrderRepository orderRepository
    ) {
        this.investorRepository = investorRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public HouseholdReportResponse generate(UUID distributorId) {
        List<Investor> investors = investorRepository.findByDistributorId(distributorId);
        Map<UUID, TransactionOrderRepository.InvestorOrderAmountSummary> orderSummaries =
                orderRepository.summarizeAmountsByDistributor(distributorId).stream()
                        .collect(Collectors.toMap(
                                TransactionOrderRepository.InvestorOrderAmountSummary::getInvestorId,
                                summary -> summary
                        ));

        List<HouseholdSummaryResponse> households = investors.stream()
                .collect(Collectors.groupingBy(this::householdIdFor))
                .entrySet()
                .stream()
                .map(entry -> toHousehold(entry.getKey(), entry.getValue(), orderSummaries))
                .sorted(Comparator.comparing(HouseholdSummaryResponse::primaryInvestorName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        BigDecimal totalOrderAmount = households.stream()
                .map(HouseholdSummaryResponse::totalOrderAmount)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal completedOrderAmount = households.stream()
                .map(HouseholdSummaryResponse::completedOrderAmount)
                .reduce(ZERO, BigDecimal::add);

        return new HouseholdReportResponse(
                distributorId,
                households.size(),
                investors.size(),
                totalOrderAmount,
                completedOrderAmount,
                households
        );
    }

    private HouseholdSummaryResponse toHousehold(
            UUID householdId,
            List<Investor> investors,
            Map<UUID, TransactionOrderRepository.InvestorOrderAmountSummary> orderSummaries
    ) {
        List<Investor> sortedInvestors = investors.stream()
                .sorted(Comparator
                        .comparing((Investor investor) -> investor.getRelationshipType() == InvestorRelationshipType.SELF ? 0 : 1)
                        .thenComparing(Investor::getFullName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<HouseholdMemberResponse> members = sortedInvestors.stream()
                .map(investor -> toMember(investor, orderSummaries.get(investor.getId())))
                .toList();

        BigDecimal totalOrderAmount = members.stream()
                .map(HouseholdMemberResponse::totalOrderAmount)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal completedOrderAmount = members.stream()
                .map(HouseholdMemberResponse::completedOrderAmount)
                .reduce(ZERO, BigDecimal::add);
        Investor primary = sortedInvestors.get(0);
        String householdName = sortedInvestors.stream()
                .map(Investor::getHouseholdName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(primary.getFullName() + " Household");

        return new HouseholdSummaryResponse(
                householdId,
                householdName,
                primary.getFullName(),
                primary.getPan(),
                members.size(),
                members.stream().filter(member -> member.relationshipType() == InvestorRelationshipType.MINOR).count(),
                members.stream().filter(member -> member.relationshipType() == InvestorRelationshipType.HUF).count(),
                totalOrderAmount,
                completedOrderAmount,
                members
        );
    }

    private HouseholdMemberResponse toMember(
            Investor investor,
            TransactionOrderRepository.InvestorOrderAmountSummary orderSummary
    ) {
        return new HouseholdMemberResponse(
                investor.getId(),
                investor.getFullName(),
                investor.getPan(),
                investor.getRelationshipType(),
                investor.getGuardianInvestorId(),
                investor.getGuardianPan(),
                investor.getInvestorStatus(),
                investor.getKycStatus(),
                investor.getBankVerificationStatus(),
                orderSummary == null ? ZERO : nullToZero(orderSummary.getTotalAmount()),
                orderSummary == null ? ZERO : nullToZero(orderSummary.getCompletedAmount()),
                orderSummary == null ? 0 : orderSummary.getOrderCount()
        );
    }

    private UUID householdIdFor(Investor investor) {
        return investor.getHouseholdId() == null ? investor.getId() : investor.getHouseholdId();
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? ZERO : value;
    }
}
