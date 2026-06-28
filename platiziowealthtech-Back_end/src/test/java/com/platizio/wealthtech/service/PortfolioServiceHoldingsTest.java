package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.HoldingResponse;
import com.platizio.wealthtech.repository.InvestorBankAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-Mockito tests for {@link PortfolioService#getInvestorHoldings(UUID)} (Phase-2):
 * the investor-scoped holdings shape, the masked payout bank, and the dataQuality
 * contract — STALE when NAV has no as-of, UNAVAILABLE when units/NAV are missing,
 * and NEVER a fall back to the order amount or zero (locked decision #4).
 */
class PortfolioServiceHoldingsTest {

    private final TransactionOrderRepository orderRepository = mock(TransactionOrderRepository.class);
    private final ProductSchemeRepository schemeRepository = mock(ProductSchemeRepository.class);
    private final InvestorBankAccountRepository bankRepository = mock(InvestorBankAccountRepository.class);

    private final PortfolioService service = new PortfolioService(
            orderRepository, mock(InvestorRepository.class), schemeRepository, bankRepository,
            new ObjectMapper());

    private final UUID investorId = UUID.randomUUID();

    @Test
    void okValuationWhenNavUnitsAndAsOfPresent() {
        UUID schemeId = UUID.randomUUID();
        TransactionOrder order = order(schemeId, new BigDecimal("100"), OrderStatus.SUCCESSFUL);
        order.setAmount(new BigDecimal("99999")); // must NOT be used as a fallback value
        stub(order, scheme(schemeId, "{\"nav\":25.5,\"nav_date\":\"2026-06-20T00:00:00Z\"}"));
        stubBank();

        HoldingResponse holding = single();

        assertThat(holding.dataQuality()).isEqualTo(HoldingResponse.DataQuality.OK);
        assertThat(holding.latestNav()).isEqualByComparingTo("25.5");
        assertThat(holding.navAsOf()).isNotNull();
        assertThat(holding.currentValue()).isEqualByComparingTo(new BigDecimal("2550.0"));
        assertThat(holding.availableUnits()).isEqualByComparingTo("100");
        assertThat(holding.maskedPayoutBank()).contains("1234");
        assertThat(holding.redeemable()).isTrue();
        // Never the order amount.
        assertThat(holding.currentValue()).isNotEqualByComparingTo("99999");
    }

    @Test
    void staleWhenNavHasNoAsOf() {
        UUID schemeId = UUID.randomUUID();
        TransactionOrder order = order(schemeId, new BigDecimal("10"), OrderStatus.SUCCESSFUL);
        stub(order, scheme(schemeId, "{\"nav\":12}"));
        stubBank();

        HoldingResponse holding = single();

        assertThat(holding.dataQuality()).isEqualTo(HoldingResponse.DataQuality.STALE);
        assertThat(holding.currentValue()).isEqualByComparingTo("120");
    }

    @Test
    void unavailableWhenUnitsMissing_neverFallsBackToAmount() {
        UUID schemeId = UUID.randomUUID();
        TransactionOrder order = order(schemeId, null, OrderStatus.SUCCESSFUL);
        order.setAmount(new BigDecimal("50000"));
        stub(order, scheme(schemeId, "{\"nav\":25.5,\"nav_date\":\"2026-06-20T00:00:00Z\"}"));
        stubBank();

        HoldingResponse holding = single();

        assertThat(holding.dataQuality()).isEqualTo(HoldingResponse.DataQuality.UNAVAILABLE);
        assertThat(holding.currentValue()).isNull();
    }

    @Test
    void unavailableWhenNavMissing_neverFallsBackToAmountOrZero() {
        UUID schemeId = UUID.randomUUID();
        TransactionOrder order = order(schemeId, new BigDecimal("10"), OrderStatus.SUCCESSFUL);
        order.setAmount(new BigDecimal("7000"));
        stub(order, scheme(schemeId, "{\"returns\":{\"1y\":12.3}}")); // no nav
        stubBank();

        HoldingResponse holding = single();

        assertThat(holding.dataQuality()).isEqualTo(HoldingResponse.DataQuality.UNAVAILABLE);
        assertThat(holding.currentValue()).isNull();
        assertThat(holding.latestNav()).isNull();
    }

    @Test
    void excludesNonSettledAndPendingSipHoldings() {
        UUID schemeId = UUID.randomUUID();
        TransactionOrder pendingSip = order(schemeId, new BigDecimal("10"), OrderStatus.PENDING_INVESTOR_ACTION);
        pendingSip.setTransactionType(TransactionType.SIP);
        TransactionOrder failed = order(schemeId, new BigDecimal("10"), OrderStatus.FAILED);
        when(orderRepository.findByInvestorId(investorId)).thenReturn(List.of(pendingSip, failed));
        when(schemeRepository.findAllById(any())).thenReturn(List.of(scheme(schemeId, "{\"nav\":10}")));
        stubBank();

        List<HoldingResponse> holdings = service.getInvestorHoldings(investorId);

        assertThat(holdings).isEmpty();
    }

    @Test
    void emptyForNullInvestor() {
        assertThat(service.getInvestorHoldings(null)).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private HoldingResponse single() {
        List<HoldingResponse> holdings = service.getInvestorHoldings(investorId);
        assertThat(holdings).hasSize(1);
        return holdings.get(0);
    }

    private void stub(TransactionOrder order, ProductScheme scheme) {
        when(orderRepository.findByInvestorId(investorId)).thenReturn(List.of(order));
        when(schemeRepository.findAllById(any())).thenReturn(List.of(scheme));
    }

    private void stubBank() {
        InvestorBankAccount bank = new InvestorBankAccount();
        bank.setInvestorId(investorId);
        bank.setAccountNumber("9876541234");
        bank.setBankName("HDFC Bank");
        bank.setVerificationStatus(BankVerificationStatus.VERIFIED);
        when(bankRepository.findByInvestorId(investorId)).thenReturn(List.of(bank));
    }

    private TransactionOrder order(UUID schemeId, BigDecimal units, OrderStatus status) {
        TransactionOrder order = new TransactionOrder();
        setId(order, UUID.randomUUID());
        order.setInvestorId(investorId);
        order.setDistributorId(UUID.randomUUID());
        order.setProductSchemeId(schemeId);
        order.setTransactionType(TransactionType.LUMPSUM_PURCHASE);
        order.setProductCategory(ProductCategory.MF);
        order.setOrderStatus(status);
        order.setUnits(units);
        order.setExternalOrderId("mfp_123");
        return order;
    }

    private ProductScheme scheme(UUID schemeId, String metadataJson) {
        ProductScheme scheme = new ProductScheme();
        setId(scheme, schemeId);
        scheme.setSchemeName("Focused Equity Fund");
        scheme.setAmcName("Platizio AMC");
        scheme.setCategory(ProductCategory.MF);
        scheme.setExternalSchemeCode("CODE-" + schemeId);
        scheme.setMetadataJson(metadataJson);
        return scheme;
    }

    private static void setId(Object entity, UUID id) {
        try {
            java.lang.reflect.Field field =
                    com.platizio.wealthtech.common.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
