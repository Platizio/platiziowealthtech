package com.platizio.wealthtech.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.integration.auth.CybrillaPreVerificationProperties;
import com.platizio.wealthtech.integration.auth.ExternalBearerTokenService;
import com.platizio.wealthtech.integration.auth.FinprimTenantProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RealCybrillaClientTest {

    @Test
    void createKycCheckCreatesPoaPreVerificationWithPartnerToken() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token", "poa-token"));
        Investor investor = investor(null, null);
        investor.setPan("aaapa3751a");
        investor.setFullName("Rani Gupta");
        investor.setDateOfBirth(LocalDate.of(1955, 10, 25));

        fixture.server.expect(once(), requestTo("https://poa.test/poa/pre_verifications"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer poa-token"))
                .andExpect(content().json("""
                        {
                          "investor_identifier": "AAAPA3751A",
                          "pan": { "value": "AAAPA3751A" },
                          "name": { "value": "Rani Gupta" },
                          "date_of_birth": { "value": "1955-10-25" }
                        }
                        """))
                .andRespond(withSuccess("{\"object\":\"pre_verification\",\"id\":\"pv_1\",\"status\":\"accepted\"}", MediaType.APPLICATION_JSON));

        JsonNode response = fixture.client.createKycCheck(investor);

        fixture.server.verify();
        assertThat(response.path("id").asText()).isEqualTo("pv_1");
        assertThat(response.path("status").asText()).isEqualTo("accepted");
    }

    @Test
    void createPreVerificationPostsPayloadWithPartnerToken() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token", "poa-token"));

        fixture.server.expect(once(), requestTo("https://poa.test/poa/pre_verifications"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer poa-token"))
                .andExpect(content().json("""
                        {
                          "investor_identifier": "AAAPA3751A",
                          "pan": { "value": "AAAPA3751A" },
                          "name": { "value": "Rani Gupta" },
                          "date_of_birth": { "value": "1955-10-25" }
                        }
                        """))
                .andRespond(withSuccess("{\"object\":\"pre_verification\",\"id\":\"pv_payload\",\"status\":\"accepted\"}", MediaType.APPLICATION_JSON));

        JsonNode response = fixture.client.createPreVerification(Map.of(
                "investor_identifier", "AAAPA3751A",
                "pan", Map.of("value", "AAAPA3751A"),
                "name", Map.of("value", "Rani Gupta"),
                "date_of_birth", Map.of("value", "1955-10-25")
        ));

        fixture.server.verify();
        assertThat(response.path("id").asText()).isEqualTo("pv_payload");
    }

    @Test
    void createKycRequestPostsToKycRequestsEndpoint() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/kyc_requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andExpect(header("x-tenant-id", "tenant-123"))
                .andExpect(content().json("""
                        {
                          "name": "Rani Gupta",
                          "pan": "SKLPA9239S"
                        }
                        """))
                .andRespond(withSuccess("{\"id\":\"kycr_1\",\"status\":\"pending\"}", MediaType.APPLICATION_JSON));

        JsonNode response = fixture.client.createKycRequest(Map.of("name", "Rani Gupta", "pan", "SKLPA9239S"));

        fixture.server.verify();
        assertThat(response.path("id").asText()).isEqualTo("kycr_1");
    }

    @Test
    void createIdentityDocumentPostsToIdentityDocumentsEndpoint() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/identity_documents"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andExpect(header("x-tenant-id", "tenant-123"))
                .andExpect(content().json("""
                        {
                          "kyc_request": "kycr_1",
                          "type": "aadhaar",
                          "postback_url": "https://app.example/callback"
                        }
                        """))
                .andRespond(withSuccess("{\"id\":\"iddoc_1\",\"fetch\":{\"status\":\"pending\"}}", MediaType.APPLICATION_JSON));

        JsonNode response = fixture.client.createIdentityDocument(Map.of(
                "kyc_request", "kycr_1",
                "type", "aadhaar",
                "postback_url", "https://app.example/callback"
        ));

        fixture.server.verify();
        assertThat(response.path("id").asText()).isEqualTo("iddoc_1");
    }

    @Test
    void createOrderPostsToMfPurchasesEndpointWithPayloadAndIdempotencyKey() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        TransactionOrder order = order(TransactionType.LUMPSUM_PURCHASE);
        Investor investor = investor("profile-1", "mfia-1");
        ProductScheme productScheme = productScheme("INF209KA1K47");

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/mf_purchases"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andExpect(header("x-tenant-id", "tenant-123"))
                .andExpect(header("Idempotency-Key", "order-" + order.getId()))
                .andExpect(content().json("""
                        {
                          "source_ref_id": "%s",
                          "mf_investment_account": "mfia-1",
                          "scheme": "INF209KA1K47",
                          "amount": 1500.50
                        }
                        """.formatted(
                        order.getId()
                )))
                .andRespond(withSuccess("{\"id\":\"mfp_1\"}", MediaType.APPLICATION_JSON));

        String externalOrderId = fixture.client.createOrder(order, investor, productScheme);

        fixture.server.verify();
        assertThat(externalOrderId).isEqualTo("mfp_1");
    }

    @Test
    void createSipOrderPostsToMfPurchasePlansEndpoint() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        TransactionOrder order = order(TransactionType.SIP);
        order.setSipFrequency("MONTHLY");
        order.setSipStartDate(LocalDate.of(2026, 7, 1));
        order.setSipInstalments(12);
        Investor investor = investor("profile-1", "mfia-1");
        ProductScheme productScheme = productScheme("INF209KA1K47");

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/mf_purchase_plans"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "order-" + order.getId()))
                .andExpect(content().json("""
                        {
                          "source_ref_id": "%s",
                          "mf_investment_account": "mfia-1",
                          "scheme": "INF209KA1K47",
                          "amount": 1500.50,
                          "systematic": true,
                          "frequency": "monthly",
                          "start_date": "2026-07-01",
                          "number_of_installments": 12,
                          "auto_generate_installments": true
                        }
                        """.formatted(order.getId())))
                .andRespond(withSuccess("{\"id\":\"mfpp_1\"}", MediaType.APPLICATION_JSON));

        String externalOrderId = fixture.client.createOrder(order, investor, productScheme);

        fixture.server.verify();
        assertThat(externalOrderId).isEqualTo("mfpp_1");
    }

    @Test
    void createRedemptionPostsMfRedemptionPayload() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        TransactionOrder order = order(TransactionType.REDEMPTION);
        order.setExternalOrderId("fp-order-1");
        order.setAmount(null);
        order.setUnits(new BigDecimal("10.25"));
        Investor investor = investor("profile-1", "mfia-1");
        ProductScheme productScheme = productScheme("INF209KA1K47");

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/mf_redemptions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "redemption-" + order.getId()))
                .andExpect(content().json("""
                        {
                          "source_ref_id": "redemption-%s",
                          "mf_investment_account": "mfia-1",
                          "scheme": "INF209KA1K47",
                          "units": 10.25
                        }
                        """.formatted(
                        order.getId()
                )))
                .andRespond(withSuccess("{\"id\":\"mfr_1\"}", MediaType.APPLICATION_JSON));

        String externalRedemptionId = fixture.client.createRedemption(order, investor, productScheme);

        fixture.server.verify();
        assertThat(externalRedemptionId).isEqualTo("mfr_1");
    }

    @Test
    void nonSuccessfulOrderResponseIsWrappedInCybrillaApiException() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        TransactionOrder order = order(TransactionType.LUMPSUM_PURCHASE);

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/mf_purchases"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"upstream failed\"}"));

        assertThatThrownBy(() -> fixture.client.createOrder(order, investor("profile-1", "mfia-1"), productScheme("INF209KA1K47")))
                .isInstanceOf(CybrillaApiException.class)
                .hasMessageContaining("Unable to create order with Fintech Primitives")
                .hasMessageContaining("500")
                .hasMessageContaining("upstream failed");

        fixture.server.verify();
    }

    @Test
    void networkFailuresAreWrappedInCybrillaApiException() {
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    throw new IOException("DNS failure");
                });
        FinprimTenantProperties properties = new FinprimTenantProperties();
        properties.setBaseUrl("https://finprim.test");
        properties.getTenant().setId("tenant-123");
        RealCybrillaClient client = new RealCybrillaClient(
                restClientBuilder,
                new StaticBearerTokenService("tenant-token"),
                properties,
                new CybrillaPreVerificationProperties(),
                new SimpleMeterRegistry()
        );

        assertThatThrownBy(() -> client.createOrder(order(TransactionType.LUMPSUM_PURCHASE), investor("profile-1", "mfia-1"), productScheme("INF209KA1K47")))
                .isInstanceOf(CybrillaApiException.class)
                .hasMessageContaining("Unable to create order with Fintech Primitives")
                .hasMessageContaining("DNS failure");
    }

    @Test
    void unauthorizedOrderRequestInvalidatesTokenAndRetriesOnce() {
        RotatingBearerTokenService tokenService = new RotatingBearerTokenService();
        ClientFixture fixture = clientFixture(tokenService);
        TransactionOrder order = order(TransactionType.LUMPSUM_PURCHASE);

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/mf_purchases"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
                .andExpect(header("Idempotency-Key", "order-" + order.getId()))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"expired token\"}"));
        fixture.server.expect(once(), requestTo("https://finprim.test/v2/mf_purchases"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh-token"))
                .andExpect(header("Idempotency-Key", "order-" + order.getId()))
                .andRespond(withSuccess("{\"id\":\"fp-order-2\"}", MediaType.APPLICATION_JSON));

        String externalOrderId = fixture.client.createOrder(order, investor("profile-1", "mfia-1"), productScheme("INF209KA1K47"));

        fixture.server.verify();
        assertThat(externalOrderId).isEqualTo("fp-order-2");
        assertThat(tokenService.invalidations).isEqualTo(1);
    }

    @Test
    void createMfInvestmentAccountPostsProfileAndHoldingPattern() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        Investor investor = investor("profile-1", null);

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/mf_investment_accounts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "primary_investor": "profile-1",
                          "holding_pattern": "single"
                        }
                        """))
                .andRespond(withSuccess("{\"id\":\"mfia_1\"}", MediaType.APPLICATION_JSON));

        String accountId = fixture.client.createMfInvestmentAccount(investor);

        fixture.server.verify();
        assertThat(accountId).isEqualTo("mfia_1");
    }

    @Test
    void captureBankAccountCreatesBankAccountAndVerificationRequest() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        Investor investor = investor("profile-1", "mfia-1");
        InvestorBankAccount bankAccount = bankAccount();

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/bank_accounts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "profile": "profile-1",
                          "primary_account_holder_name": "Alice Investor",
                          "account_number": "98123459204",
                          "type": "savings",
                          "ifsc_code": "HDFC0001330"
                        }
                        """))
                .andRespond(withSuccess("{\"id\":\"bac_1\"}", MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo("https://finprim.test/v2/bank_account_verifications"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "bank_account": "bac_1"
                        }
                        """))
                .andRespond(withSuccess("{\"id\":\"bav_1\",\"status\":\"pending\",\"confidence\":null}", MediaType.APPLICATION_JSON));

        fixture.client.captureBankAccount(investor, bankAccount);

        fixture.server.verify();
        assertThat(bankAccount.getCybrillaBankId()).isEqualTo("bac_1");
        assertThat(bankAccount.getCybrillaBankVerificationId()).isEqualTo("bav_1");
        assertThat(bankAccount.getCybrillaBankVerificationStatus()).isEqualTo("pending");
    }

    private ClientFixture clientFixture(ExternalBearerTokenService tokenService) {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        FinprimTenantProperties properties = new FinprimTenantProperties();
        properties.setBaseUrl("https://finprim.test");
        properties.getTenant().setId("tenant-123");
        CybrillaPreVerificationProperties poaProperties = new CybrillaPreVerificationProperties();
        poaProperties.setBaseUrl("https://poa.test");

        RealCybrillaClient client = new RealCybrillaClient(
                restClientBuilder,
                tokenService,
                properties,
                poaProperties,
                new SimpleMeterRegistry()
        );

        return new ClientFixture(client, server);
    }

    private TransactionOrder order(TransactionType transactionType) {
        TransactionOrder order = new TransactionOrder();
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.setInvestorId(UUID.randomUUID());
        order.setDistributorId(UUID.randomUUID());
        order.setProductSchemeId(UUID.randomUUID());
        order.setTransactionType(transactionType);
        order.setAmount(new BigDecimal("1500.50"));
        order.setPaymentMode("NET_BANKING");
        return order;
    }

    private Investor investor(String profileId, String mfInvestmentAccountId) {
        Investor investor = new Investor();
        investor.setCybrillaInvestorId(profileId);
        investor.setExternalMfInvestmentAccountId(mfInvestmentAccountId);
        return investor;
    }

    private ProductScheme productScheme(String isin) {
        ProductScheme productScheme = new ProductScheme();
        productScheme.setSchemeName("Test Scheme");
        productScheme.setAmcName("Test AMC");
        productScheme.setCategory(ProductCategory.MF);
        productScheme.setExternalSchemeCode(isin);
        productScheme.setExternalIsin(isin);
        return productScheme;
    }

    private InvestorBankAccount bankAccount() {
        InvestorBankAccount bankAccount = new InvestorBankAccount();
        bankAccount.setAccountHolderName("Alice Investor");
        bankAccount.setAccountNumber("98123459204");
        bankAccount.setIfscCode("HDFC0001330");
        return bankAccount;
    }

    private record ClientFixture(RealCybrillaClient client, MockRestServiceServer server) {
    }

    private static class StaticBearerTokenService extends ExternalBearerTokenService {
        private final String tenantToken;
        private final String poaToken;

        StaticBearerTokenService(String token) {
            this(token, token);
        }

        StaticBearerTokenService(String tenantToken, String poaToken) {
            super(
                    new CybrillaPreVerificationProperties(),
                    new FinprimTenantProperties(),
                    null,
                    RestClient.builder()
            );
            this.tenantToken = tenantToken;
            this.poaToken = poaToken;
        }

        @Override
        public String getFinprimTenantAccessToken() {
            return tenantToken;
        }

        @Override
        public String getCybrillaPreVerificationAccessToken() {
            return poaToken;
        }
    }

    private static class RotatingBearerTokenService extends ExternalBearerTokenService {
        private int invalidations;

        RotatingBearerTokenService() {
            super(
                    new CybrillaPreVerificationProperties(),
                    new FinprimTenantProperties(),
                    null,
                    RestClient.builder()
            );
        }

        @Override
        public String getFinprimTenantAccessToken() {
            return invalidations == 0 ? "stale-token" : "fresh-token";
        }

        @Override
        public void invalidateFinprimTenantToken() {
            invalidations++;
        }
    }
}
