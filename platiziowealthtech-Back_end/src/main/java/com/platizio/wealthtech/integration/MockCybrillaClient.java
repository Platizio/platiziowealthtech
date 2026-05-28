package com.platizio.wealthtech.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "cybrilla.integration", name = "real-client-enabled", havingValue = "false")
public class MockCybrillaClient implements CybrillaClient {

    private static final Logger logger = LoggerFactory.getLogger(MockCybrillaClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public MockCybrillaClient() {
        logger.warn("cybrilla_client mode='mock' external_calls='disabled' reason='CYBRILLA_REAL_CLIENT_ENABLED=false'");
    }

    @Override
    public String createInvestorProfile(Investor investor) {
        String mockId = "cyb-inv-" + UUID.randomUUID();
        logger.warn("cybrilla_client mode='mock' operation='create_investor_profile' local_investor_id='{}' mock_id='{}'", investor.getId(), mockId);
        return mockId;
    }

    @Override
    public String createMfInvestmentAccount(Investor investor) {
        String mockId = "cyb-mfia-" + UUID.randomUUID();
        logger.warn("cybrilla_client mode='mock' operation='create_mf_investment_account' local_investor_id='{}' mock_id='{}'", investor.getId(), mockId);
        return mockId;
    }

    @Override
    public void captureBankAccount(Investor investor, InvestorBankAccount bankAccount) {
        String mockId = "cyb-bank-" + UUID.randomUUID();
        String mockVerificationId = "cyb-bav-" + UUID.randomUUID();
        logger.warn("cybrilla_client mode='mock' operation='capture_bank_account' local_investor_id='{}' local_bank_id='{}' mock_id='{}'", investor.getId(), bankAccount.getId(), mockId);
        bankAccount.setCybrillaBankId(mockId);
        bankAccount.setCybrillaBankVerificationId(mockVerificationId);
        bankAccount.setCybrillaBankVerificationStatus("pending");
    }

    @Override
    public JsonNode fetchBankAccountVerification(String bankAccountVerificationId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "bank_account_verification");
        response.put("id", bankAccountVerificationId);
        response.put("status", "completed");
        response.put("confidence", "very_high");
        return response;
    }

    @Override
    public JsonNode createPreVerification(Map<String, Object> payload) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "pre_verification");
        response.put("id", "pv_" + UUID.randomUUID().toString().replace("-", ""));
        response.put("status", "completed");
        response.put("investor_identifier", textValue(payload, "investor_identifier"));
        response.putObject("readiness").put("status", "verified").putNull("code").putNull("reason");
        response.putObject("pan").put("status", "verified").putNull("code").putNull("reason").put("value", nestedTextValue(payload, "pan"));
        response.putObject("name").put("status", "verified").putNull("code").putNull("reason").put("value", nestedTextValue(payload, "name"));
        response.putObject("date_of_birth").put("status", "verified").putNull("code").putNull("reason").put("value", nestedTextValue(payload, "date_of_birth"));
        return response;
    }

    @Override
    public JsonNode createKycCheck(Investor investor) {
        return createPreVerification(Map.of(
                "investor_identifier", investor.getPan(),
                "pan", Map.of("value", investor.getPan()),
                "name", Map.of("value", investor.getFullName()),
                "date_of_birth", Map.of("value", investor.getDateOfBirth() == null ? "" : investor.getDateOfBirth().toString())
        ));
    }

    @Override
    public JsonNode fetchKycCheck(String kycCheckId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "pre_verification");
        response.put("id", kycCheckId);
        response.put("status", "completed");
        response.putObject("readiness").put("status", "verified").putNull("code").putNull("reason");
        return response;
    }

    @Override
    public JsonNode refetchKycCheck(String kycCheckId) {
        return fetchKycCheck(kycCheckId);
    }

    private String textValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private String nestedTextValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Map<?, ?> map) {
            Object nested = map.get("value");
            return nested == null ? "" : String.valueOf(nested);
        }
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public JsonNode listKycRequests(String pan, String status) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "list");
        response.putArray("data");
        return response;
    }

    @Override
    public JsonNode createKycRequest(Map<String, Object> payload) {
        ObjectNode response = OBJECT_MAPPER.valueToTree(payload);
        response.put("object", "kyc_request");
        response.put("id", "kycr_" + UUID.randomUUID().toString().replace("-", ""));
        response.put("status", "pending");
        return response;
    }

    @Override
    public JsonNode fetchKycRequest(String kycRequestId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "kyc_request");
        response.put("id", kycRequestId);
        response.put("status", "pending");
        return response;
    }

    @Override
    public JsonNode updateKycRequest(String kycRequestId, Map<String, Object> payload) {
        ObjectNode response = OBJECT_MAPPER.valueToTree(payload);
        response.put("object", "kyc_request");
        response.put("id", kycRequestId);
        response.put("status", "pending");
        return response;
    }

    @Override
    public JsonNode simulateKycRequest(String kycRequestId, String status) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "kyc_request");
        response.put("id", kycRequestId);
        response.put("status", status);
        return response;
    }

    @Override
    public JsonNode createIdentityDocument(Map<String, Object> payload) {
        ObjectNode response = OBJECT_MAPPER.valueToTree(payload);
        response.put("object", "identity_document");
        response.put("id", "iddoc_" + UUID.randomUUID().toString().replace("-", ""));
        ObjectNode fetch = response.putObject("fetch");
        fetch.put("redirect_url", "https://example.com/digilocker");
        fetch.put("status", "pending");
        return response;
    }

    @Override
    public JsonNode fetchIdentityDocument(String identityDocumentId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "identity_document");
        response.put("id", identityDocumentId);
        response.putObject("fetch").put("status", "pending");
        return response;
    }

    @Override
    public JsonNode listIdentityDocuments(String kycRequestId, String fetchStatus) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "list");
        response.putArray("data");
        return response;
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
    public String createOrder(TransactionOrder order, Investor investor, ProductScheme productScheme) {
        return "cyb-order-" + UUID.randomUUID();
    }

    @Override
    public String generateInvestorActionUrl(TransactionOrder order) {
        String token = StringUtils.hasText(order.getInvestorActionToken())
                ? order.getInvestorActionToken()
                : String.valueOf(order.getId());
        return "/investor-actions/" + token;
    }

    @Override
    public String createRedemption(TransactionOrder order, Investor investor, ProductScheme productScheme) {
        return "cyb-red-" + UUID.randomUUID();
    }

    @Override
    public void cancelOrder(TransactionOrder order) {
        logger.warn("cybrilla_client mode='mock' operation='cancel_order' local_order_id='{}' external_order_id='{}'", order.getId(), order.getExternalOrderId());
    }
}
