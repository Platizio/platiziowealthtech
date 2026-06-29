package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
import com.platizio.wealthtech.dto.InvestorDashboardResponse.DataQuality;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.RedemptionRecordRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Derivation logic for {@link HoldingsService}: authoritative net units (never the
 * order amount), units×NAV valuation, weighted cost basis, returns, dataQuality,
 * and that XIRR is wired through. The XIRR math itself is pinned by
 * {@link XirrCalculatorTest}.
 */
@ExtendWith(MockitoExtension.class)
class HoldingsServiceTest {

    @Mock private TransactionOrderRepository orderRepository;
    @Mock private RedemptionRecordRepository redemptionRepository;
    @Mock private ProductSchemeRepository schemeRepository;

    private HoldingsService service;

    private final UUID investorId = UUID.randomUUID();
    private final UUID schemeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new HoldingsService(orderRepository, redemptionRepository, schemeRepository, new ObjectMapper());
    }

    private ProductScheme scheme(String metadataJson) {
        ProductScheme s = new ProductScheme();
        ReflectionTestUtils.setField(s, "id", schemeId);
        s.setSchemeName("Acme Bluechip Fund");
        s.setAmcName("Acme AMC");
        s.setCategory(ProductCategory.MF);
        s.setMetadataJson(metadataJson);
        return s;
    }

    private TransactionOrder purchase(BigDecimal units, BigDecimal amount, OffsetDateTime createdAt) {
        TransactionOrder o = new TransactionOrder();
        ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(o, "createdAt", createdAt);
        o.setInvestorId(investorId);
        o.setProductSchemeId(schemeId);
        o.setTransactionType(TransactionType.PURCHASE);
        o.setOrderStatus(OrderStatus.SUCCESSFUL);
        o.setProductCategory(ProductCategory.MF);
        o.setUnits(units);
        o.setAmount(amount);
        return o;
    }

    private RedemptionRecord redemption(UUID orderId, BigDecimal units, BigDecimal amount, OffsetDateTime createdAt) {
        RedemptionRecord r = new RedemptionRecord();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(r, "createdAt", createdAt);
        r.setOrderId(orderId);
        r.setInvestorId(investorId);
        r.setRedemptionStatus(RedemptionStatus.SUCCESSFUL);
        r.setUnits(units);
        r.setAmount(amount);
        return r;
    }

    private void stubScheme(ProductScheme s) {
        when(schemeRepository.findAllById(any())).thenReturn(s == null ? List.of() : List.of(s));
    }

    @Test
    void valuesHoldingByUnitsTimesNavWithGainCostBasisAndXirr() {
        // 100 units bought a year ago for 1,000 (avg cost NAV 10); NAV now 20 (+prev 19.5).
        OffsetDateTime aYearAgo = OffsetDateTime.now().minusDays(365);
        TransactionOrder buy = purchase(new BigDecimal("100"), new BigDecimal("1000"), aYearAgo);
        when(orderRepository.findByInvestorId(investorId)).thenReturn(List.of(buy));
        when(redemptionRepository.findByInvestorId(investorId)).thenReturn(List.of());
        stubScheme(scheme("{\"nav\":20.0,\"nav_date\":\"2030-01-01T00:00:00Z\",\"previous_nav\":19.5}"));

        InvestorDashboardResponse res = service.getDashboard(investorId);

        assertThat(res.holdings()).hasSize(1);
        DashboardHolding h = res.holdings().get(0);
        assertThat(h.units()).isEqualByComparingTo("100");          // from units, NOT amount
        assertThat(h.latestNav()).isEqualByComparingTo("20.0000");
        assertThat(h.invested()).isEqualByComparingTo("1000.00");
        assertThat(h.currentValue()).isEqualByComparingTo("2000.00"); // 100 × 20
        assertThat(h.absoluteReturn()).isEqualByComparingTo("1000.00");
        assertThat(h.percentReturn()).isEqualByComparingTo("100.00");
        assertThat(h.dataQuality()).isEqualTo(DataQuality.OK);
        // 1-day return: (20 − 19.5) × 100 = 50
        assertThat(h.oneDayReturn()).isEqualByComparingTo("50.00");
        // XIRR ≈ +100% (−1000 a year ago, +2000 today)
        assertThat(h.xirr()).isNotNull();
        assertThat(h.xirr()).isCloseTo(1.0, within(0.05));

        assertThat(res.totals().totalInvested()).isEqualByComparingTo("1000.00");
        assertThat(res.totals().totalCurrentValue()).isEqualByComparingTo("2000.00");
        assertThat(res.totals().portfolioXirr()).isCloseTo(1.0, within(0.05));
    }

    @Test
    void netUnitsAreSettledPurchasesMinusRedemptions() {
        OffsetDateTime t0 = OffsetDateTime.now().minusDays(200);
        TransactionOrder buy = purchase(new BigDecimal("100"), new BigDecimal("1000"), t0);
        UUID buyId = (UUID) ReflectionTestUtils.getField(buy, "id");
        RedemptionRecord sell = redemption(buyId, new BigDecimal("40"), new BigDecimal("900"),
                OffsetDateTime.now().minusDays(10));
        when(orderRepository.findByInvestorId(investorId)).thenReturn(List.of(buy));
        when(redemptionRepository.findByInvestorId(investorId)).thenReturn(List.of(sell));
        stubScheme(scheme("{\"nav\":15.0,\"nav_date\":\"2030-01-01T00:00:00Z\"}"));

        InvestorDashboardResponse res = service.getDashboard(investorId);

        assertThat(res.holdings()).hasSize(1);
        assertThat(res.holdings().get(0).units()).isEqualByComparingTo("60"); // 100 − 40
        assertThat(res.holdings().get(0).currentValue()).isEqualByComparingTo("900.00"); // 60 × 15
    }

    @Test
    void unavailableNavYieldsNullValueAndUnavailableQuality() {
        TransactionOrder buy = purchase(new BigDecimal("100"), new BigDecimal("1000"),
                OffsetDateTime.now().minusDays(30));
        when(orderRepository.findByInvestorId(investorId)).thenReturn(List.of(buy));
        when(redemptionRepository.findByInvestorId(investorId)).thenReturn(List.of());
        stubScheme(scheme("{\"amc\":\"Acme\"}")); // no nav

        InvestorDashboardResponse res = service.getDashboard(investorId);

        // NAV unavailable → fall back to average-cost basis so the investor still sees a value
        // (labelled STALE / "at cost"), per the dashboard-valuation product change.
        DashboardHolding h = res.holdings().get(0);
        assertThat(h.currentValue()).isEqualByComparingTo("1000");   // 100 units × ₹10 avg cost
        assertThat(h.absoluteReturn()).isEqualByComparingTo("0");    // valued at cost → no gain
        assertThat(h.dataQuality()).isEqualTo(DataQuality.STALE);
        assertThat(res.totals().totalCurrentValue()).isEqualByComparingTo("1000");
    }

    @Test
    void navWithoutAsOfIsStaleButStillValued() {
        TransactionOrder buy = purchase(new BigDecimal("50"), new BigDecimal("500"),
                OffsetDateTime.now().minusDays(30));
        when(orderRepository.findByInvestorId(investorId)).thenReturn(List.of(buy));
        when(redemptionRepository.findByInvestorId(investorId)).thenReturn(List.of());
        stubScheme(scheme("{\"nav\":12.0}")); // nav present, no as-of date

        DashboardHolding h = service.getDashboard(investorId).holdings().get(0);
        assertThat(h.currentValue()).isEqualByComparingTo("600.00"); // 50 × 12
        assertThat(h.dataQuality()).isEqualTo(DataQuality.STALE);
        assertThat(h.oneDayReturn()).isNull(); // no previous-day NAV → not zeroed
    }

    @Test
    void fullyRedeemedHoldingIsExcluded() {
        OffsetDateTime t0 = OffsetDateTime.now().minusDays(100);
        TransactionOrder buy = purchase(new BigDecimal("100"), new BigDecimal("1000"), t0);
        UUID buyId = (UUID) ReflectionTestUtils.getField(buy, "id");
        RedemptionRecord sellAll = redemption(buyId, new BigDecimal("100"), new BigDecimal("1500"),
                OffsetDateTime.now().minusDays(5));
        when(orderRepository.findByInvestorId(investorId)).thenReturn(List.of(buy));
        when(redemptionRepository.findByInvestorId(investorId)).thenReturn(List.of(sellAll));
        stubScheme(scheme("{\"nav\":15.0,\"nav_date\":\"2030-01-01T00:00:00Z\"}"));

        InvestorDashboardResponse res = service.getDashboard(investorId);
        assertThat(res.holdings()).isEmpty();
    }

    @Test
    void onlyPendingOrdersContributeNoHoldings() {
        TransactionOrder draft = purchase(new BigDecimal("100"), new BigDecimal("1000"),
                OffsetDateTime.now().minusDays(5));
        draft.setOrderStatus(OrderStatus.PENDING_INVESTOR_ACTION); // not a settled holding
        when(orderRepository.findByInvestorId(investorId)).thenReturn(List.of(draft));
        when(redemptionRepository.findByInvestorId(investorId)).thenReturn(List.of());

        InvestorDashboardResponse res = service.getDashboard(investorId);
        assertThat(res.holdings()).isEmpty();
        assertThat(res.totals().totalInvested()).isEqualByComparingTo("0.00");
    }

    @Test
    void nullInvestorReturnsEmptyDashboard() {
        InvestorDashboardResponse res = service.getDashboard(null);
        assertThat(res.holdings()).isEmpty();
        assertThat(res.totals().totalCurrentValue()).isNull();
    }
}
