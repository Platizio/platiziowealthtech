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
import com.platizio.wealthtech.integration.CybrillaUnavailableException;
import com.platizio.wealthtech.integration.auth.CybrillaPreVerificationProperties;
import com.platizio.wealthtech.integration.auth.ExternalBearerTokenService;
import com.platizio.wealthtech.integration.auth.FinprimTenantProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
    void fetchProductSchemesUsesPoaOrderableMfSchemePlansEndpoint() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));

        fixture.server.expect(once(), requestTo(
                        "https://finprim.test/v2/mf_scheme_plans/cybrillapoa?expand=mf_scheme,mf_fund&page=0&size=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andExpect(header("x-tenant-id", "tenant-123"))
                .andRespond(withSuccess("""
                        {
                          "mf_scheme_plans": [
                            {
                              "object": "mf_scheme_plan",
                              "gateway": "cybrillapoa",
                              "isin": "INF001",
                              "active": true,
                              "mf_scheme": { "name": "Alpha Liquid Fund" },
                              "mf_fund": { "name": "Alpha AMC" }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        fixture.server.expect(once(), requestTo(
                        "https://finprim.test/v2/sif_scheme_plans/cybrillapoa?expand=sif_scheme,sif_fund&page=0&size=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        CybrillaClient.SchemeFetchResult result = fixture.client.fetchProductSchemes();

        fixture.server.verify();
        assertThat(result.complete()).isTrue();
        assertThat(result.schemes()).hasSize(1);
        ProductScheme scheme = result.schemes().getFirst();
        assertThat(scheme.getSchemeName()).isEqualTo("Alpha Liquid Fund");
        assertThat(scheme.getAmcName()).isEqualTo("Alpha AMC");
        assertThat(scheme.getCategory()).isEqualTo(ProductCategory.MF);
        assertThat(scheme.getExternalSchemeCode()).isEqualTo("INF001");
        assertThat(scheme.getExternalIsin()).isEqualTo("INF001");
        assertThat(scheme.getActive()).isTrue();
    }

    @Test
    void fetchProductSchemesMergesSifSchemePlansFromFinprimGateway() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));

        fixture.server.expect(once(), requestTo(
                        "https://finprim.test/v2/mf_scheme_plans/cybrillapoa?expand=mf_scheme,mf_fund&page=0&size=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "mf_scheme_plans": [
                            {
                              "object": "mf_scheme_plan",
                              "gateway": "cybrillapoa",
                              "isin": "INF001",
                              "active": true,
                              "mf_scheme": { "name": "Alpha Liquid Fund" }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        fixture.server.expect(once(), requestTo(
                        "https://finprim.test/v2/sif_scheme_plans/cybrillapoa?expand=sif_scheme,sif_fund&page=0&size=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "data": [
                            {
                              "object": "sif_scheme_plan",
                              "gateway": "cybrillapoa",
                              "isin": "INF900000001",
                              "active": true,
                              "sif_scheme": { "name": "Impact Venture SIF" },
                              "sif_fund": { "name": "Impact Capital" }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        CybrillaClient.SchemeFetchResult result = fixture.client.fetchProductSchemes();

        fixture.server.verify();
        assertThat(result.complete()).isTrue();
        assertThat(result.schemes()).hasSize(2);
        assertThat(result.schemes())
                .filteredOn(scheme -> scheme.getCategory() == ProductCategory.SIF)
                .singleElement()
                .satisfies(scheme -> {
                    assertThat(scheme.getSchemeName()).isEqualTo("Impact Venture SIF");
                    assertThat(scheme.getAmcName()).isEqualTo("Impact Capital");
                    assertThat(scheme.getExternalIsin()).isEqualTo("INF900000001");
                    assertThat(scheme.getProductType()).isEqualTo("SIF");
                });
    }

    @Test
    void updateInvestorProfilePatchesOnlyMissingProfileFields() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        Investor investor = investor("invp_1", null);
        investor.setFullName("Rani Gupta");
        investor.setDateOfBirth(LocalDate.of(1955, 10, 25));
        investor.setCity("Mumbai");

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/investor_profiles/invp_1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "object": "investor_profile",
                          "id": "invp_1",
                          "name": "Rani Gupta",
                          "occupation": "service"
                        }
                        """, MediaType.APPLICATION_JSON));

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/investor_profiles"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andExpect(header("x-tenant-id", "tenant-123"))
                .andExpect(content().json("""
                        {
                          "id": "invp_1",
                          "tax_status": "resident_individual",
                          "date_of_birth": "1955-10-25",
                          "place_of_birth": "Mumbai",
                          "nationality_country": "IN",
                          "source_of_wealth": "salary",
                          "income_slab": "upto_1lakh",
                          "pep_details": "not_applicable"
                        }
                        """))
                .andRespond(withSuccess("{\"object\":\"investor_profile\",\"id\":\"invp_1\"}", MediaType.APPLICATION_JSON));

        fixture.client.updateInvestorProfile(investor);

        fixture.server.verify();
    }

    @Test
    void ensureInvestorProfileOrderReadySkipsOccupationWhenAlreadySetOnProfile() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        Investor investor = investor("invp_1", null);

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/investor_profiles/invp_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andRespond(withSuccess("""
                        {
                          "object": "investor_profile",
                          "id": "invp_1",
                          "gender": "female",
                          "country_of_birth": "IN",
                          "place_of_birth": "Mumbai",
                          "nationality_country": "IN",
                          "use_default_tax_residences": true,
                          "occupation": "service",
                          "source_of_wealth": "salary",
                          "income_slab": "upto_1lakh",
                          "pep_details": "not_applicable"
                        }
                        """, MediaType.APPLICATION_JSON));

        fixture.client.ensureInvestorProfileOrderReady(investor);

        fixture.server.verify();
    }

    @Test
    void ensureInvestorProfileOrderReadyRetriesWithoutImmutableOccupationField() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        Investor investor = investor("invp_1", null);

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/investor_profiles/invp_1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "object": "investor_profile",
                          "id": "invp_1"
                        }
                        """, MediaType.APPLICATION_JSON));

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/investor_profiles"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""
                        {
                          "id": "invp_1",
                          "source_of_wealth": "salary",
                          "income_slab": "upto_1lakh",
                          "pep_details": "not_applicable"
                        }
                        """))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""
                        {
                          "error": {
                            "status": 400,
                            "message": "source_of_wealth is already set and cannot be modified"
                          }
                        }
                        """));

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/investor_profiles"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""
                        {
                          "id": "invp_1",
                          "income_slab": "upto_1lakh",
                          "pep_details": "not_applicable"
                        }
                        """))
                .andRespond(withSuccess("{\"object\":\"investor_profile\",\"id\":\"invp_1\"}", MediaType.APPLICATION_JSON));

        fixture.client.ensureInvestorProfileOrderReady(investor);

        fixture.server.verify();
    }

    @Test
    void ensureInvestorProfileOrderReadyNeverPatchesOccupationOnExistingProfile() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        Investor investor = investor("invp_1", null);

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/investor_profiles/invp_1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "object": "investor_profile",
                          "id": "invp_1",
                          "pan": "AAAPX1234A",
                          "name": "Alice Investor"
                        }
                        """, MediaType.APPLICATION_JSON));

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/investor_profiles"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""
                        {
                          "id": "invp_1",
                          "source_of_wealth": "salary",
                          "income_slab": "upto_1lakh",
                          "pep_details": "not_applicable"
                        }
                        """))
                .andRespond(withSuccess("{\"object\":\"investor_profile\",\"id\":\"invp_1\"}", MediaType.APPLICATION_JSON));

        fixture.client.ensureInvestorProfileOrderReady(investor);

        fixture.server.verify();
    }

    @Test
    void ensureInvestorProfileOrderReadyRetriesWhenBadRequestUsesHttpStatusCodeNotEnum() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        Investor investor = investor("invp_1", null);

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/investor_profiles/invp_1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"object\":\"investor_profile\",\"id\":\"invp_1\"}", MediaType.APPLICATION_JSON));

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/investor_profiles"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(400)).body("""
                        {
                          "error": {
                            "status": 400,
                            "code": "BAD_REQUEST_ERROR",
                            "message": "income_slab is already set and cannot be modified",
                            "errors": null
                          }
                        }
                        """));
        fixture.server.expect(once(), requestTo("https://finprim.test/v2/investor_profiles"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""
                        {
                          "id": "invp_1",
                          "source_of_wealth": "salary",
                          "pep_details": "not_applicable"
                        }
                        """))
                .andRespond(withSuccess("{\"object\":\"investor_profile\",\"id\":\"invp_1\"}", MediaType.APPLICATION_JSON));

        fixture.client.ensureInvestorProfileOrderReady(investor);

        fixture.server.verify();
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
    void createEsignPostsToEsignsEndpoint() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/esigns"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andExpect(header("x-tenant-id", "tenant-123"))
                .andExpect(content().json("""
                        {
                          "kyc_request": "kycr_1",
                          "postback_url": "https://app.example/kyc/esign"
                        }
                        """))
                .andRespond(withSuccess(
                        "{\"id\":\"esign_1\",\"status\":\"pending\",\"redirect_url\":\"https://s.finprim.com/v2/esigns/esign_1/redirect\"}",
                        MediaType.APPLICATION_JSON
                ));

        JsonNode response = fixture.client.createEsign(Map.of(
                "kyc_request", "kycr_1",
                "postback_url", "https://app.example/kyc/esign"
        ));

        fixture.server.verify();
        assertThat(response.path("id").asText()).isEqualTo("esign_1");
        assertThat(response.path("status").asText()).isEqualTo("pending");
    }

    @Test
    void fetchEsignGetsEsignById() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/esigns/esign_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andExpect(header("x-tenant-id", "tenant-123"))
                .andRespond(withSuccess(
                        "{\"id\":\"esign_1\",\"status\":\"successful\",\"redirect_url\":\"https://s.finprim.com/v2/esigns/esign_1/redirect\"}",
                        MediaType.APPLICATION_JSON
                ));

        JsonNode response = fixture.client.fetchEsign("esign_1");

        fixture.server.verify();
        assertThat(response.path("id").asText()).isEqualTo("esign_1");
        assertThat(response.path("status").asText()).isEqualTo("successful");
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
                          "amount": 1500.50,
                          "user_ip": "127.0.0.1",
                          "gateway": "ondc",
                          "initiated_via": "web"
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
                          "installment_day": 1,
                          "number_of_installments": 12,
                          "user_ip": "127.0.0.1",
                          "gateway": "ondc"
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
                          "units": 10.25,
                          "gateway": "ondc"
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
                new SimpleMeterRegistry(),
                new NoopExternalApiSnapshotService()
        );

        assertThatThrownBy(() -> client.createOrder(order(TransactionType.LUMPSUM_PURCHASE), investor("profile-1", "mfia-1"), productScheme("INF209KA1K47")))
                .isInstanceOf(CybrillaUnavailableException.class)
                .hasMessageContaining("Unable to reach the external investment platform to create order")
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

        // createMfInvestmentAccount first lists existing accounts by primary_investor, then POSTs.
        // Mock the list call (empty) so the subsequent POST is what the test asserts.
        fixture.server.expect(once(), requestTo("https://finprim.test/v2/mf_investment_accounts?primary_investor=profile-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
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
    void createMfInvestmentAccountResolvesDuplicateWhenListOmitsPrimaryInvestor() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));
        Investor investor = investor("invp_existing_profile", null);

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/mf_investment_accounts?primary_investor=invp_existing_profile"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo("https://finprim.test/v2/mf_investment_accounts"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""
                        {"error":{"message":"investment account already present for given investor/holding_pattern combination"}}
                        """));
        fixture.server.expect(once(), requestTo("https://finprim.test/v2/mf_investment_accounts?primary_investor=invp_existing_profile"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"mfia_existing_99\"}]}", MediaType.APPLICATION_JSON));

        String accountId = fixture.client.createMfInvestmentAccount(investor);

        fixture.server.verify();
        assertThat(accountId).isEqualTo("mfia_existing_99");
    }

    @Test
    void captureBankAccountCreatesBankAccountAndPoaPreVerificationRequest() {
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
        fixture.server.expect(once(), requestTo("https://poa.test/poa/pre_verifications"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "pan": {
                            "value": "AAAPX1234A"
                          },
                          "name": {
                            "value": "Alice Investor"
                          },
                          "bank_accounts": [
                            {
                              "value": {
                                "account_number": "98123459204",
                                "ifsc_code": "HDFC0001330",
                                "account_type": "savings"
                              }
                            }
                          ]
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "object": "pre_verification",
                          "id": "pv_bank_1",
                          "status": "accepted",
                          "bank_accounts": [
                            {
                              "status": null,
                              "code": null,
                              "reason": null
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        fixture.client.captureBankAccount(investor, bankAccount);

        fixture.server.verify();
        assertThat(bankAccount.getCybrillaBankId()).isEqualTo("bac_1");
        assertThat(bankAccount.getCybrillaBankVerificationId()).isEqualTo("pv_bank_1");
        assertThat(bankAccount.getCybrillaBankVerificationStatus()).isEqualTo("accepted");
    }

    @Test
    void fetchIfscDetailsUsesFinprimOnboardingLookup() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));

        fixture.server.expect(once(), requestTo("https://finprim.test/api/onb/ifsc_codes/HDFC0001330"))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andExpect(header("x-tenant-id", "tenant-123"))
                .andRespond(withSuccess("""
                        {
                          "ifsc_code": "HDFC0001330",
                          "bank_name": "HDFC Bank",
                          "branch_name": "MG Road",
                          "branch_address": "MG Road Branch",
                          "city": "Bengaluru",
                          "district": "Bengaluru Urban",
                          "state": "Karnataka",
                          "micr_code": "560240002"
                        }
                        """, MediaType.APPLICATION_JSON));

        IfscLookupResult result = fixture.client.fetchIfscDetails("hdfc0001330");

        fixture.server.verify();
        assertThat(result.ifscCode()).isEqualTo("HDFC0001330");
        assertThat(result.bankName()).isEqualTo("HDFC Bank");
        assertThat(result.branchName()).isEqualTo("MG Road");
    }

    @Test
    void fetchPincodeDetailsSanitizesCorruptedSandboxCityLabels() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));

        fixture.server.expect(once(), requestTo("https://finprim.test/api/onb/pincodes/400001"))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andExpect(header("x-tenant-id", "tenant-123"))
                .andRespond(withSuccess("""
                        {
                          "code": "400001",
                          "city": "Mumbai%00$##$$@#$T^&*&^%$#",
                          "district": "Mumbai",
                          "state_name": "MAHARASHTRA",
                          "country_ansi_code": "IN",
                          "cities": ["Mumbai%00$##$$@#$T^&*&^%$#"]
                        }
                        """, MediaType.APPLICATION_JSON));

        PincodeLookupResult result = fixture.client.fetchPincodeDetails("400001");

        fixture.server.verify();
        assertThat(result.city()).isEqualTo("Mumbai");
        assertThat(result.district()).isEqualTo("Mumbai");
        assertThat(result.stateName()).isEqualTo("Maharashtra");
        assertThat(result.cities()).containsExactly("Mumbai");
    }

    @Test
    void fetchPincodeDetailsUsesFinprimOnboardingLookup() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));

        fixture.server.expect(once(), requestTo("https://finprim.test/api/onb/pincodes/560102"))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andExpect(header("x-tenant-id", "tenant-123"))
                .andRespond(withSuccess("""
                        {
                          "code": "560102",
                          "city": "Bangalore South",
                          "district": "Bangalore",
                          "state_name": "Karnataka",
                          "country_ansi_code": "IN"
                        }
                        """, MediaType.APPLICATION_JSON));

        PincodeLookupResult result = fixture.client.fetchPincodeDetails("560102");

        fixture.server.verify();
        assertThat(result.code()).isEqualTo("560102");
        assertThat(result.city()).isEqualTo("Bangalore South");
        assertThat(result.stateName()).isEqualTo("Karnataka");
        assertThat(result.cities()).containsExactly("Bangalore South");
    }

    @Test
    void confirmPurchaseFlowUsesConsentPaymentAndConfirmEndpoints() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/mf_purchases"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess("""
                        {"object":"mf_purchase","id":"mfp_1","state":"pending"}
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo("https://finprim.test/api/pg/payments/netbanking"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"id":9,"token_url":"https://payments.fp/token"}
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo("https://finprim.test/v2/mf_purchases"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess("""
                        {"object":"mf_purchase","id":"mfp_1","state":"submitted"}
                        """, MediaType.APPLICATION_JSON));

        fixture.client.updateMfPurchaseConsent("mfp_1", Map.of("email", "investor@example.com"));
        JsonNode payment = fixture.client.createNetbankingPayment(
                List.of(77), "http://localhost/postback", "NETBANKING", 417, "ONDC");
        JsonNode confirmed = fixture.client.confirmMfPurchase("mfp_1");

        fixture.server.verify();
        assertThat(payment.path("token_url").asText()).isEqualTo("https://payments.fp/token");
        assertThat(confirmed.path("state").asText()).isEqualTo("submitted");
    }

    @Test
    void createUpiUriPaymentSendsCustomCheckoutPayload() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));

        fixture.server.expect(once(), requestTo("https://finprim.test/api/pg/payments/netbanking"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "amc_order_ids": [77],
                          "payment_postback_url": "http://localhost/postback",
                          "method": "UPI",
                          "bank_account_id": 417,
                          "upi": {"type": "uri"},
                          "provider_name": "ONDC"
                        }
                        """))
                .andRespond(withSuccess("""
                        {"id":9,"token_url":null,"upi":{"type":"uri","uri":null}}
                        """, MediaType.APPLICATION_JSON));

        JsonNode payment = fixture.client.createUpiUriPayment(
                List.of(77), "http://localhost/postback", 417, "ONDC");

        fixture.server.verify();
        assertThat(payment.path("id").asInt()).isEqualTo(9);
        assertThat(payment.path("upi").path("type").asText()).isEqualTo("uri");
    }

    @Test
    void pollBankAccountVerificationThrowsRetryableWhenInterruptedWhilePending() {
        ClientFixture fixture = clientFixture(new StaticBearerTokenService("tenant-token"));

        fixture.server.expect(once(), requestTo("https://finprim.test/v2/bank_account_verifications/bav_1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"bav_1\",\"status\":\"pending\"}", MediaType.APPLICATION_JSON));

        // Pre-set the interrupt flag so the first Thread.sleep in the poll loop throws
        // InterruptedException immediately (no real delay).
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                    fixture.client, "pollFpBankAccountVerificationUntilSettled", "bav_1"))
                    .isInstanceOf(CybrillaUnavailableException.class)
                    .hasMessageContaining("Interrupted")
                    .hasMessageContaining("bav_1");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Clear the interrupt flag so it does not leak into other tests.
            Thread.interrupted();
        }

        fixture.server.verify();
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
                new SimpleMeterRegistry(),
                new NoopExternalApiSnapshotService()
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
        investor.setFullName("Alice Investor");
        investor.setPan("AAAPX1234A");
        investor.setDateOfBirth(LocalDate.of(1990, 1, 1));
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
