package com.platizio.wealthtech.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.integration.auth.ExternalBearerTokenService;
import com.platizio.wealthtech.integration.auth.CybrillaPreVerificationProperties;
import com.platizio.wealthtech.integration.auth.FinprimTenantProperties;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RealCybrillaClientMetricsTest {

    @Test
    void postRequestsRecordCybrillaApiTimerWithOperationTag() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ExternalBearerTokenService tokenService = new TestBearerTokenService();

        FinprimTenantProperties properties = new FinprimTenantProperties();
        properties.setBaseUrl("https://finprim.test");

        RealCybrillaClient client = new RealCybrillaClient(
                restClientBuilder,
                tokenService,
                properties,
                new CybrillaPreVerificationProperties(),
                meterRegistry,
                new NoopExternalApiSnapshotService()
        );

        server.expect(once(), requestTo("https://finprim.test/v2/investor_profiles"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"profile-1\"}", MediaType.APPLICATION_JSON));

        String profileId = client.createInvestorProfile(investor());

        server.verify();
        assertThat(profileId).isEqualTo("profile-1");
        Timer timer = meterRegistry
                .find("cybrilla.api.request")
                .tag("operation", "create_investor_profile")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    private Investor investor() {
        Investor investor = new Investor();
        investor.setFullName("Ada Investor");
        investor.setPan("ABCDE1234F");
        return investor;
    }

    private static class TestBearerTokenService extends ExternalBearerTokenService {
        TestBearerTokenService() {
            super(
                    new CybrillaPreVerificationProperties(),
                    new FinprimTenantProperties(),
                    null,
                    RestClient.builder()
            );
        }

        @Override
        public String getFinprimTenantAccessToken() {
            return "tenant-token";
        }
    }
}
