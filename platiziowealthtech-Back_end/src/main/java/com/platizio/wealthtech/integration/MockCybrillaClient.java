package com.platizio.wealthtech.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import java.util.LinkedHashMap;
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
        String mockId = "invp_" + UUID.randomUUID().toString().replace("-", "");
        logger.warn("cybrilla_client mode='mock' operation='create_investor_profile' local_investor_id='{}' mock_id='{}'", investor.getId(), mockId);
        return mockId;
    }

    @Override
    public void ensureInvestorProfileOrderReady(Investor investor) {
        logger.warn(
                "cybrilla_client mode='mock' operation='ensure_investor_profile_order_ready' local_investor_id='{}'",
                investor.getId()
        );
    }

    @Override
    public void updateInvestorProfile(Investor investor) {
        logger.warn(
                "cybrilla_client mode='mock' operation='update_investor_profile' local_investor_id='{}' external_profile_id='{}'",
                investor.getId(),
                investor.getCybrillaInvestorId()
        );
    }

    @Override
    public JsonNode listInvestorProfiles(String pan, String type) {
        logger.warn("cybrilla_client mode='mock' operation='list_investor_profiles' pan='{}' type='{}'", pan, type);
        return OBJECT_MAPPER.createObjectNode()
                .put("object", "list")
                .set("data", OBJECT_MAPPER.createArrayNode());
    }

    @Override
    public JsonNode fetchInvestorProfile(String profileId) {
        logger.warn("cybrilla_client mode='mock' operation='fetch_investor_profile' profile_id='{}'", profileId);
        return OBJECT_MAPPER.createObjectNode()
                .put("object", "investor_profile")
                .put("id", profileId);
    }

    @Override
    public void syncInvestorContactResources(Investor investor) {
        logger.warn(
                "cybrilla_client mode='mock' operation='sync_investor_contact_resources' local_investor_id='{}'",
                investor.getId()
        );
    }

    @Override
    public JsonNode listMfInvestmentAccounts(String investorProfileId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "list");
        ObjectNode account = OBJECT_MAPPER.createObjectNode();
        account.put("object", "mf_investment_account");
        account.put("id", "mfia_" + UUID.randomUUID().toString().replace("-", ""));
        account.put("primary_investor", investorProfileId);
        response.putArray("data").add(account);
        return response;
    }

    @Override
    public void ensureMfInvestmentAccountOrderReady(Investor investor, InvestorBankAccount bankAccount) {
        logger.warn(
                "cybrilla_client mode='mock' operation='ensure_mf_investment_account_order_ready' local_investor_id='{}'",
                investor.getId()
        );
    }

    @Override
    public String createMfInvestmentAccount(Investor investor) {
        String mockId = "mfia_" + UUID.randomUUID().toString().replace("-", "");
        logger.warn("cybrilla_client mode='mock' operation='create_mf_investment_account' local_investor_id='{}' mock_id='{}'", investor.getId(), mockId);
        return mockId;
    }

    @Override
    public void captureBankAccount(Investor investor, InvestorBankAccount bankAccount) {
        ensureFpBankAccountCaptured(investor, bankAccount);
        startBankAccountVerification(investor, bankAccount);
    }

    @Override
    public void ensureFpBankAccountCaptured(Investor investor, InvestorBankAccount bankAccount) {
        if (StringUtils.hasText(bankAccount.getCybrillaBankId())) {
            return;
        }
        String mockId = "cyb-bank-" + UUID.randomUUID();
        logger.warn("cybrilla_client mode='mock' operation='capture_bank_account' local_investor_id='{}' local_bank_id='{}' mock_id='{}'", investor.getId(), bankAccount.getId(), mockId);
        bankAccount.setCybrillaBankId(mockId);
    }

    @Override
    public void startBankAccountVerification(Investor investor, InvestorBankAccount bankAccount) {
        String mockVerificationId = "pv_" + UUID.randomUUID().toString().replace("-", "");
        logger.warn("cybrilla_client mode='mock' operation='start_bank_account_verification' local_investor_id='{}' local_bank_id='{}' mock_id='{}'", investor.getId(), bankAccount.getId(), mockVerificationId);
        bankAccount.setCybrillaBankVerificationId(mockVerificationId);
        bankAccount.setCybrillaBankVerificationStatus("accepted");
        bankAccount.setExternalSyncPending(false);
        bankAccount.setExternalSyncMessage(null);
    }

    @Override
    public JsonNode fetchBankAccountVerification(String bankAccountVerificationId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "pre_verification");
        response.put("id", bankAccountVerificationId);
        response.put("status", "completed");
        ObjectNode bank = OBJECT_MAPPER.createObjectNode();
        bank.put("status", "verified");
        bank.putNull("code");
        bank.putNull("reason");
        response.putArray("bank_accounts").add(bank);
        return response;
    }

    @Override
    public JsonNode fetchBankAccountVerificationWithPayloadSnapshot(String bankAccountVerificationId, Map<String, Object> payloadSnapshot) {
        if (payloadSnapshot != null) {
            payloadSnapshot.put("operation", "fetch_bank_account_verification");
            payloadSnapshot.put("bank_account_verification_id", bankAccountVerificationId);
            payloadSnapshot.put("mode", "mock");
        }
        return fetchBankAccountVerification(bankAccountVerificationId);
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
                "pan", Map.of("value", investor.getPan()),
                "name", Map.of("value", investor.getFullName()),
                "date_of_birth", Map.of("value", investor.getDateOfBirth() == null ? "" : investor.getDateOfBirth().toString())
        ));
    }

    @Override
    public JsonNode createCombinedOrderPreVerification(Investor investor, InvestorBankAccount bankAccount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("investor_identifier", investor.getPan());
        payload.put("pan", Map.of("value", investor.getPan()));
        payload.put("name", Map.of("value", investor.getFullName()));
        if (investor.getDateOfBirth() != null) {
            payload.put("date_of_birth", Map.of("value", investor.getDateOfBirth().toString()));
        }
        if (bankAccount != null) {
            payload.put("bank_accounts", List.of(Map.of(
                    "value", Map.of(
                            "account_number", bankAccount.getAccountNumber(),
                            "ifsc_code", bankAccount.getIfscCode(),
                            "account_type", "savings"
                    )
            )));
        }
        return createPreVerification(payload);
    }

    @Override
    public JsonNode createReadinessCheck(Investor investor) {
        return createPreVerification(Map.of("investor_identifier", investor.getPan()));
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

    @Override
    public JsonNode createKycComplianceCheck(String pan, java.time.LocalDate dateOfBirth) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("id", "kyc_" + UUID.randomUUID().toString().replace("-", ""));
        response.putNull("source_ref_id");
        response.put("pan", pan == null ? "" : pan.toUpperCase());

        String normalized = pan == null ? "" : pan.toUpperCase();
        // Mirror the FP sandbox PAN simulator (XXXPX375NX) so local flows can exercise each state.
        if (normalized.contains("3753")) {
            response.put("status", false);
            response.put("reason", "unavailable");
            response.put("action", "create");
            response.putArray("constraints");
        } else if (normalized.contains("3754")) {
            response.put("status", false);
            response.put("reason", "onhold");
            response.put("action", "modify");
            response.putArray("constraints");
        } else if (normalized.contains("3759")) {
            response.put("status", false);
            response.put("reason", "incomplete");
            response.put("action", "modify");
            response.putArray("constraints");
        } else if (normalized.contains("3752")) {
            response.put("status", true);
            response.putNull("reason");
            response.putNull("action");
            ObjectNode constraint = response.putArray("constraints").addObject();
            constraint.put("type", "investment_limit");
            constraint.putObject("amount").put("value", 50000).put("currency", "inr");
        } else {
            response.put("status", true);
            response.putNull("reason");
            response.putNull("action");
            response.putArray("constraints");
        }

        ObjectNode entityDetails = response.putObject("entity_details");
        entityDetails.put("name", "Mock Investor");
        if (dateOfBirth != null) {
            entityDetails.put("date_of_birth", dateOfBirth.toString());
        }
        return response;
    }

    @Override
    public JsonNode fetchKycComplianceCheck(String kycComplianceCheckId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("id", kycComplianceCheckId);
        response.put("status", true);
        response.putNull("reason");
        response.putNull("action");
        response.putArray("constraints");
        return response;
    }

    @Override
    public JsonNode refetchKycComplianceCheck(String kycComplianceCheckId) {
        return fetchKycComplianceCheck(kycComplianceCheckId);
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
        ObjectNode fetch = response.putObject("fetch");
        fetch.put("status", "successful");
        fetch.putNull("reason");
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
    public JsonNode createEsign(Map<String, Object> payload) {
        String esignId = "esign_" + UUID.randomUUID().toString().replace("-", "");
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "esign");
        response.put("id", esignId);
        response.put("kyc_request", textValue(payload, "kyc_request"));
        response.put("postback_url", textValue(payload, "postback_url"));
        response.put("redirect_url", "https://s.finprim.com/v2/esigns/" + esignId + "/redirect");
        response.put("status", "pending");
        return response;
    }

    @Override
    public JsonNode fetchEsign(String esignId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "esign");
        response.put("id", esignId);
        response.put("status", "successful");
        response.put("redirect_url", "https://s.finprim.com/v2/esigns/" + esignId + "/redirect");
        return response;
    }

    @Override
    public JsonNode createKycForm(Map<String, Object> payload) {
        String mockId = "kycf_" + UUID.randomUUID().toString().replace("-", "");
        logger.warn("cybrilla_client mode='mock' operation='create_kyc_form' mock_id='{}'", mockId);
        ObjectNode response = kycFormBase(mockId, "created");
        response.put("pan", textValue(payload, "pan"));
        response.put("name", textValue(payload, "name"));
        response.put("date_of_birth", textValue(payload, "date_of_birth"));
        response.put("proof_details_callback_url", textValue(payload, "proof_details_callback_url"));
        response.put("esign_callback_url", textValue(payload, "esign_callback_url"));
        ObjectNode proof = response.putObject("proof_details");
        proof.put("fetch_url", sandboxProofFetchUrl(payload, mockId));
        proof.put("status", "pending");
        response.putObject("esign_details")
                .put("esign_url", sandboxEsignUrl(payload, mockId))
                .putNull("status");
        response.putObject("requirements").putArray("fields_needed")
                .add("identity_proof").add("address").add("signature");
        return response;
    }

    @Override
    public JsonNode updateKycForm(String kycFormId, Map<String, Object> payload) {
        logger.warn("cybrilla_client mode='mock' operation='update_kyc_form' kyc_form_id='{}'", kycFormId);
        ObjectNode response = kycFormBase(kycFormId, "created");
        response.putObject("proof_details")
                .put("fetch_url", "https://s.finprim.com/identity_documents/fetch_my_proof?form=" + kycFormId)
                .put("status", "pending");
        response.putObject("requirements").putArray("fields_needed").add("signature");
        return response;
    }

    @Override
    public JsonNode fetchKycForm(String kycFormId) {
        logger.warn("cybrilla_client mode='mock' operation='fetch_kyc_form' kyc_form_id='{}'", kycFormId);
        ObjectNode response = kycFormBase(kycFormId, "awaiting_esign");
        response.putObject("proof_details")
                .put("fetch_url", "https://s.finprim.com/identity_documents/fetch_my_proof?form=" + kycFormId)
                .put("status", "fetched");
        response.putObject("esign_details")
                .put("esign_url", "https://s.finprim.com/v2/esigns/" + kycFormId + "/redirect")
                .put("status", "pending");
        response.put("signature_provided", true);
        response.putObject("requirements").putNull("fields_needed");
        return response;
    }

    @Override
    public JsonNode uploadKycFormSignature(String kycFormId, byte[] fileBytes, String filename, String contentType) {
        logger.warn("cybrilla_client mode='mock' operation='upload_kyc_form_signature' kyc_form_id='{}' file='{}'", kycFormId, filename);
        ObjectNode response = kycFormBase(kycFormId, "created");
        response.put("signature_provided", true);
        return response;
    }

    @Override
    public JsonNode retryKycFormProofDetailsFetch(String kycFormId) {
        logger.warn("cybrilla_client mode='mock' operation='retry_kyc_form_proof_details_fetch' kyc_form_id='{}'", kycFormId);
        ObjectNode response = kycFormBase(kycFormId, "created");
        response.putObject("proof_details")
                .put("fetch_url", "https://s.finprim.com/identity_documents/fetch_my_proof?form=" + kycFormId + "&retry=1")
                .put("status", "pending");
        return response;
    }

    private String sandboxProofFetchUrl(Map<String, Object> payload, String kycFormId) {
        return "https://s.finprim.com/identity_documents/fetch_my_proof?form=" + kycFormId;
    }

    private String sandboxEsignUrl(Map<String, Object> payload, String kycFormId) {
        return "https://s.finprim.com/v2/esigns/" + kycFormId + "/redirect";
    }

    private ObjectNode kycFormBase(String id, String status) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "kyc_form");
        response.put("id", id);
        response.put("type", "modify");
        response.put("status", status);
        response.putNull("reason");
        return response;
    }

    @Override
    public SchemeFetchResult fetchProductSchemes() {
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

        return SchemeFetchResult.complete(List.of(equityScheme, mfScheme, sifScheme));
    }

    @Override
    public JsonNode getFundSchemesPageWithPayloadSnapshot(int page, int size, Map<String, Object> payloadSnapshot) {
        if (payloadSnapshot != null) {
            payloadSnapshot.put("operation", "fetch_fund_schemes_page");
            payloadSnapshot.put("path", "/api/oms/fund_schemes");
            payloadSnapshot.put("page", page);
            payloadSnapshot.put("size", size);
            payloadSnapshot.put("mode", "mock");
        }
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("page", page);
        response.put("total_pages", 1);
        response.put("last", true);
        response.putArray("fund_schemes");
        return response;
    }

    @Override
    public LiveCataloguePage fetchLiveCataloguePage(String endpoint, int page, int size) {
        SchemeFetchResult catalogue = fetchProductSchemes();
        List<ProductScheme> schemes = catalogue.schemes();
        int from = Math.min(page * size, schemes.size());
        int to = Math.min(from + size, schemes.size());
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("page", page);
        response.put("size", size);
        response.put("totalElements", schemes.size());
        response.put("source", "mock");
        response.put("endpoint", endpoint);
        return new LiveCataloguePage(
                response,
                schemes.subList(from, to),
                schemes.size(),
                page,
                size,
                "mock"
        );
    }

    @Override
    public String createOrder(TransactionOrder order, Investor investor, ProductScheme productScheme) {
        return "cyb-order-" + UUID.randomUUID();
    }

    @Override
    public String generateInvestorActionUrl(TransactionOrder order) {
        if (StringUtils.hasText(order.getInvestorActionUrl()) && order.getInvestorActionUrl().startsWith("http")) {
            return order.getInvestorActionUrl();
        }
        String token = StringUtils.hasText(order.getInvestorActionToken())
                ? order.getInvestorActionToken()
                : String.valueOf(order.getId());
        return "/investor-actions/" + token;
    }

    @Override
    public IfscLookupResult fetchIfscDetails(String ifscCode) {
        String normalized = ifscCode == null ? "" : ifscCode.trim().toUpperCase();
        return new IfscLookupResult(
                normalized,
                "Mock Bank",
                "Mock Branch",
                "Mock Branch Address",
                "Mock City",
                "Mock District",
                "Mock State",
                "000000000"
        );
    }

    @Override
    public PincodeLookupResult fetchPincodeDetails(String pincode) {
        String normalized = pincode == null ? "" : pincode.trim().replaceAll("\\D", "");
        if ("400001".equals(normalized) || "400002".equals(normalized)) {
            return new PincodeLookupResult(
                    normalized,
                    "Mumbai",
                    "Mumbai",
                    "Maharashtra",
                    "IN",
                    java.util.List.of("Mumbai")
            );
        }
        return new PincodeLookupResult(
                normalized,
                "Mock City",
                "Mock District",
                "Mock State",
                "IN",
                java.util.List.of("Mock City")
        );
    }

    @Override
    public JsonNode fetchMfPurchase(String mfPurchaseId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "mf_purchase");
        response.put("id", mfPurchaseId);
        response.put("old_id", 1001);
        response.put("state", "successful");
        return response;
    }

    @Override
    public JsonNode updateMfPurchaseConsent(String mfPurchaseId, Map<String, Object> consent) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "mf_purchase");
        response.put("id", mfPurchaseId);
        response.put("state", "pending");
        response.set("consent", OBJECT_MAPPER.valueToTree(consent));
        return response;
    }

    @Override
    public JsonNode createNetbankingPayment(List<Integer> amcOrderIds, String paymentPostbackUrl, String paymentMethod) {
        return createNetbankingPayment(amcOrderIds, paymentPostbackUrl, paymentMethod, null, null);
    }

    @Override
    public JsonNode createNetbankingPayment(
            List<Integer> amcOrderIds,
            String paymentPostbackUrl,
            String paymentMethod,
            Integer bankAccountOldId,
            String providerName
    ) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("id", 2001);
        response.put("token_url", "sandbox://platizio/simulate-payment");
        return response;
    }

    @Override
    public JsonNode createUpiUriPayment(
            List<Integer> amcOrderIds,
            String paymentPostbackUrl,
            Integer bankAccountOldId,
            String providerName
    ) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("id", 2001);
        response.putNull("token_url");
        ObjectNode upi = response.putObject("upi");
        upi.put("type", "uri");
        upi.putNull("vpa");
        upi.put("uri", "upi://pay?pa=platizio@upi&pn=Platizio&am=5000.00&cu=INR");
        return response;
    }

    @Override
    public JsonNode fetchPayment(int paymentId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("id", paymentId);
        response.put("token_url", "sandbox://platizio/simulate-payment");
        ObjectNode upi = response.putObject("upi");
        upi.put("type", "uri");
        upi.putNull("vpa");
        upi.put("uri", "upi://pay?pa=platizio@upi&pn=Platizio&am=5000.00&cu=INR");
        response.put("status", "PENDING");
        return response;
    }

    @Override
    public JsonNode simulatePayment(int paymentId, String status) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("message", "payment updated to status " + status);
        return response;
    }

    @Override
    public JsonNode confirmMfPurchase(String mfPurchaseId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "mf_purchase");
        response.put("id", mfPurchaseId);
        response.put("state", "submitted");
        response.put("old_id", 9123);
        return response;
    }

    @Override
    public JsonNode fetchBankAccount(String bankAccountId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "bank_account");
        response.put("id", bankAccountId);
        response.put("old_id", 501);
        return response;
    }

    @Override
    public JsonNode createMandate(int bankAccountOldId, String mandateType, int mandateLimit, String providerName) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("id", 3001);
        response.put("mandate_status", "CREATED");
        return response;
    }

    @Override
    public JsonNode authorizeMandate(int mandateId, String paymentPostbackUrl) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("id", 4001);
        response.put("token_url", "https://payments.mock/mandate-auth");
        return response;
    }

    @Override
    public JsonNode fetchMandate(int mandateId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("id", mandateId);
        response.put("mandate_status", "APPROVED");
        return response;
    }

    @Override
    public JsonNode simulateMandate(int mandateId, String status) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("message", "mandate updated to status " + status);
        return response;
    }

    @Override
    public JsonNode fetchMfPurchasePlan(String mfPurchasePlanId) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "mf_purchase_plan");
        response.put("id", mfPurchasePlanId);
        response.put("state", "review_completed");
        return response;
    }

    @Override
    public JsonNode updateMfPurchasePlan(String mfPurchasePlanId, Map<String, Object> payload) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "mf_purchase_plan");
        response.put("id", mfPurchasePlanId);
        response.put("state", "active");
        return response;
    }

    @Override
    public JsonNode listMfPurchasesForPlan(String mfPurchasePlanId) {
        ObjectNode purchase = OBJECT_MAPPER.createObjectNode();
        purchase.put("object", "mf_purchase");
        purchase.put("id", "mfp_mock_installment");
        purchase.put("old_id", 1001);
        purchase.put("state", "pending");
        purchase.put("plan", mfPurchasePlanId);
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "list");
        response.putArray("data").add(purchase);
        return response;
    }

    @Override
    public JsonNode createNachPayment(int mandateId, List<Integer> amcOrderIds) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("id", 5001);
        response.put("mandate_id", mandateId);
        response.put("status", "SUBMITTED");
        return response;
    }

    @Override
    public String createSipOrderWithMandate(TransactionOrder order, Investor investor, ProductScheme productScheme, int mandateId) {
        return "mfpp_mock_" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public String createRedemption(TransactionOrder order, Investor investor, ProductScheme productScheme) {
        return "cyb-red-" + UUID.randomUUID();
    }

    @Override
    public JsonNode fetchRedemption(String redemptionId) {
        // Demo: a redemption settles to 'successful' so the lifecycle reaches a terminal state.
        return OBJECT_MAPPER.createObjectNode()
                .put("id", redemptionId)
                .put("state", "successful")
                .put("bank_credit_reference", "DEMO-CREDIT-" + redemptionId);
    }

    @Override
    public JsonNode updateRedemptionConsent(String redemptionId, java.util.Map<String, Object> consent) {
        return OBJECT_MAPPER.createObjectNode().put("id", redemptionId).put("state", "confirmed");
    }

    @Override
    public JsonNode confirmRedemption(String redemptionId) {
        return OBJECT_MAPPER.createObjectNode().put("id", redemptionId).put("state", "submitted");
    }

    @Override
    public void cancelOrder(TransactionOrder order) {
        logger.warn("cybrilla_client mode='mock' operation='cancel_order' local_order_id='{}' external_order_id='{}'", order.getId(), order.getExternalOrderId());
    }

    @Override
    public JsonNode cancelPurchasePlan(String planId, String cancellationCode, String cancellationReason) {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("object", "mf_purchase_plan");
        response.put("id", planId);
        response.put("state", "cancelled");
        response.put("cancellation_code", cancellationCode == null ? "invest_later" : cancellationCode);
        return response;
    }
}
