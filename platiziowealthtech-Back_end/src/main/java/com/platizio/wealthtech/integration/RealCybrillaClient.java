package com.platizio.wealthtech.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.integration.auth.ExternalBearerTokenService;
import com.platizio.wealthtech.integration.auth.FinprimTenantProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(prefix = "cybrilla.integration", name = "real-client-enabled", havingValue = "true", matchIfMissing = true)
public class RealCybrillaClient implements CybrillaClient {

    private static final Logger logger = LoggerFactory.getLogger(RealCybrillaClient.class);
    private static final String TENANT_HEADER = "x-tenant-id";
    private static final int FUND_SCHEME_PAGE_SIZE = 100;
    private static final int FUND_SCHEME_MAX_PAGES = 50;
    private static final int FUND_SCHEME_RATE_LIMIT_RETRIES = 3;
    private static final long FUND_SCHEME_RATE_LIMIT_BACKOFF_MILLIS = 5_000L;

    private final RestClient restClient;
    private final ExternalBearerTokenService tokenService;
    private final FinprimTenantProperties finprimProperties;

    public RealCybrillaClient(
            RestClient.Builder restClientBuilder,
            ExternalBearerTokenService tokenService,
            FinprimTenantProperties finprimProperties
    ) {
        this.restClient = restClientBuilder
                .baseUrl(finprimProperties.getBaseUrl())
                .build();
        this.tokenService = tokenService;
        this.finprimProperties = finprimProperties;
        logger.info("cybrilla_client mode='real' base_url='{}' tenant_header_configured='{}'", finprimProperties.getBaseUrl(), StringUtils.hasText(finprimProperties.tenantHeaderValue()));
    }

    @Override
    public String createInvestorProfile(Investor investor) {
        logger.info("cybrilla_workflow operation='create_investor_profile' status='started' local_investor_id='{}'", investor.getId());
        String profileId = executeWithTenantTokenRetry("create investor profile", () -> {
            JsonNode response = post("/v2/investor_profiles", investorProfilePayload(investor));
            return extractId(response, "investor profile");
        });
        logger.info("cybrilla_workflow operation='create_investor_profile' status='completed' local_investor_id='{}' external_profile_id='{}'", investor.getId(), profileId);

        createAddressIfPresent(profileId, investor);
        createEmailIfPresent(profileId, investor);
        createPhoneIfPresent(profileId, investor);

        return profileId;
    }

    @Override
    public void captureBankAccount(Investor investor, InvestorBankAccount bankAccount) {
        if (!StringUtils.hasText(investor.getCybrillaInvestorId())) {
            throw new CybrillaApiException("Investor must have a Cybrilla/FP investor profile id before adding a bank account");
        }

        String bankAccountId = executeWithTenantTokenRetry("create bank account", () -> {
            JsonNode response = post("/v2/bank_accounts", bankAccountPayload(investor, bankAccount));
            return extractId(response, "bank account");
        });
        logger.info("cybrilla_workflow operation='create_bank_account' status='completed' local_investor_id='{}' local_bank_id='{}' external_bank_id='{}'", investor.getId(), bankAccount.getId(), bankAccountId);
        bankAccount.setCybrillaBankId(bankAccountId);
    }

    @Override
    public List<ProductScheme> fetchProductSchemes() {
        int page = 0;
        int size = FUND_SCHEME_PAGE_SIZE;
        List<ProductScheme> schemes = new ArrayList<>();

        while (true) {
            int currentPage = page;
            JsonNode response = executeWithTenantTokenRetry("fetch fund schemes page " + currentPage, () -> getFundSchemesPageWithRateLimitRetry(currentPage, size));
            JsonNode schemeNodes = extractSchemeArray(response);
            int pageCount = 0;

            for (JsonNode schemeNode : schemeNodes) {
                schemes.add(toProductScheme(schemeNode));
                pageCount++;
            }

            logger.info("cybrilla_workflow operation='fetch_fund_schemes' status='page_loaded' page='{}' count='{}'", page, pageCount);

            if (pageCount < size || isLastPage(response) || page >= FUND_SCHEME_MAX_PAGES - 1) {
                break;
            }
            page++;
        }

        logger.info("cybrilla_workflow operation='fetch_fund_schemes' status='completed' total_count='{}'", schemes.size());
        return schemes;
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

    private void createAddressIfPresent(String profileId, Investor investor) {
        if (!StringUtils.hasText(investor.getAddressLine1()) || !StringUtils.hasText(investor.getPostalCode())) {
            return;
        }

        executeWithTenantTokenRetry("create investor address", () -> {
            post("/v2/addresses", addressPayload(profileId, investor));
            logger.info("cybrilla_workflow operation='create_investor_address' status='completed' external_profile_id='{}'", profileId);
            return null;
        });
    }

    private void createEmailIfPresent(String profileId, Investor investor) {
        if (!StringUtils.hasText(investor.getEmail())) {
            return;
        }

        executeWithTenantTokenRetry("create investor email", () -> {
            post("/v2/email_addresses", emailPayload(profileId, investor));
            logger.info("cybrilla_workflow operation='create_investor_email' status='completed' external_profile_id='{}'", profileId);
            return null;
        });
    }

    private void createPhoneIfPresent(String profileId, Investor investor) {
        if (!StringUtils.hasText(investor.getMobileNumber())) {
            return;
        }

        executeWithTenantTokenRetry("create investor phone number", () -> {
            post("/v2/phone_numbers", phonePayload(profileId, investor));
            logger.info("cybrilla_workflow operation='create_investor_phone' status='completed' external_profile_id='{}'", profileId);
            return null;
        });
    }

    private JsonNode post(String path, Map<String, Object> payload) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='POST' path='{}' payload_fields='{}'", path, payload.keySet());
        return restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::setTenantAuthHeaders)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode getFundSchemesPage(int page, int size) {
        logger.info("cybrilla_api direction='backend_to_finprim' method='GET' path='/v2/mf_scheme_plans/cybrillapoa' page='{}' size='{}'", page, size);
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/mf_scheme_plans/cybrillapoa")
                        .queryParam("expand", "mf_scheme,mf_fund")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build())
                .headers(this::setTenantAuthHeaders)
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode getFundSchemesPageWithRateLimitRetry(int page, int size) {
        for (int attempt = 1; attempt <= FUND_SCHEME_RATE_LIMIT_RETRIES + 1; attempt++) {
            try {
                return getFundSchemesPage(page, size);
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS || attempt > FUND_SCHEME_RATE_LIMIT_RETRIES) {
                    throw ex;
                }
                long delayMillis = resolveRateLimitDelayMillis(ex, attempt);
                logger.warn(
                        "cybrilla_api rate_limited='true' path='/v2/mf_scheme_plans/cybrillapoa' page='{}' attempt='{}' retry_in_ms='{}'",
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
        headers.setBearerAuth(tokenService.getFinprimTenantAccessToken());
        if (StringUtils.hasText(finprimProperties.tenantHeaderValue())) {
            headers.set(TENANT_HEADER, finprimProperties.tenantHeaderValue());
        }
    }

    private <T> T executeWithTenantTokenRetry(String operation, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                tokenService.invalidateFinprimTenantToken();
                try {
                    return supplier.get();
                } catch (RestClientResponseException retryEx) {
                    throw apiException(operation, retryEx);
                }
            }
            throw apiException(operation, ex);
        }
    }

    private CybrillaApiException apiException(String operation, RestClientResponseException ex) {
        String responseBody = ex.getResponseBodyAsString();
        String detail = StringUtils.hasText(responseBody) ? " response=" + responseBody : "";
        return new CybrillaApiException(
                "Unable to " + operation + " with Fintech Primitives: " + ex.getStatusCode() + detail,
                ex
        );
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

    private ProductScheme toProductScheme(JsonNode schemeNode) {
        String externalCode = firstText(schemeNode, "id", "scheme_code", "code", "fund_scheme_id", "isin");
        String isin = firstText(schemeNode, "isin", "isin_code");
        if (!StringUtils.hasText(externalCode)) {
            externalCode = isin;
        }
        if (!StringUtils.hasText(externalCode)) {
            throw new CybrillaApiException("Fund scheme response did not include an id, scheme_code, code, fund_scheme_id, or isin");
        }

        ProductScheme scheme = new ProductScheme();
        scheme.setSchemeName(defaultText(
                firstText(schemeNode, "name", "scheme_name", "fund_scheme_name", "display_name"),
                defaultText(nestedText(schemeNode, "mf_scheme", "name"), externalCode)
        ));
        scheme.setAmcName(defaultText(nestedText(schemeNode, "mf_fund", "name"), resolveAmcName(schemeNode)));
        scheme.setCategory(ProductCategory.MF);
        scheme.setExternalSchemeCode(externalCode);
        scheme.setExternalIsin(isin);
        scheme.setProductType("MF");
        scheme.setActive(resolveActive(schemeNode));
        scheme.setMetadataJson(schemeNode.toString());
        return scheme;
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
        for (String fieldName : List.of("fund_schemes", "data", "content", "items", "results")) {
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
