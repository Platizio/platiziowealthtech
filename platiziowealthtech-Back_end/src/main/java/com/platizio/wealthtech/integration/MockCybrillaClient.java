package com.platizio.wealthtech.integration;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class MockCybrillaClient implements CybrillaClient {

    @Override
    public String createInvestorProfile(Investor investor) {
        return "cyb-inv-" + UUID.randomUUID();
    }

    @Override
    public void captureBankAccount(Investor investor, InvestorBankAccount bankAccount) {
        bankAccount.setCybrillaBankId("cyb-bank-" + UUID.randomUUID());
    }

    @Override
    public List<ProductScheme> fetchProductSchemes() {
        ProductScheme equityScheme = new ProductScheme();
        equityScheme.setSchemeName("Bluechip Equity Fund");
        equityScheme.setAmcName("Platizio Assets");
        equityScheme.setCategory(ProductCategory.OTHER);
        equityScheme.setExternalSchemeCode("EQ-101");
        equityScheme.setExternalIsin("INF001");
        equityScheme.setProductType("MUTUAL_FUND");
        equityScheme.setMetadataJson("{\"risk\":\"high\",\"returns\":{\"daily\":0.8,\"ytd\":12.4,\"1y\":18.5,\"5y\":85.2}}");

        ProductScheme mfScheme = new ProductScheme();
        mfScheme.setSchemeName("Balanced Mutual Fund");
        mfScheme.setAmcName("Platizio Assets");
        mfScheme.setCategory(ProductCategory.MF);
        mfScheme.setExternalSchemeCode("MF-201");
        mfScheme.setExternalIsin("INF002");
        mfScheme.setProductType("MUTUAL_FUND");
        mfScheme.setMetadataJson("{\"risk\":\"medium\",\"returns\":{\"daily\":0.2,\"ytd\":8.1,\"1y\":12.4,\"5y\":55.8}}");

        ProductScheme sifScheme = new ProductScheme();
        sifScheme.setSchemeName("Social Impact Fund");
        sifScheme.setAmcName("Impact Capital");
        sifScheme.setCategory(ProductCategory.SIF);
        sifScheme.setExternalSchemeCode("SIF-301");
        sifScheme.setExternalIsin("INF003");
        sifScheme.setProductType("SIF");
        sifScheme.setMetadataJson("{\"impact\":\"high\",\"returns\":{\"daily\":-0.1,\"ytd\":5.4,\"1y\":8.2,\"5y\":32.1}}");

        return List.of(equityScheme, mfScheme, sifScheme);
    }

    @Override
    public String createOrder(TransactionOrder order, Investor investor) {
        return "cyb-order-" + UUID.randomUUID();
    }

    @Override
    public String generateInvestorActionUrl(TransactionOrder order) {
        return "https://example.com/investor-action/" + order.getId();
    }

    @Override
    public String createRedemption(TransactionOrder order) {
        return "cyb-red-" + UUID.randomUUID();
    }
}