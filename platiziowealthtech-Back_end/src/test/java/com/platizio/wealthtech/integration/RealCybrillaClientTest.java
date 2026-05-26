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
    void createKycCheckPostsPanAndDateOfBirth() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));

        fixture.server.expect(once(), requestTo("https://finprim.test/api/kyc/check"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andExpect(header("x-tenant-id", "tenant-123"))
                .andExpect(content().json("""
                        {
                          "pan": "AAAPA3751A",
                          "date_of_birth": "1955-10-25"
                        }
                        """))
                .andRespond(withSuccess("{\"id\":\"kyc-check-1\",\"status\":true}", MediaType.APPLICATION_JSON));

        JsonNode response = fixture.client.createKycCheck("aaapa3751a", LocalDate.of(1955, 10, 25));

        fixture.server.verify();
        assertThat(response.path("id").asText()).isEqualTo("kyc-check-1");
        assertThat(response.path("status").asBoolean()).isTrue();
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
    void createOrderPostsToOrdersEndpointWithPayloadAndIdempotencyKey() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        TransactionOrder order = order(TransactionType.LUMPSUM_PURCHASE);
        Investor investor = investor("profile-1");

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andExpect(header("x-tenant-id", "tenant-123"))
                .andExpect(header("Idempotency-Key", "order-" + order.getId()))
                .andExpect(content().json("""
                        {
                          "source_ref_id": "%s",
                          "investor_profile": "profile-1",
                          "investor_id": "%s",
                          "distributor_id": "%s",
                          "scheme": "%s",
                          "type": "purchase",
                          "amount": 1500.50,
                          "payment_mode": "NET_BANKING"
                        }
                        """.formatted(
                        order.getId(),
                        order.getInvestorId(),
                        order.getDistributorId(),
                        order.getProductSchemeId()
                )))
                .andRespond(withSuccess("{\"id\":\"fp-order-1\"}", MediaType.APPLICATION_JSON));

        String externalOrderId = fixture.client.createOrder(order, investor);

        fixture.server.verify();
        assertThat(externalOrderId).isEqualTo("fp-order-1");
    }

    @Test
    void createRedemptionPostsRedemptionOrderPayload() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        TransactionOrder order = order(TransactionType.REDEMPTION);
        order.setExternalOrderId("fp-order-1");
        order.setAmount(null);
        order.setUnits(new BigDecimal("10.25"));

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "redemption-" + order.getId()))
                .andExpect(content().json("""
                        {
                          "source_ref_id": "redemption-%s",
                          "source_order_id": "fp-order-1",
                          "investor_id": "%s",
                          "scheme": "%s",
                          "type": "redemption",
                          "units": 10.25
                        }
                        """.formatted(
                        order.getId(),
                        order.getInvestorId(),
                        order.getProductSchemeId()
                )))
                .andRespond(withSuccess("{\"id\":\"fp-redemption-1\"}", MediaType.APPLICATION_JSON));

        String externalRedemptionId = fixture.client.createRedemption(order);

        fixture.server.verify();
        assertThat(externalRedemptionId).isEqualTo("fp-redemption-1");
    }

    @Test
    void nonSuccessfulOrderResponseIsWrappedInCybrillaApiException() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        TransactionOrder order = order(TransactionType.LUMPSUM_PURCHASE);

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/orders"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"upstream failed\"}"));

        assertThatThrownBy(() -> fixture.client.createOrder(order, investor("profile-1")))
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
                new SimpleMeterRegistry()
        );

        assertThatThrownBy(() -> client.createOrder(order(TransactionType.LUMPSUM_PURCHASE), investor("profile-1")))
                .isInstanceOf(CybrillaApiException.class)
                .hasMessageContaining("Unable to create order with Fintech Primitives")
                .hasMessageContaining("DNS failure");
    }

    @Test
    void unauthorizedOrderRequestInvalidatesTokenAndRetriesOnce() {
        RotatingBearerTokenService tokenService = new RotatingBearerTokenService();
        ClientFixture fixture = clientFixture(tokenService);
        TransactionOrder order = order(TransactionType.LUMPSUM_PURCHASE);

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
                .andExpect(header("Idempotency-Key", "order-" + order.getId()))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"expired token\"}"));
        fixture.server.expect(once(), requestTo("https://finprim.test/v2/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh-token"))
                .andExpect(header("Idempotency-Key", "order-" + order.getId()))
                .andRespond(withSuccess("{\"id\":\"fp-order-2\"}", MediaType.APPLICATION_JSON));

        String externalOrderId = fixture.client.createOrder(order, investor("profile-1"));

        fixture.server.verify();
        assertThat(externalOrderId).isEqualTo("fp-order-2");
        assertThat(tokenService.invalidations).isEqualTo(1);
    }

    private ClientFixture clientFixture(ExternalBearerTokenService tokenService) {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        FinprimTenantProperties properties = new FinprimTenantProperties();
        properties.setBaseUrl("https://finprim.test");
        properties.getTenant().setId("tenant-123");

        RealCybrillaClient client = new RealCybrillaClient(
                restClientBuilder,
                tokenService,
                properties,
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

    private Investor investor(String profileId) {
        Investor investor = new Investor();
        investor.setCybrillaInvestorId(profileId);
        return investor;
    }

    private record ClientFixture(RealCybrillaClient client, MockRestServiceServer server) {
    }

    private static class StaticBearerTokenService extends ExternalBearerTokenService {
        private final String token;

        StaticBearerTokenService(String token) {
            super(
                    new CybrillaPreVerificationProperties(),
                    new FinprimTenantProperties(),
                    null,
                    RestClient.builder()
            );
            this.token = token;
        }

        @Override
        public String getFinprimTenantAccessToken() {
            return token;
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
