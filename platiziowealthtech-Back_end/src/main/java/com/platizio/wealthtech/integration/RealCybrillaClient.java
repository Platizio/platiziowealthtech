package com.platizio.wealthtech.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.integration.auth.CybrillaPreVerificationProperties;
import com.platizio.wealthtech.integration.auth.ExternalApiAuthenticationException;
import com.platizio.wealthtech.integration.auth.ExternalBearerTokenService;
import com.platizio.wealthtech.integration.auth.FinprimTenantProperties;
import com.platizio.wealthtech.service.ExternalApiSnapshotService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(prefix = "cybrilla.integration", name = "real-client-enabled", havingValue = "true", matchIfMissing = true)
public class RealCybrillaClient implements CybrillaClient {

    private static final Logger logger = LoggerFactory.getLogger(RealCybrillaClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TENANT_HEADER = "x-tenant-id";
    private static final int FUND_SCHEME_PAGE_SIZE = 100;
    private static final int FUND_SCHEME_MAX_PAGES = 50;
    private static final int FUND_SCHEME_RATE_LIMIT_RETRIES = 3;
    private static final long FUND_SCHEME_RATE_LIMIT_BACKOFF_MILLIS = 5_000L;
    // Transient connectivity (DNS/connect/timeout) retry — small, fast backoff.
    private static final int CONNECTIVITY_RETRY_MAX_ATTEMPTS = 3;
    /** One Finprim bearer per catalogue batch — avoids dozens of cache lookups per paginated import. */
    private static final ThreadLocal<String> SCOPED_TENANT_BEARER = new ThreadLocal<>();
    /** After 404, tenant has no SIF catalogue — do not call the SIF endpoint again this JVM lifetime. */
    private volatile boolean sifCatalogueUnavailable;
    private static final long CONNECTIVITY_RETRY_BACKOFF_MILLIS = 500L;
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String MF_PURCHASES_PATH = "/v2/mf_purchases";
    private static final String MF_PURCHASE_PLANS_PATH = "/v2/mf_purchase_plans";
    private static final String MF_REDEMPTIONS_PATH = "/v2/mf_redemptions";
    /** Gateway id for POA-tradeable scheme plan catalogues (see repo exports under exports/). */
    private static final String POA_ORDER_GATEWAY = "cybrillapoa";

    private final RestClient restClient;
    private final RestClient poaRestClient;
    private final ExternalBearerTokenService tokenService;
    private final FinprimTenantProperties finprimProperties;
    private final MeterRegistry meterRegistry;
    private final ExternalApiSnapshotService externalApiSnapshotService;

    public RealCybrillaClient(
            RestClient.Builder restClientBuilder,
            ExternalBearerTokenService tokenService,
            FinprimTenantProperties finprimProperties,
            CybrillaPreVerificationProperties poaProperties,
            MeterRegistry meterRegistry,
            ExternalApiSnapshotService externalApiSnapshotService
    ) {
        this.restClient = restClientBuilder
                .baseUrl(finprimProperties.getBaseUrl())
                .build();
        this.poaRestClient = restClientBuilder
                .baseUrl(poaProperties.getBaseUrl())
                .build();
        this.tokenService = tokenService;
        this.finprimProperties = finprimProperties;
        this.meterRegistry = meterRegistry;
        this.externalApiSnapshotService = externalApiSnapshotService;
        logger.info("cybrilla_client mode='real' base_url='{}' tenant_header_configured='{}'", finprimProperties.getBaseUrl(), StringUtils.hasText(finprimProperties.tenantHeaderValue()));
        logger.info("cybrilla_poa_client mode='real' base_url='{}'", poaProperties.getBaseUrl());
    }

    @Override
    public String createInvestorProfile(Investor investor) {
        logger.info("cybrilla_workflow operation='create_investor_profile' status='started' local_investor_id='{}'", investor.getId());
        String profileId = executeWithTenantTokenRetry("create investor profile", () -> {
            JsonNode response = post("create_investor_profile", "/v2/investor_profiles", investorProfilePayload(investor));
            return extractId(response, "investor profile");
        });
        logger.info("cybrilla_workflow operation='create_investor_profile' status='completed' local_investor_id='{}' external_profile_id='{}'", investor.getId(), profileId);

        createAddressIfPresent(profileId, investor);
        createEmailIfPresent(profileId, investor);
        createPhoneIfPresent(profileId, investor);

        return profileId;
    }

    @Override
    public void updateInvestorProfile(Investor investor) {
        if (!StringUtils.hasText(investor.getCybrillaInvestorId())) {
            throw new CybrillaApiException("Investor must have a Cybrilla/FP investor profile id before updating profile details");
        }
        executeWithTenantTokenRetry("update investor profile", () ->
                patch("update_investor_profile", "/v2/investor_profiles", investorProfileUpdatePayload(investor)));
        logger.info(
                "cybrilla_workflow operation='update_investor_profile' status='completed' local_investor_id='{}' external_profile_id='{}'",
                investor.getId(),
                investor.getCybrillaInvestorId()
        );
    }

    @Override
    public String createMfInvestmentAccount(Investor investor) {
        if (!StringUtils.hasText(investor.getCybrillaInvestorId())) {
            throw new CybrillaApiException("Investor must have a Cybrilla/FP investor profile id before opening an MF investment account");
        }

        String investmentAccountId = executeWithTenantTokenRetry("create MF investment account", () -> {
            JsonNode response = post("create_mf_investment_account", "/v2/mf_investment_accounts", mfInvestmentAccountPayload(investor));
            return extractId(response, "MF investment account");
        });
        logger.info("cybrilla_workflow operation='create_mf_investment_account' status='completed' local_investor_id='{}' external_mf_investment_account_id='{}'", investor.getId(), investmentAccountId);
        return investmentAccountId;
    }

    @Override
    public void captureBankAccount(Investor investor, InvestorBankAccount bankAccount) {
        if (!StringUtils.hasText(investor.getCybrillaInvestorId())) {
            throw new CybrillaApiException("Investor must have a Cybrilla/FP investor profile id before adding a bank account");
        }

        String bankAccountId = executeWithTenantTokenRetry("create bank account", () -> {
            JsonNode response = post("create_bank_account", "/v2/bank_accounts", bankAccountPayload(investor, bankAccount));
            return extractId(response, "bank account");
        });
        logger.info("cybrilla_workflow operation='create_bank_account' status='completed' local_investor_id='{}' local_bank_id='{}' external_bank_id='{}'", investor.getId(), bankAccount.getId(), bankAccountId);
        bankAccount.setCybrillaBankId(bankAccountId);
        startBankAccountVerification(investor, bankAccount);
    }

    @Override
    public void startBankAccountVerification(Investor investor, InvestorBankAccount bankAccount) {
        if (!StringUtils.hasText(bankAccount.getCybrillaBankId())) {
            throw new CybrillaApiException("Bank account must have a Fintech Primitives bank account id before verification");
        }
        try {
            JsonNode verification = executeWithPoaTokenRetry("create bank account pre verification", () ->
                    postPoa("create_bank_account_pre_verification", "/poa/pre_verifications", bankAccountPreVerificationPayload(investor, bankAccount)));
            JsonNode bankVerification = firstBankAccountVerification(verification);
            bankAccount.setCybrillaBankVerificationId(extractId(verification, "bank account verification"));
            bankAccount.setCybrillaBankVerificationStatus(firstText(verification, "status"));
            bankAccount.setCybrillaBankVerificationConfidence(bankVerification == null ? null : firstText(bankVerification, "code"));
            bankAccount.setExternalSyncPending(false);
            bankAccount.setExternalSyncMessage(null);
            logger.info("cybrilla_workflow operation='create_bank_account_verification' status='completed' local_bank_id='{}' external_verification_id='{}'", bankAccount.getId(), bankAccount.getCybrillaBankVerificationId());
        } catch (CybrillaApiException ex) {
            bankAccount.setExternalSyncPending(true);
            bankAccount.setExternalSyncMessage("Bank account was captured in Fintech Primitives, but POA bank pre-verification could not be started.");
            logger.warn("cybrilla_workflow operation='create_bank_account_verification' status='pending' local_bank_id='{}' external_bank_id='{}' reason='{}'", bankAccount.getId(), bankAccount.getCybrillaBankId(), ex.getMessage());
        }
    }

    @Override
    public JsonNode fetchBankAccountVerification(String bankAccountVerificationId) {
        if (!StringUtils.hasText(bankAccountVerificationId)) {
            throw new CybrillaApiException("Bank account verification id is required");
        }
        if (bankAccountVerificationId.trim().toLowerCase().startsWith("pv_")) {
            return executeWithPoaTokenRetry("fetch bank account pre verification", () ->
                    getPoa("fetch_bank_account_pre_verification", "/poa/pre_verifications/" + bankAccountVerificationId.trim()));
        }
        return executeWithTenantTokenRetry("fetch bank account verification", () ->
                get("fetch_bank_account_verification", "/v2/bank_account_verifications/" + bankAccountVerificationId.trim()));
    }

    @Override
    public JsonNode fetchBankAccountVerificationWithPayloadSnapshot(String bankAccountVerificationId, Map<String, Object> payloadSnapshot) {
        if (payloadSnapshot != null) {
            payloadSnapshot.put("operation", "fetch_bank_account_verification");
            payloadSnapshot.put("bank_account_verification_id", bankAccountVerificationId);
        }
        return fetchBankAccountVerification(bankAccountVerificationId);
    }

    @Override
    public JsonNode createPreVerification(Map<String, Object> payload) {
        return executeWithPoaTokenRetry("create POA pre verification", () ->
                postPoa("create_poa_pre_verification", "/poa/pre_verifications", payload));
    }

    @Override
    public JsonNode createKycCheck(Investor investor) {
        return createPreVerification(preVerificationPayload(investor));
    }

    @Override
    public JsonNode fetchKycCheck(String kycCheckId) {
        return executeWithPoaTokenRetry("fetch POA pre verification", () ->
                getPoa("fetch_poa_pre_verification", "/poa/pre_verifications/" + kycCheckId));
    }

    @Override
    public JsonNode refetchKycCheck(String kycCheckId) {
        return fetchKycCheck(kycCheckId);
    }

    @Override
    public JsonNode createKycComplianceCheck(String pan, LocalDate dateOfBirth) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StringUtils.hasText(pan)) {
            payload.put("pan", pan.trim().toUpperCase());
        }
        if (dateOfBirth != null) {
            payload.put("date_of_birth", dateOfBirth.toString());
        }
        return executeWithTenantTokenRetry("create KYC compliance check", () ->
                post("create_kyc_compliance_check", "/api/kyc/check", payload));
    }

    @Override
    public JsonNode fetchKycComplianceCheck(String kycComplianceCheckId) {
        return executeWithTenantTokenRetry("fetch KYC compliance check", () ->
                get("fetch_kyc_compliance_check", "/api/kyc/" + kycComplianceCheckId));
    }

    @Override
    public JsonNode refetchKycComplianceCheck(String kycComplianceCheckId) {
        return executeWithTenantTokenRetry("refetch KYC compliance check", () ->
                put("refetch_kyc_compliance_check", "/api/kyc/" + kycComplianceCheckId + "/refetch"));
    }

    @Override
    public JsonNode createKycForm(Map<String, Object> payload) {
        return executeWithPoaTokenRetry("create KYC form", () ->
                postPoa("create_kyc_form", "/poa/kyc_forms", payload));
    }

    @Override
    public JsonNode updateKycForm(String kycFormId, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.put("id", kycFormId);
        return executeWithPoaTokenRetry("update KYC form", () ->
                patchPoa("update_kyc_form", "/poa/kyc_forms", body));
    }

    @Override
    public JsonNode fetchKycForm(String kycFormId) {
        return executeWithPoaTokenRetry("fetch KYC form", () ->
                getPoa("fetch_kyc_form", "/poa/kyc_forms/" + kycFormId));
    }

    @Override
    public JsonNode uploadKycFormSignature(String kycFormId, byte[] fileBytes, String filename, String contentType) {
        return executeWithPoaTokenRetry("upload KYC form signature", () ->
                postPoaMultipartFile(
                        "upload_kyc_form_signature",
                        "/poa/kyc_forms/" + kycFormId + "/signature",
                        fileBytes,
                        filename,
                        contentType));
    }

    @Override
    public JsonNode retryKycFormProofDetailsFetch(String kycFormId) {
        return executeWithPoaTokenRetry("retry KYC form proof details fetch", () ->
                postPoa("retry_kyc_form_proof_details_fetch", "/poa/kyc_forms/" + kycFormId + "/retry_proof_details_fetch", Map.of()));
    }

    @Override
    public JsonNode listKycRequests(String pan, String status) {
        return executeWithTenantTokenRetry("list KYC requests", () ->
                recordApiRequest("list_kyc_requests", () -> {
                    Map<String, Object> requestPayload = new LinkedHashMap<>();
                    if (StringUtils.hasText(pan)) {
                        requestPayload.put("pan", pan.trim().toUpperCase());
                    }
                    if (StringUtils.hasText(status)) {
                        requestPayload.put("status", status.trim());
                    }
                    JsonNode response = restClient.get()
                            .uri(uriBuilder -> {
                                var builder = uriBuilder.path("/v2/kyc_requests");
                                if (requestPayload.containsKey("pan")) {
                                    builder.queryParam("pan", requestPayload.get("pan"));
                                }
                                if (requestPayload.containsKey("status")) {
                                    builder.queryParam("status", requestPayload.get("status"));
                                }
                                return builder.build();
                            })
                            .headers(this::setTenantAuthHeaders)
                            .retrieve()
                            .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess(
                            "finprim",
                            "list_kyc_requests",
                            "GET",
                            "/v2/kyc_requests",
                            requestPayload.isEmpty() ? null : requestPayload,
                            response
                    );
                    return response;
                }));
    }

    @Override
    public JsonNode createKycRequest(Map<String, Object> payload) {
        return executeWithTenantTokenRetry("create KYC request", () ->
                post("create_kyc_request", "/v2/kyc_requests", payload));
    }

    @Override
    public JsonNode fetchKycRequest(String kycRequestId) {
        return executeWithTenantTokenRetry("fetch KYC request", () ->
                get("fetch_kyc_request", "/v2/kyc_requests/" + kycRequestId));
    }

    @Override
    public JsonNode updateKycRequest(String kycRequestId, Map<String, Object> payload) {
        return executeWithTenantTokenRetry("update KYC request", () ->
                patch("update_kyc_request", "/v2/kyc_requests/" + kycRequestId, payload));
    }

    @Override
    public JsonNode simulateKycRequest(String kycRequestId, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "status", status);
        return executeWithTenantTokenRetry("simulate KYC request", () ->
                post("simulate_kyc_request", "/v2/kyc_requests/" + kycRequestId + "/simulate", payload));
    }

    @Override
    public JsonNode createIdentityDocument(Map<String, Object> payload) {
        return executeWithTenantTokenRetry("create identity document", () ->
                post("create_identity_document", "/v2/identity_documents", payload));
    }

    @Override
    public JsonNode fetchIdentityDocument(String identityDocumentId) {
        return executeWithTenantTokenRetry("fetch identity document", () ->
                get("fetch_identity_document", "/v2/identity_documents/" + identityDocumentId));
    }

    @Override
    public JsonNode listIdentityDocuments(String kycRequestId, String fetchStatus) {
        return executeWithTenantTokenRetry("list identity documents", () ->
                recordApiRequest("list_identity_documents", () -> {
                    Map<String, Object> requestPayload = new LinkedHashMap<>();
                    if (StringUtils.hasText(kycRequestId)) {
                        requestPayload.put("kyc_request", kycRequestId.trim());
                    }
                    if (StringUtils.hasText(fetchStatus)) {
                        requestPayload.put("fetch.status", fetchStatus.trim());
                    }
                    JsonNode response = restClient.get()
                            .uri(uriBuilder -> {
                                var builder = uriBuilder.path("/v2/identity_documents");
                                if (requestPayload.containsKey("kyc_request")) {
                                    builder.queryParam("kyc_request", requestPayload.get("kyc_request"));
                                }
                                if (requestPayload.containsKey("fetch.status")) {
                                    builder.queryParam("fetch.status", requestPayload.get("fetch.status"));
                                }
                                return builder.build();
                            })
                            .headers(this::setTenantAuthHeaders)
                            .retrieve()
                            .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess(
                            "finprim",
                            "list_identity_documents",
                            "GET",
                            "/v2/identity_documents",
                            requestPayload.isEmpty() ? null : requestPayload,
                            response
                    );
                    return response;
                }));
    }

    @Override
    public SchemeFetchResult fetchProductSchemes() {
        beginTenantTokenScope();
        try {
            SchemeFetchResult mutualFunds = fetchPaginatedProductCatalogue(
                    "fetch_fund_schemes",
                    "/api/oms/fund_schemes",
                    FUND_SCHEME_MAX_PAGES,
                    this::getFundSchemesPageWithRateLimitRetry
            );
            if (!mutualFunds.complete()) {
                return mutualFunds;
            }

            SchemeFetchResult sifs;
            if (sifCatalogueUnavailable) {
                sifs = SchemeFetchResult.complete(List.of());
            } else {
                sifs = fetchPaginatedProductCatalogue(
                        "fetch_sif_scheme_plans",
                        "/v2/sif_scheme_plans/" + POA_ORDER_GATEWAY,
                        FUND_SCHEME_MAX_PAGES,
                        this::getSifSchemePlansPageWithRateLimitRetry
                );
            }
            if (!sifs.complete()) {
                List<ProductScheme> combined = new ArrayList<>(mutualFunds.schemes());
                combined.addAll(sifs.schemes());
                return SchemeFetchResult.partial(combined, sifs.incompleteReason());
            }

            List<ProductScheme> combined = new ArrayList<>(mutualFunds.schemes());
            combined.addAll(sifs.schemes());
            logger.debug(
                    "cybrilla_workflow operation='fetch_product_schemes' status='completed' mf_count='{}' sif_count='{}'",
                    mutualFunds.schemes().size(),
                    sifs.schemes().size()
            );
            return SchemeFetchResult.complete(combined);
        } finally {
            endTenantTokenScope();
        }
    }

    private void beginTenantTokenScope() {
        SCOPED_TENANT_BEARER.set(tokenService.getFinprimTenantAccessToken());
    }

    private void endTenantTokenScope() {
        SCOPED_TENANT_BEARER.remove();
    }

    private void clearScopedTenantBearer() {
        SCOPED_TENANT_BEARER.remove();
    }

    @FunctionalInterface
    private interface SchemePageFetcher {
        JsonNode fetch(int page, int size, Map<String, Object> requestSnapshot);
    }

    /**
     * Paginated Finprim catalogue fetch shared by OMS mutual funds and POA SIF scheme plans.
     */
    private SchemeFetchResult fetchPaginatedProductCatalogue(
            String operation,
            String path,
            int maxPages,
            SchemePageFetcher pageFetcher
    ) {
        int page = 0;
        int size = FUND_SCHEME_PAGE_SIZE;
        List<ProductScheme> schemes = new ArrayList<>();
        boolean truncatedAtPageCap = false;

        while (true) {
            int currentPage = page;
            Map<String, Object> requestSnapshot = new LinkedHashMap<>();
            JsonNode response;
            try {
                response = executeWithTenantTokenRetry(
                        operation + " page " + currentPage,
                        () -> pageFetcher.fetch(currentPage, size, requestSnapshot)
                );
            } catch (CybrillaApiException ex) {
                if (path.contains("sif_scheme_plans") && isNotFound(ex)) {
                    sifCatalogueUnavailable = true;
                    logger.info(
                            "cybrilla_workflow operation='{}' status='skipped' reason='sif_catalogue_not_enabled' path='{}'",
                            operation,
                            path
                    );
                    return SchemeFetchResult.complete(List.of());
                }
                throw ex;
            }
            JsonNode schemeNodes = extractSchemeArray(response);
            int pageCount = 0;
            String requestJson = writeJson(requestSnapshot);

            for (JsonNode schemeNode : schemeNodes) {
                schemes.add(toProductScheme(schemeNode, requestJson));
                pageCount++;
            }

            logger.debug("cybrilla_workflow operation='{}' status='page_loaded' page='{}' count='{}'", operation, page, pageCount);

            boolean naturalEnd = pageCount < size || isLastPage(response);
            if (naturalEnd) {
                break;
            }
            if (page >= maxPages - 1) {
                truncatedAtPageCap = true;
                logger.warn(
                        "cybrilla_workflow operation='{}' status='truncated_at_page_cap' page='{}' max_pages='{}' "
                                + "page_size='{}' partial_total='{}'",
                        operation,
                        page,
                        maxPages,
                        size,
                        schemes.size()
                );
                break;
            }
            page++;
        }

        if (truncatedAtPageCap) {
            return SchemeFetchResult.partial(schemes, "max_pages_reached");
        }
        logger.debug("cybrilla_workflow operation='{}' status='completed' total_count='{}'", operation, schemes.size());
        return SchemeFetchResult.complete(schemes);
    }

    @Override
    public String createOrder(TransactionOrder order, Investor investor, ProductScheme productScheme) {
        if (!StringUtils.hasText(investor.getCybrillaInvestorId())) {
            throw new CybrillaApiException("Investor must have a Cybrilla/FP investor profile id before order creation");
        }
        if (!StringUtils.hasText(investor.getExternalMfInvestmentAccountId())) {
            throw new CybrillaApiException("Investor must have a Fintech Primitives MF investment account id before order creation");
        }

        return executeWithTenantTokenRetry("create order", () -> {
            boolean sipOrder = order.getTransactionType() == TransactionType.SIP;
            JsonNode response = post(
                    sipOrder ? "create_mf_purchase_plan" : "create_mf_purchase",
                    sipOrder ? MF_PURCHASE_PLANS_PATH : MF_PURCHASES_PATH,
                    sipOrder ? mfPurchasePlanPayload(order, investor, productScheme) : mfPurchasePayload(order, investor, productScheme),
                    idempotencyKey("order", order.getId())
            );
            return extractId(response, "order");
        });
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
        if (!StringUtils.hasText(order.getExternalOrderId())) {
            throw new CybrillaApiException("Order must have a Cybrilla/FP external order id before redemption");
        }
        if (!StringUtils.hasText(investor.getExternalMfInvestmentAccountId())) {
            throw new CybrillaApiException("Investor must have a Fintech Primitives MF investment account id before redemption");
        }

        return executeWithTenantTokenRetry("create redemption", () -> {
            JsonNode response = post(
                    "create_mf_redemption",
                    MF_REDEMPTIONS_PATH,
                    redemptionPayload(order, investor, productScheme),
                    idempotencyKey("redemption", order.getId())
            );
            return extractId(response, "redemption");
        });
    }

    @Override
    public void cancelOrder(TransactionOrder order) {
        if (!StringUtils.hasText(order.getExternalOrderId())) {
            logger.warn("cybrilla_workflow operation='cancel_order' status='skipped' reason='missing_external_order_id' local_order_id='{}'", order.getId());
            return;
        }

        executeWithTenantTokenRetry("cancel order", () -> {
            String path = (order.getTransactionType() == TransactionType.SIP ? MF_PURCHASE_PLANS_PATH : MF_PURCHASES_PATH)
                    + "/" + order.getExternalOrderId()
                    + "/cancel";
            post("cancel_order", path, idempotencyKey("cancel-order", order.getId()));
            logger.info("cybrilla_workflow operation='cancel_order' status='completed' local_order_id='{}' external_order_id='{}'", order.getId(), order.getExternalOrderId());
            return null;
        });
    }

    private void createAddressIfPresent(String profileId, Investor investor) {
        if (!StringUtils.hasText(investor.getAddressLine1()) || !StringUtils.hasText(investor.getPostalCode())) {
            return;
        }

        executeWithTenantTokenRetry("create investor address", () -> {
            post("create_investor_address", "/v2/addresses", addressPayload(profileId, investor));
            logger.info("cybrilla_workflow operation='create_investor_address' status='completed' external_profile_id='{}'", profileId);
            return null;
        });
    }

    private void createEmailIfPresent(String profileId, Investor investor) {
        if (!StringUtils.hasText(investor.getEmail())) {
            return;
        }

        executeWithTenantTokenRetry("create investor email", () -> {
            post("create_investor_email", "/v2/email_addresses", emailPayload(profileId, investor));
            logger.info("cybrilla_workflow operation='create_investor_email' status='completed' external_profile_id='{}'", profileId);
            return null;
        });
    }

    private void createPhoneIfPresent(String profileId, Investor investor) {
        if (!StringUtils.hasText(investor.getMobileNumber())) {
            return;
        }

        executeWithTenantTokenRetry("create investor phone number", () -> {
            post("create_investor_phone", "/v2/phone_numbers", phonePayload(profileId, investor));
            logger.info("cybrilla_workflow operation='create_investor_phone' status='completed' external_profile_id='{}'", profileId);
            return null;
        });
    }

    private JsonNode post(String operation, String path, Map<String, Object> payload) {
        return post(operation, path, payload, null);
    }

    private JsonNode postPoa(String operation, String path, Map<String, Object> payload) {
        logger.info("cybrilla_api direction='backend_to_poa' method='POST' path='{}' payload_fields='{}'", path, payload.keySet());
        return recordApiRequest(operation, () -> {
                    JsonNode response = poaRestClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::setPoaAuthHeaders)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("cybrilla_poa", operation, "POST", path, payload, response);
                    return response;
                });
    }

    private JsonNode patchPoa(String operation, String path, Map<String, Object> payload) {
        logger.info("cybrilla_api direction='backend_to_poa' method='PATCH' path='{}' payload_fields='{}'", path, payload.keySet());
        return recordApiRequest(operation, () -> {
                    JsonNode response = poaRestClient.patch()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::setPoaAuthHeaders)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("cybrilla_poa", operation, "PATCH", path, payload, response);
                    return response;
                });
    }

    private JsonNode postPoaMultipartFile(String operation, String path, byte[] fileBytes, String filename, String contentType) {
        logger.info("cybrilla_api direction='backend_to_poa' method='POST' path='{}' multipart_file='{}'", path, filename);
        return recordApiRequest(operation, () -> {
                    MultipartBodyBuilder builder = new MultipartBodyBuilder();
                    builder.part("file", new ByteArrayResource(fileBytes) {
                        @Override
                        public String getFilename() {
                            return filename;
                        }
                    }).contentType(MediaType.parseMediaType(contentType));
                    MultiValueMap<String, HttpEntity<?>> body = builder.build();
                    JsonNode response = poaRestClient.post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .headers(this::setPoaAuthHeaders)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("cybrilla_poa", operation, "POST", path, Map.of("file", filename), response);
                    return response;
                });
    }

    private JsonNode post(String operation, String path, Map<String, Object> payload, String idempotencyKey) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='POST' path='{}' payload_fields='{}'", path, payload.keySet());
        return recordApiRequest(operation, () -> {
                    JsonNode response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> setTenantAuthHeaders(headers, idempotencyKey))
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("finprim", operation, "POST", path, payload, response);
                    return response;
                });
    }

    private JsonNode post(String operation, String path, String idempotencyKey) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='POST' path='{}' payload_fields='none'", path);
        return recordApiRequest(operation, () -> {
                    JsonNode response = restClient.post()
                    .uri(path)
                    .headers(headers -> setTenantAuthHeaders(headers, idempotencyKey))
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("finprim", operation, "POST", path, null, response);
                    return response;
                });
    }

    private JsonNode patch(String operation, String path, Map<String, Object> payload) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='PATCH' path='{}' payload_fields='{}'", path, payload.keySet());
        return recordApiRequest(operation, () -> {
                    JsonNode response = restClient.patch()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::setTenantAuthHeaders)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("finprim", operation, "PATCH", path, payload, response);
                    return response;
                });
    }

    private JsonNode put(String operation, String path) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='PUT' path='{}'", path);
        return recordApiRequest(operation, () -> {
                    JsonNode response = restClient.put()
                    .uri(path)
                    .headers(this::setTenantAuthHeaders)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("finprim", operation, "PUT", path, null, response);
                    return response;
                });
    }

    private JsonNode get(String operation, String path) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='GET' path='{}'", path);
        return recordApiRequest(operation, () -> {
                    JsonNode response = restClient.get()
                    .uri(path)
                    .headers(this::setTenantAuthHeaders)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("finprim", operation, "GET", path, null, response);
                    return response;
                });
    }

    private JsonNode getPoa(String operation, String path) {
        logger.info("cybrilla_api direction='backend_to_poa' method='GET' path='{}'", path);
        return recordApiRequest(operation, () -> {
                    JsonNode response = poaRestClient.get()
                    .uri(path)
                    .headers(this::setPoaAuthHeaders)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess("cybrilla_poa", operation, "GET", path, null, response);
                    return response;
                });
    }

    private void delete(String path) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='DELETE' path='{}'", path);
        recordApiRequest("cancel_order", () -> {
                    var response = restClient.delete()
                    .uri(path)
                    .headers(this::setTenantAuthHeaders)
                    .retrieve()
                    .toBodilessEntity();
                    externalApiSnapshotService.recordSuccess("finprim", "cancel_order", "DELETE", path, null, null);
                    return response;
                });
    }

    private JsonNode getFundSchemesPage(int page, int size) {
        logger.debug("cybrilla_api direction='backend_to_finprim' method='GET' path='/api/oms/fund_schemes' page='{}' size='{}'", page, size);
        return recordApiRequest("fetch_fund_schemes", () -> {
                    JsonNode response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/oms/fund_schemes")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build())
                    .headers(this::setTenantAuthHeaders)
                    .retrieve()
                    .body(JsonNode.class);
                    externalApiSnapshotService.recordSuccess(
                            "finprim",
                            "fetch_fund_schemes",
                            "GET",
                            "/api/oms/fund_schemes",
                            Map.of("page", page, "size", size),
                            response
                    );
                    return response;
                });
    }

    @Override
    public JsonNode getFundSchemesPageWithPayloadSnapshot(int page, int size, Map<String, Object> payloadSnapshot) {
        if (payloadSnapshot != null) {
            payloadSnapshot.put("operation", "fetch_fund_schemes_page");
            payloadSnapshot.put("path", "/api/oms/fund_schemes");
            payloadSnapshot.put("page", page);
            payloadSnapshot.put("size", size);
        }
        return getFundSchemesPage(page, size);
    }

    private JsonNode getSifSchemePlansPage(int page, int size) {
        String path = "/v2/sif_scheme_plans/" + POA_ORDER_GATEWAY;
        logger.debug("cybrilla_api direction='backend_to_finprim' method='GET' path='{}' page='{}' size='{}'", path, page, size);
        return recordApiRequest("fetch_sif_scheme_plans", () -> {
            JsonNode response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(path)
                            .queryParam("expand", "sif_scheme,sif_fund")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .build())
                    .headers(this::setTenantAuthHeaders)
                    .retrieve()
                    .body(JsonNode.class);
            externalApiSnapshotService.recordSuccess(
                    "finprim",
                    "fetch_sif_scheme_plans",
                    "GET",
                    path,
                    Map.of("page", page, "size", size, "expand", "sif_scheme,sif_fund"),
                    response
            );
            return response;
        });
    }

    private JsonNode getSifSchemePlansPageWithRateLimitRetry(int page, int size, Map<String, Object> payloadSnapshot) {
        if (payloadSnapshot != null) {
            payloadSnapshot.put("operation", "fetch_sif_scheme_plans_page");
            payloadSnapshot.put("path", "/v2/sif_scheme_plans/" + POA_ORDER_GATEWAY);
            payloadSnapshot.put("expand", "sif_scheme,sif_fund");
            payloadSnapshot.put("page", page);
            payloadSnapshot.put("size", size);
        }
        for (int attempt = 1; attempt <= FUND_SCHEME_RATE_LIMIT_RETRIES + 1; attempt++) {
            try {
                return getSifSchemePlansPage(page, size);
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS || attempt > FUND_SCHEME_RATE_LIMIT_RETRIES) {
                    throw ex;
                }
                long delayMillis = resolveRateLimitDelayMillis(ex, attempt);
                logger.warn(
                        "cybrilla_api rate_limited='true' path='/v2/sif_scheme_plans/{}' page='{}' attempt='{}' retry_in_ms='{}'",
                        POA_ORDER_GATEWAY,
                        page,
                        attempt,
                        delayMillis
                );
                sleep(delayMillis);
            }
        }
        throw new IllegalStateException("Unreachable SIF scheme plan retry state");
    }

    private <T> T recordApiRequest(String operation, Supplier<T> requestSupplier) {
        return Timer.builder("cybrilla.api.request")
                .description("Latency of Cybrilla/Fintech Primitives API requests")
                .tag("operation", operation)
                .register(meterRegistry)
                .record(requestSupplier);
    }

    private JsonNode getFundSchemesPageWithRateLimitRetry(int page, int size, Map<String, Object> requestSnapshot) {
        for (int attempt = 1; attempt <= FUND_SCHEME_RATE_LIMIT_RETRIES + 1; attempt++) {
            try {
                return getFundSchemesPageWithPayloadSnapshot(page, size, requestSnapshot);
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS || attempt > FUND_SCHEME_RATE_LIMIT_RETRIES) {
                    throw ex;
                }
                long delayMillis = resolveRateLimitDelayMillis(ex, attempt);
                logger.warn(
                        "cybrilla_api rate_limited='true' path='/api/oms/fund_schemes' page='{}' attempt='{}' retry_in_ms='{}'",
                        page,
                        attempt,
                        delayMillis
                );
                sleep(delayMillis);
            }
        }
        throw new IllegalStateException("Unreachable fund scheme retry state");
    }

    private long resolveRateLimitDelayMillis(RestClientResponseException ex, int attempt) {
        if (ex.getResponseHeaders() != null) {
            String retryAfter = ex.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (StringUtils.hasText(retryAfter) && retryAfter.matches("\\d+")) {
                return Long.parseLong(retryAfter) * 1_000L;
            }
        }
        return FUND_SCHEME_RATE_LIMIT_BACKOFF_MILLIS * attempt;
    }

    private void sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CybrillaApiException("Interrupted while waiting to retry rate-limited fund schemes request", ex);
        }
    }

    private void setTenantAuthHeaders(HttpHeaders headers) {
        setTenantAuthHeaders(headers, null);
    }

    private void setTenantAuthHeaders(HttpHeaders headers, String idempotencyKey) {
        String bearer = SCOPED_TENANT_BEARER.get();
        if (!StringUtils.hasText(bearer)) {
            bearer = tokenService.getFinprimTenantAccessToken();
        }
        headers.setBearerAuth(bearer);
        if (StringUtils.hasText(finprimProperties.tenantHeaderValue())) {
            headers.set(TENANT_HEADER, finprimProperties.tenantHeaderValue());
        }
        if (StringUtils.hasText(idempotencyKey)) {
            headers.set(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
    }

    private void setPoaAuthHeaders(HttpHeaders headers) {
        headers.setBearerAuth(tokenService.getCybrillaPreVerificationAccessToken());
    }

    /**
     * Retries transient connectivity failures (DNS resolution, connection
     * refused, read timeout — all surfaced by Spring as ResourceAccessException)
     * a few times with a short backoff. If the provider is still unreachable
     * after the last attempt, a CybrillaUnavailableException is thrown so callers
     * can degrade gracefully and the API can answer 503 instead of 502.
     * Business errors (4xx/5xx with a response body) are RestClientResponse
     * exceptions, NOT ResourceAccessException, so they pass straight through.
     */
    private <T> T executeWithConnectivityRetry(String operation, Supplier<T> supplier) {
        ResourceAccessException lastError = null;
        for (int attempt = 1; attempt <= CONNECTIVITY_RETRY_MAX_ATTEMPTS; attempt++) {
            try {
                return supplier.get();
            } catch (ResourceAccessException ex) {
                lastError = ex;
                logger.warn(
                        "cybrilla_api connectivity_error operation='{}' attempt='{}/{}' cause='{}'",
                        operation, attempt, CONNECTIVITY_RETRY_MAX_ATTEMPTS, rootCauseMessage(ex)
                );
                if (attempt < CONNECTIVITY_RETRY_MAX_ATTEMPTS) {
                    sleep(CONNECTIVITY_RETRY_BACKOFF_MILLIS * attempt);
                }
            }
        }
        throw new CybrillaUnavailableException(
                "Unable to reach the external investment platform to " + operation
                        + " (network/DNS unreachable): " + rootCauseMessage(lastError),
                lastError
        );
    }

    private <T> T executeWithTenantTokenRetry(String operation, Supplier<T> supplier) {
        try {
            return executeWithConnectivityRetry(operation, supplier);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                tokenService.invalidateFinprimTenantToken();
                clearScopedTenantBearer();
                try {
                    return executeWithConnectivityRetry(operation, supplier);
                } catch (RestClientResponseException retryEx) {
                    throw apiException(operation, retryEx);
                }
            }
            throw apiException(operation, ex);
        } catch (RestClientException ex) {
            throw apiException(operation, ex);
        } catch (ExternalApiAuthenticationException ex) {
            throw new CybrillaApiException("Unable to authenticate with Fintech Primitives while trying to " + operation, ex);
        }
    }

    private <T> T executeWithPoaTokenRetry(String operation, Supplier<T> supplier) {
        try {
            return executeWithConnectivityRetry(operation, supplier);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                tokenService.invalidateCybrillaPreVerificationToken();
                try {
                    return executeWithConnectivityRetry(operation, supplier);
                } catch (RestClientResponseException retryEx) {
                    throw poaApiException(operation, retryEx);
                }
            }
            throw poaApiException(operation, ex);
        } catch (RestClientException ex) {
            throw poaApiException(operation, ex);
        } catch (ExternalApiAuthenticationException ex) {
            throw new CybrillaApiException("Unable to authenticate with Cybrilla POA while trying to " + operation, ex);
        } catch (IllegalStateException ex) {
            throw new CybrillaApiException("Cybrilla POA configuration error while trying to " + operation + ": " + ex.getMessage(), ex);
        }
    }

    private CybrillaApiException poaApiException(String operation, RestClientResponseException ex) {
        String responseBody = ex.getResponseBodyAsString();
        String detail = StringUtils.hasText(responseBody) ? " response=" + responseBody : "";
        return new CybrillaApiException(
                "Unable to " + operation + " with Cybrilla POA: " + ex.getStatusCode() + detail,
                ex
        );
    }

    private CybrillaApiException poaApiException(String operation, RestClientException ex) {
        return new CybrillaApiException(
                "Unable to " + operation + " with Cybrilla POA: " + rootCauseMessage(ex),
                ex
        );
    }

    private boolean isNotFound(CybrillaApiException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RestClientResponseException responseEx) {
            return HttpStatus.NOT_FOUND.equals(responseEx.getStatusCode());
        }
        return ex.getMessage() != null && ex.getMessage().contains("404");
    }

    private CybrillaApiException apiException(String operation, RestClientResponseException ex) {
        String responseBody = ex.getResponseBodyAsString();
        String detail = StringUtils.hasText(responseBody) ? " response=" + responseBody : "";
        return new CybrillaApiException(
                "Unable to " + operation + " with Fintech Primitives: " + ex.getStatusCode() + detail,
                ex
        );
    }

    private CybrillaApiException apiException(String operation, RestClientException ex) {
        return new CybrillaApiException(
                "Unable to " + operation + " with Fintech Primitives: " + rootCauseMessage(ex),
                ex
        );
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        Throwable root = throwable;
        while (current != null) {
            root = current;
            current = current.getCause();
        }
        return root.getMessage() == null ? throwable.getClass().getSimpleName() : root.getMessage();
    }

    private Map<String, Object> investorProfilePayload(Investor investor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "type", "individual");
        put(payload, "tax_status", "resident_individual");
        put(payload, "name", investor.getFullName());
        if (investor.getDateOfBirth() != null) {
            put(payload, "date_of_birth", investor.getDateOfBirth().toString());
        }
        put(payload, "pan", investor.getPan() != null ? investor.getPan().trim().toUpperCase() : null);
        put(payload, "country_of_birth", "IN");
        put(payload, "place_of_birth", investor.getCity());
        put(payload, "nationality_country", "IN");
        put(payload, "use_default_tax_residences", true);
        put(payload, "source_of_wealth", "salary");
        put(payload, "income_slab", "upto_1lakh");
        put(payload, "pep_details", "not_applicable");
        return payload;
    }

    private Map<String, Object> investorProfileUpdatePayload(Investor investor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "id", investor.getCybrillaInvestorId());
        put(payload, "tax_status", "resident_individual");
        put(payload, "name", investor.getFullName());
        if (investor.getDateOfBirth() != null) {
            put(payload, "date_of_birth", investor.getDateOfBirth().toString());
        }
        put(payload, "country_of_birth", "IN");
        put(payload, "place_of_birth", investor.getCity());
        put(payload, "nationality_country", "IN");
        put(payload, "use_default_tax_residences", true);
        put(payload, "source_of_wealth", "salary");
        put(payload, "income_slab", "upto_1lakh");
        put(payload, "pep_details", "not_applicable");
        return payload;
    }

    private Map<String, Object> addressPayload(String profileId, Investor investor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "profile", profileId);
        put(payload, "line1", investor.getAddressLine1());
        put(payload, "line2", investor.getAddressLine2());
        put(payload, "city", investor.getCity());
        put(payload, "state", investor.getState());
        put(payload, "postal_code", investor.getPostalCode());
        put(payload, "country", "IN");
        put(payload, "nature", "residential");
        return payload;
    }

    private Map<String, Object> emailPayload(String profileId, Investor investor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "profile", profileId);
        put(payload, "email", investor.getEmail());
        put(payload, "belongs_to", "self");
        return payload;
    }

    private Map<String, Object> phonePayload(String profileId, Investor investor) {
        PhoneParts phoneParts = PhoneParts.from(investor.getMobileNumber());
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "profile", profileId);
        put(payload, "isd", phoneParts.isd());
        put(payload, "number", phoneParts.number());
        put(payload, "belongs_to", "self");
        return payload;
    }

    private Map<String, Object> bankAccountPayload(Investor investor, InvestorBankAccount bankAccount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "profile", investor.getCybrillaInvestorId());
        put(payload, "primary_account_holder_name", bankAccount.getAccountHolderName());
        put(payload, "account_number", bankAccount.getAccountNumber());
        put(payload, "type", "savings");
        put(payload, "ifsc_code", bankAccount.getIfscCode());
        return payload;
    }

    private Map<String, Object> mfInvestmentAccountPayload(Investor investor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "primary_investor", investor.getCybrillaInvestorId());
        put(payload, "holding_pattern", "single");
        return payload;
    }

    private Map<String, Object> preVerificationPayload(Investor investor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String pan = investor.getPan() == null ? null : investor.getPan().trim().toUpperCase();
        put(payload, "investor_identifier", pan);
        putPoaValue(payload, "pan", pan);
        putPoaValue(payload, "name", investor.getFullName());
        if (investor.getDateOfBirth() != null) {
            putPoaValue(payload, "date_of_birth", investor.getDateOfBirth().toString());
        }
        return payload;
    }

    private Map<String, Object> bankAccountPreVerificationPayload(Investor investor, InvestorBankAccount bankAccount) {
        Map<String, Object> payload = preVerificationPayload(investor);
        Map<String, Object> bankValue = new LinkedHashMap<>();
        put(bankValue, "account_number", bankAccount.getAccountNumber());
        put(bankValue, "ifsc_code", bankAccount.getIfscCode());
        put(bankValue, "account_type", "savings");
        payload.put("bank_accounts", List.of(Map.of("value", bankValue)));
        return payload;
    }

    private JsonNode firstBankAccountVerification(JsonNode verification) {
        if (verification == null || !verification.has("bank_accounts") || !verification.path("bank_accounts").isArray()
                || verification.path("bank_accounts").isEmpty()) {
            return null;
        }
        return verification.path("bank_accounts").get(0);
    }

    private void putPoaValue(Map<String, Object> payload, String key, String value) {
        if (StringUtils.hasText(value)) {
            payload.put(key, Map.of("value", value.trim()));
        }
    }

    private Map<String, Object> mfPurchasePayload(TransactionOrder order, Investor investor, ProductScheme productScheme) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "source_ref_id", order.getId() == null ? null : order.getId().toString());
        put(payload, "mf_investment_account", investor.getExternalMfInvestmentAccountId());
        put(payload, "scheme", schemeIdentifier(productScheme));
        put(payload, "amount", order.getAmount());
        return payload;
    }

    private Map<String, Object> mfPurchasePlanPayload(TransactionOrder order, Investor investor, ProductScheme productScheme) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "source_ref_id", order.getId() == null ? null : order.getId().toString());
        put(payload, "mf_investment_account", investor.getExternalMfInvestmentAccountId());
        put(payload, "scheme", schemeIdentifier(productScheme));
        put(payload, "amount", order.getAmount());
        put(payload, "systematic", true);
        put(payload, "frequency", externalFrequency(order.getSipFrequency()));
        put(payload, "start_date", order.getSipStartDate() == null ? null : order.getSipStartDate().toString());
        put(payload, "number_of_installments", order.getSipInstalments());
        put(payload, "auto_generate_installments", true);
        return payload;
    }

    private Map<String, Object> redemptionPayload(TransactionOrder order, Investor investor, ProductScheme productScheme) {
        Map<String, Object> payload = new LinkedHashMap<>();
        put(payload, "source_ref_id", order.getId() == null ? null : "redemption-" + order.getId());
        put(payload, "mf_investment_account", investor.getExternalMfInvestmentAccountId());
        put(payload, "scheme", schemeIdentifier(productScheme));
        put(payload, "amount", order.getAmount());
        put(payload, "units", order.getUnits());
        return payload;
    }

    private String schemeIdentifier(ProductScheme productScheme) {
        if (productScheme == null) {
            throw new CybrillaApiException("Product scheme is required before order creation");
        }
        String scheme = defaultText(productScheme.getExternalIsin(), productScheme.getExternalSchemeCode());
        if (!StringUtils.hasText(scheme)) {
            throw new CybrillaApiException("Product scheme must have an external ISIN or scheme code before order creation");
        }
        return scheme;
    }

    private String externalFrequency(String sipFrequency) {
        if (!StringUtils.hasText(sipFrequency)) {
            return null;
        }
        return switch (sipFrequency.trim().toUpperCase()) {
            case "MONTHLY" -> "monthly";
            case "QUARTERLY" -> "quarterly";
            default -> sipFrequency.trim().toLowerCase();
        };
    }

    private String idempotencyKey(String prefix, UUID id) {
        return id == null ? null : prefix + "-" + id;
    }

    private ProductScheme toProductScheme(JsonNode schemeNode, String requestSnapshotJson) {
        // FP's fund scheme master is keyed by ISIN (GET /api/oms/fund_schemes/{isin}),
        // so ISIN is the most stable unique key. scheme_code can collide across plans.
        String isin = firstText(schemeNode, "isin", "isin_code");
        String externalCode = firstText(schemeNode, "isin", "scheme_code", "id", "fund_scheme_id", "code");
        if (!StringUtils.hasText(externalCode)) {
            externalCode = isin;
        }
        if (!StringUtils.hasText(externalCode)) {
            throw new CybrillaApiException("Fund scheme response did not include an isin, scheme_code, id, or fund_scheme_id");
        }

        ProductScheme scheme = new ProductScheme();
        scheme.setSchemeName(defaultText(
                firstText(schemeNode, "name", "scheme_name", "fund_scheme_name", "display_name"),
                defaultText(
                        nestedText(schemeNode, "sif_scheme", "name"),
                        defaultText(nestedText(schemeNode, "mf_scheme", "name"), externalCode)
                )
        ));
        ProductCategory category = resolveProductCategory(schemeNode);
        scheme.setAmcName(defaultText(
                nestedText(schemeNode, category == ProductCategory.SIF ? "sif_fund" : "mf_fund", "name"),
                resolveAmcName(schemeNode)
        ));
        scheme.setCategory(category);
        scheme.setExternalSchemeCode(externalCode);
        scheme.setExternalIsin(isin);
        scheme.setProductType(category == ProductCategory.SIF ? "SIF" : "MUTUAL_FUND");
        scheme.setActive(resolveActive(schemeNode));
        scheme.setExternalFetchRequestJson(requestSnapshotJson);
        scheme.setMetadataJson(buildSchemeMetadata(schemeNode));
        return scheme;
    }

    /**
     * Keeps the full FP payload but normalizes a couple of keys the distributor UI
     * reads (category / min purchase / trade-eligibility flags) so fetched schemes
     * render with the right SEBI category and minimums instead of falling back to
     * "Equity / Rs 100".
     */
    private String buildSchemeMetadata(JsonNode schemeNode) {
        if (!(schemeNode instanceof ObjectNode source)) {
            return schemeNode.toString();
        }
        ObjectNode metadata = source.deepCopy();
        String fundCategory = firstText(schemeNode, "fund_category", "category", "scheme_category");
        if (StringUtils.hasText(fundCategory) && !metadata.hasNonNull("category")) {
            metadata.put("category", fundCategory);
        }
        return metadata.toString();
    }

    private String nestedText(JsonNode node, String objectName, String fieldName) {
        JsonNode value = node.path(objectName).path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    private JsonNode extractSchemeArray(JsonNode response) {
        if (response == null || response.isNull()) {
            throw new CybrillaApiException("Fund scheme response was empty");
        }
        if (response.isArray()) {
            return response;
        }
        for (String fieldName : List.of("fund_schemes", "sif_scheme_plans", "mf_scheme_plans", "data", "content", "items", "results")) {
            JsonNode candidate = response.path(fieldName);
            if (candidate.isArray()) {
                return candidate;
            }
        }
        throw new CybrillaApiException("Fund scheme response did not include an array of schemes");
    }

    private boolean isLastPage(JsonNode response) {
        if (response == null || response.isArray()) {
            return false;
        }
        if (response.path("last").asBoolean(false)) {
            return true;
        }
        int page = response.path("page").asInt(-1);
        int totalPages = firstInt(response, "total_pages", "totalPages", "pages");
        return page >= 0 && totalPages > 0 && page + 1 >= totalPages;
    }

    private ProductCategory resolveProductCategory(JsonNode schemeNode) {
        String objectType = firstText(schemeNode, "object");
        if (StringUtils.hasText(objectType)) {
            String normalized = objectType.trim().toLowerCase();
            if (normalized.contains("sif")) {
                return ProductCategory.SIF;
            }
        }
        String productType = firstText(schemeNode, "product_type", "instrument_type", "asset_class");
        if (StringUtils.hasText(productType) && productType.trim().toUpperCase().contains("SIF")) {
            return ProductCategory.SIF;
        }
        return ProductCategory.MF;
    }

    private boolean resolveActive(JsonNode schemeNode) {
        for (String fieldName : List.of("active", "is_active", "enabled")) {
            JsonNode value = schemeNode.path(fieldName);
            if (value.isBoolean()) {
                return value.asBoolean();
            }
        }
        return true;
    }

    private String resolveAmcName(JsonNode schemeNode) {
        String directName = firstText(schemeNode, "amc_name", "fund_house", "fund_house_name", "amc");
        if (StringUtils.hasText(directName)) {
            return directName;
        }
        JsonNode amcNode = schemeNode.path("amc");
        if (amcNode.isObject()) {
            String nestedName = firstText(amcNode, "name", "amc_name");
            if (StringUtils.hasText(nestedName)) {
                return nestedName;
            }
        }
        String amcId = firstText(schemeNode, "amc_id");
        return StringUtils.hasText(amcId) ? "AMC " + amcId : "Unknown AMC";
    }

    private int firstInt(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isInt() || value.isLong()) {
                return value.asInt();
            }
            if (value.isTextual() && value.asText().matches("\\d+")) {
                return Integer.parseInt(value.asText());
            }
        }
        return -1;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText();
                if (StringUtils.hasText(text)) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            logger.warn("external_snapshot status='serialize_failed' reason='{}'", ex.getMessage());
            return null;
        }
    }

    private String extractId(JsonNode response, String objectName) {
        if (response != null && response.hasNonNull("id")) {
            return response.get("id").asText();
        }
        throw new CybrillaApiException("Fintech Primitives " + objectName + " response did not include id");
    }

    private void put(Map<String, Object> payload, String key, Object value) {
        if (value instanceof String stringValue) {
            if (StringUtils.hasText(stringValue)) {
                payload.put(key, stringValue.trim());
            }
            return;
        }
        if (value != null) {
            payload.put(key, value);
        }
    }

    private record PhoneParts(String isd, String number) {
        static PhoneParts from(String rawPhone) {
            String digits = rawPhone == null ? "" : rawPhone.replaceAll("[^0-9]", "");
            if (digits.startsWith("91") && digits.length() > 10) {
                return new PhoneParts("91", digits.substring(2));
            }
            return new PhoneParts("91", digits);
        }
    }
}
