package com.platizio.wealthtech.integration.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
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

    @Test
    void zeroRefreshBufferReusesTokenUntilActualExpiry() throws Exception {
        AtomicInteger tokenRequests = new AtomicInteger();
        HttpServer server = createTokenServer(tokenRequests);
        server.start();

        try {
            CybrillaPreVerificationProperties cybrillaProperties = new CybrillaPreVerificationProperties();
            cybrillaProperties.getAuth().setTokenUrl("http://localhost:" + server.getAddress().getPort() + "/token");
            cybrillaProperties.getAuth().setClientId("client-id");
            cybrillaProperties.getAuth().setClientSecret("client-secret");
            cybrillaProperties.getAuth().setRefreshBufferSeconds(0);
            MutableClock clock = new MutableClock(Instant.parse("2026-05-13T00:00:00Z"));

            ExternalBearerTokenService tokenService = new ExternalBearerTokenService(
                    cybrillaProperties,
                    new FinprimTenantProperties(),
                    disabledCacheStore(),
                    RestClient.builder(),
                    clock
            );

            String firstToken = tokenService.getCybrillaPreVerificationAccessToken();
            clock.advance(Duration.ofMinutes(29));
            String secondToken = tokenService.getCybrillaPreVerificationAccessToken();
            clock.advance(Duration.ofMinutes(2));
            String thirdToken = tokenService.getCybrillaPreVerificationAccessToken();

            assertThat(firstToken).isEqualTo("test-token-1");
            assertThat(secondToken).isEqualTo("test-token-1");
            assertThat(thirdToken).isEqualTo("test-token-2");
            assertThat(tokenRequests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesJwtExpirationWhenAuthResponseOmitsExpiresIn() throws Exception {
        AtomicInteger tokenRequests = new AtomicInteger();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-13T00:00:00Z"));
        HttpServer server = createTokenServer(tokenRequests, requestNumber -> """
                {"access_token":"%s","token_type":"Bearer","scope":"partner"}
                """.formatted(jwt(clock.instant(), clock.instant().plus(Duration.ofHours(1)), requestNumber)));
        server.start();

        try {
            CybrillaPreVerificationProperties cybrillaProperties = new CybrillaPreVerificationProperties();
            cybrillaProperties.getAuth().setTokenUrl("http://localhost:" + server.getAddress().getPort() + "/token");
            cybrillaProperties.getAuth().setClientId("client-id");
            cybrillaProperties.getAuth().setClientSecret("client-secret");
            cybrillaProperties.getAuth().setRefreshBufferSeconds(0);

            ExternalBearerTokenService tokenService = new ExternalBearerTokenService(
                    cybrillaProperties,
                    new FinprimTenantProperties(),
                    disabledCacheStore(),
                    RestClient.builder(),
                    clock
            );

            String firstToken = tokenService.getCybrillaPreVerificationAccessToken();
            clock.advance(Duration.ofMinutes(31));
            String secondToken = tokenService.getCybrillaPreVerificationAccessToken();

            assertThat(secondToken).isEqualTo(firstToken);
            assertThat(tokenRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scheduledRefreshGeneratesTokenWhenNoCacheExists() throws Exception {
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

            boolean refreshed = tokenService.refreshCybrillaPreVerificationTokenIfCachedAndDue();

            assertThat(refreshed).isTrue();
            assertThat(tokenRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer createTokenServer(AtomicInteger tokenRequests) throws IOException {
        return createTokenServer(tokenRequests, requestNumber -> """
                {"access_token":"test-token-%d","token_type":"Bearer","expires_in":1800,"scope":"partner"}
                """.formatted(requestNumber));
    }

    private HttpServer createTokenServer(AtomicInteger tokenRequests, IntFunction<String> responseFactory) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/token", exchange -> {
            int requestNumber = tokenRequests.incrementAndGet();
            byte[] response = responseFactory.apply(requestNumber).getBytes(StandardCharsets.UTF_8);

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

    private String jwt(Instant issuedAt, Instant expiresAt, int requestNumber) {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("""
                {"iat":%d,"exp":%d,"request":%d}
                """.formatted(issuedAt.getEpochSecond(), expiresAt.getEpochSecond(), requestNumber));
        return header + "." + payload + ".signature";
    }

    private String base64Url(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
