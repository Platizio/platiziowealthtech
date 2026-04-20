package com.platizio.wealthtech.integration;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import java.util.List;

public interface CybrillaClient {
    String createInvestorProfile(Investor investor);
    void captureBankAccount(Investor investor, InvestorBankAccount bankAccount);
    List<ProductScheme> fetchProductSchemes();
    String createOrder(TransactionOrder order, Investor investor);
    String generateInvestorActionUrl(TransactionOrder order);
    String createRedemption(TransactionOrder order);
}