package com.platizio.wealthtech.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface CybrillaClient {
    String createInvestorProfile(Investor investor);
    String createMfInvestmentAccount(Investor investor);
    void captureBankAccount(Investor investor, InvestorBankAccount bankAccount);
    JsonNode fetchBankAccountVerification(String bankAccountVerificationId);
    JsonNode createKycCheck(String pan, LocalDate dateOfBirth);
    JsonNode fetchKycCheck(String kycCheckId);
    JsonNode refetchKycCheck(String kycCheckId);
    JsonNode listKycRequests(String pan, String status);
    JsonNode createKycRequest(Map<String, Object> payload);
    JsonNode fetchKycRequest(String kycRequestId);
    JsonNode updateKycRequest(String kycRequestId, Map<String, Object> payload);
    JsonNode simulateKycRequest(String kycRequestId, String status);
    JsonNode createIdentityDocument(Map<String, Object> payload);
    JsonNode fetchIdentityDocument(String identityDocumentId);
    JsonNode listIdentityDocuments(String kycRequestId, String fetchStatus);
    List<ProductScheme> fetchProductSchemes();
    String createOrder(TransactionOrder order, Investor investor, ProductScheme productScheme);
    String generateInvestorActionUrl(TransactionOrder order);
    String createRedemption(TransactionOrder order, Investor investor, ProductScheme productScheme);
    void cancelOrder(TransactionOrder order);
}
