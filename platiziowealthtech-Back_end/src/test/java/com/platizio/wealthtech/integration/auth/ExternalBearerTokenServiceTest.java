package com.platizio.wealthtech.integration.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import java.nio.file.Path;

class ExternalBearerTokenServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void reusesCachedPreVerificationTokenUntilRefreshWindow() throws Exception {
        AtomicInteger tokenRequests = new AtomicInteger();
        HttpServer server = createTokenServer(tokenRequests);
        server.start();

        try {
            CybrillaPreVerificationProperties cybrillaProperties = new CybrillaPreVerificationProperties();
            cybrillaProperties.getAuth().setTokenUrl("http://localhost:" + server.getAddress().getPort() + "/token");
            cybrillaProperties.getAuth().setClientId("client-id");
            cybrillaProperties.getAuth().setClientSecret("client-secret");

            ExternalBearerTokenService tokenService = new ExternalBearerTokenService(
                    cybrillaProperties,
                    new FinprimTenantProperties(),
                    disabledCacheStore(),
                    RestClient.builder(),
                    Clock.fixed(Instant.parse("2026-05-13T00:00:00Z"), ZoneOffset.UTC)
            );

            String firstToken = tokenService.getCybrillaPreVerificationAccessToken();
            String secondToken = tokenService.getCybrillaPreVerificationAccessToken();

            assertThat(firstToken).isEqualTo("test-token-1");
            assertThat(secondToken).isEqualTo("test-token-1");
            assertThat(tokenRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reusesStoredTokenAfterRestartWhenStillInsideRefreshWindow() throws Exception {
        AtomicInteger tokenRequests = new AtomicInteger();
        HttpServer server = createTokenServer(tokenRequests);
        server.start();

        try {
            CybrillaPreVerificationProperties cybrillaProperties = new CybrillaPreVerificationProperties();
            cybrillaProperties.getAuth().setTokenUrl("http://localhost:" + server.getAddress().getPort() + "/token");
            cybrillaProperties.getAuth().setClientId("client-id");
            cybrillaProperties.getAuth().setClientSecret("client-secret");

            String cacheFile = tempDir.resolve("token-cache.json").toString();
            Clock fixedClock = Clock.fixed(Instant.parse("2026-05-13T00:00:00Z"), ZoneOffset.UTC);

            ExternalBearerTokenService firstRun = new ExternalBearerTokenService(
                    cybrillaProperties,
                    new FinprimTenantProperties(),
                    cacheStore(cacheFile),
                    RestClient.builder(),
                    fixedClock
            );
            String firstToken = firstRun.getCybrillaPreVerificationAccessToken();

            ExternalBearerTokenService secondRun = new ExternalBearerTokenService(
                    cybrillaProperties,
                    new FinprimTenantProperties(),
                    cacheStore(cacheFile),
                    RestClient.builder(),
                    fixedClock
            );
            String secondToken = secondRun.getCybrillaPreVerificationAccessToken();

            assertThat(firstToken).isEqualTo("test-token-1");
            assertThat(secondToken).isEqualTo("test-token-1");
            assertThat(tokenRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer createTokenServer(AtomicInteger tokenRequests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/token", exchange -> {
            int requestNumber = tokenRequests.incrementAndGet();
            byte[] response = """
                    {"access_token":"test-token-%d","token_type":"Bearer","expires_in":1800,"scope":"partner"}
                    """.formatted(requestNumber).getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return server;
    }

    private ExternalAuthTokenCacheStore disabledCacheStore() {
        return new ExternalAuthTokenCacheStore(new ObjectMapper(), false, tempDir.resolve("disabled.json").toString(), false);
    }

    private ExternalAuthTokenCacheStore cacheStore(String cacheFile) {
        return new ExternalAuthTokenCacheStore(new ObjectMapper(), true, cacheFile, false);
    }
}
