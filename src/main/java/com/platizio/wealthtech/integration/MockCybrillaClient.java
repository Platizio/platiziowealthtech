package com.platizio.wealthtech.integration;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
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
        ProductScheme scheme = new ProductScheme();
        scheme.setSchemeName("Sample Flexi Cap Fund");
        scheme.setAmcName("Sample AMC");
        scheme.setCategory("Equity");
        scheme.setExternalSchemeCode("SCH-" + UUID.randomUUID());
        scheme.setExternalIsin("INF0000XXXX");
        scheme.setProductType("MUTUAL_FUND");
        scheme.setMetadataJson("{\"source\":\"mock\"}");
        return List.of(scheme);
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