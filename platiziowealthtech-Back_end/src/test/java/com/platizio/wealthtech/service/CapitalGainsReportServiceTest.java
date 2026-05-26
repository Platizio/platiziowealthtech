package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.CapitalGainType;
import com.platizio.wealthtech.dto.CapitalGainsExportFormat;
import com.platizio.wealthtech.dto.CapitalGainsReportResponse;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CapitalGainsReportServiceTest {

    @Test
    void generatesFinancialYearCapitalGainsWithStcgLtcgAndGrandfathering() {
        UUID distributorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        ProductScheme equityScheme = scheme(ProductCategory.EQUITY, "Equity Growth Fund", "INF-EQ-1", "{\"grandfatheredNav\":15}");
        ProductScheme debtScheme = scheme(ProductCategory.OTHER, "Debt Fund", "INF-DEBT-1", "{}");
        Investor investor = investor(investorId);

        TransactionOrder oldPurchase = order(
                distributorId,
                investorId,
                equityScheme.getId(),
                TransactionType.LUMPSUM_PURCHASE,
                "2017-01-01T10:00:00Z",
                "1000.00",
                "100.0000"
        );
        TransactionOrder longTermRedemption = order(
                distributorId,
                investorId,
                equityScheme.getId(),
                TransactionType.REDEMPTION,
                "2024-04-10T10:00:00Z",
                "2000.00",
                "100.0000"
        );
        TransactionOrder recentPurchase = order(
                distributorId,
                investorId,
                debtScheme.getId(),
                TransactionType.LUMPSUM_PURCHASE,
                "2024-04-01T10:00:00Z",
                "1000.00",
                "100.0000"
        );
        TransactionOrder shortTermRedemption = order(
                distributorId,
                investorId,
                debtScheme.getId(),
                TransactionType.REDEMPTION,
                "2024-10-01T10:00:00Z",
                "1200.00",
                "100.0000"
        );

        CapitalGainsReportService service = service(
                distributorId,
                List.of(oldPurchase, longTermRedemption, recentPurchase, shortTermRedemption),
                List.of(equityScheme, debtScheme),
                List.of(investor)
        );

        CapitalGainsReportResponse report = service.generate(distributorId, "2024-2025");

        assertThat(report.financialYear()).isEqualTo("2024-2025");
        assertThat(report.lineItems()).hasSize(2);
        assertThat(report.longTermCapitalGain()).isEqualByComparingTo("500.00");
        assertThat(report.shortTermCapitalGain()).isEqualByComparingTo("200.00");

        assertThat(report.lineItems())
                .filteredOn(line -> line.gainType() == CapitalGainType.LTCG)
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.purchaseCost()).isEqualByComparingTo("1000.00");
                    assertThat(line.fairMarketValueAsOf20180131()).isEqualByComparingTo("1500.00");
                    assertThat(line.grandfatheredCost()).isEqualByComparingTo("1500.00");
                    assertThat(line.capitalGain()).isEqualByComparingTo("500.00");
                });
    }

    @Test
    void exportsClearTaxCsvWithSeparateStcgAndLtcgColumns() {
        UUID distributorId = UUID.randomUUID();
        UUID investorId = UUID.randomUUID();
        ProductScheme scheme = scheme(ProductCategory.EQUITY, "Equity Growth Fund", "INF-EQ-1", "{\"grandfatheredNav\":15}");
        Investor investor = investor(investorId);
        TransactionOrder purchase = order(
                distributorId,
                investorId,
                scheme.getId(),
                TransactionType.LUMPSUM_PURCHASE,
                "2017-01-01T10:00:00Z",
                "1000.00",
                "100.0000"
        );
        TransactionOrder redemption = order(
                distributorId,
                investorId,
                scheme.getId(),
                TransactionType.REDEMPTION,
                "2024-04-10T10:00:00Z",
                "2000.00",
                "100.0000"
        );

        CapitalGainsReportService service = service(distributorId, List.of(purchase, redemption), List.of(scheme), List.of(investor));

        String csv = service.exportCsv(distributorId, "FY2024-25", CapitalGainsExportFormat.CLEARTAX);

        assertThat(csv).startsWith("Asset Type,ISIN,Security Name,Date of Purchase,Date of Sale");
        assertThat(csv).contains("EQUITY,INF-EQ-1,Equity Growth Fund,2017-01-01,2024-04-10,2000.00,1000.00,1500.00,1500.00,0.00,0.00,500.00,ABCDE1234F");
    }

    private CapitalGainsReportService service(
            UUID distributorId,
            List<TransactionOrder> orders,
            List<ProductScheme> schemes,
            List<Investor> investors
    ) {
        TransactionOrderRepository orderRepository = mock(TransactionOrderRepository.class);
        ProductSchemeRepository schemeRepository = mock(ProductSchemeRepository.class);
        InvestorRepository investorRepository = mock(InvestorRepository.class);

        when(orderRepository.findByDistributorIdAndOrderStatusInAndCreatedAtBefore(
                eq(distributorId),
                anyCollection(),
                any(OffsetDateTime.class)
        )).thenReturn(orders);
        when(schemeRepository.findAllById(any())).thenReturn(schemes);
        when(investorRepository.findAllById(any())).thenReturn(investors);

        return new CapitalGainsReportService(orderRepository, schemeRepository, investorRepository, new ObjectMapper());
    }

    private Investor investor(UUID id) {
        Investor investor = new Investor();
        ReflectionTestUtils.setField(investor, "id", id);
        investor.setFullName("Ada Investor");
        investor.setPan("ABCDE1234F");
        return investor;
    }

    private ProductScheme scheme(ProductCategory category, String name, String isin, String metadataJson) {
        ProductScheme scheme = new ProductScheme();
        ReflectionTestUtils.setField(scheme, "id", UUID.randomUUID());
        scheme.setCategory(category);
        scheme.setSchemeName(name);
        scheme.setExternalIsin(isin);
        scheme.setExternalSchemeCode(isin);
        scheme.setAmcName("Test AMC");
        scheme.setMetadataJson(metadataJson);
        return scheme;
    }

    private TransactionOrder order(
            UUID distributorId,
            UUID investorId,
            UUID schemeId,
            TransactionType transactionType,
            String createdAt,
            String amount,
            String units
    ) {
        TransactionOrder order = new TransactionOrder();
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(order, "createdAt", OffsetDateTime.parse(createdAt));
        order.setDistributorId(distributorId);
        order.setInvestorId(investorId);
        order.setProductSchemeId(schemeId);
        order.setTransactionType(transactionType);
        order.setOrderStatus(OrderStatus.COMPLETED);
        order.setAmount(new BigDecimal(amount));
        order.setUnits(new BigDecimal(units));
        return order;
    }
}
