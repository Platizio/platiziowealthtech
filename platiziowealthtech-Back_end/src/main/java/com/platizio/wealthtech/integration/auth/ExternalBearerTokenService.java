package com.platizio.wealthtech.integration.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class ExternalBearerTokenService {

    private static final Logger logger = LoggerFactory.getLogger(ExternalBearerTokenService.class);
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 1800;
    private static final JwtTimeClaims NO_JWT_TIME_CLAIMS = new JwtTimeClaims(Optional.empty(), Optional.empty());

    private final CybrillaPreVerificationProperties cybrillaPreVerificationProperties;
    private final FinprimTenantProperties finprimTenantProperties;
    private final ExternalAuthTokenCacheStore tokenCacheStore;
    private final RestClient authClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock;
    @Value("${external-auth.debug.log-raw-tokens:false}")
    private boolean logRawTokens;
    private final Map<TokenAudience, CachedBearerToken> tokenCache = new ConcurrentHashMap<>();
    private final Map<TokenAudience, Object> refreshLocks = Map.of(
            TokenAudience.CYBRILLA_PRE_VERIFICATION, new Object(),
            TokenAudience.FINPRIM_TENANT, new Object()
    );

    @Autowired
    public ExternalBearerTokenService(
            CybrillaPreVerificationProperties cybrillaPreVerificationProperties,
            FinprimTenantProperties finprimTenantProperties,
            ExternalAuthTokenCacheStore tokenCacheStore,
            RestClient.Builder restClientBuilder
    ) {
        this(cybrillaPreVerificationProperties, finprimTenantProperties, tokenCacheStore, restClientBuilder, Clock.systemUTC());
    }

    ExternalBearerTokenService(
            CybrillaPreVerificationProperties cybrillaPreVerificationProperties,
            FinprimTenantProperties finprimTenantProperties,
            ExternalAuthTokenCacheStore tokenCacheStore,
            RestClient.Builder restClientBuilder,
            Clock clock
    ) {
        this.cybrillaPreVerificationProperties = cybrillaPreVerificationProperties;
        this.finprimTenantProperties = finprimTenantProperties;
        this.tokenCacheStore = tokenCacheStore;
        this.authClient = restClientBuilder.build();
        this.clock = clock;
    }

    public String getCybrillaPreVerificationAccessToken() {
        return getAccessToken(
                TokenAudience.CYBRILLA_PRE_VERIFICATION,
                cybrillaPreVerificationProperties.credentials()
        );
    }

    public String getFinprimTenantAccessToken() {
        return getAccessToken(
                TokenAudience.FINPRIM_TENANT,
                finprimTenantProperties.credentials()
        );
    }

    public void invalidateCybrillaPreVerificationToken() {
        tokenCache.remove(TokenAudience.CYBRILLA_PRE_VERIFICATION);
        tokenCacheStore.remove(TokenAudience.CYBRILLA_PRE_VERIFICATION.cacheKey());
    }

    public void invalidateFinprimTenantToken() {
        tokenCache.remove(TokenAudience.FINPRIM_TENANT);
        tokenCacheStore.remove(TokenAudience.FINPRIM_TENANT.cacheKey());
    }

    public boolean refreshCybrillaPreVerificationTokenIfCachedAndDue() {
        return refreshTokenIfCachedAndDue(
                TokenAudience.CYBRILLA_PRE_VERIFICATION,
                cybrillaPreVerificationProperties.credentials()
        );
    }

    public boolean refreshFinprimTenantTokenIfCachedAndDue() {
        return refreshTokenIfCachedAndDue(
                TokenAudience.FINPRIM_TENANT,
                finprimTenantProperties.credentials()
        );
    }

    private String getAccessToken(TokenAudience audience, OAuthClientCredentials credentials) {
        CachedBearerToken cachedToken = tokenCache.get(audience);
        if (cachedToken != null && cachedToken.isUsable(clock)) {
            logTokenState(audience, cachedToken, "cached");
            return cachedToken.value();
        }

        synchronized (refreshLocks.get(audience)) {
            cachedToken = tokenCache.get(audience);
            if (cachedToken != null && cachedToken.isUsable(clock)) {
                logTokenState(audience, cachedToken, "cached");
                return cachedToken.value();
            }

            cachedToken = loadStoredToken(audience);
            if (cachedToken != null && cachedToken.isUsable(clock)) {
                tokenCache.put(audience, cachedToken);
                logTokenState(audience, cachedToken, "cached_after_restart");
                return cachedToken.value();
            }
            if (cachedToken != null) {
                logger.info(
                        "external_auth_cache status='expired_or_refresh_due' provider='{}' new_token_in='0 mins'",
                        audience.label()
                );
            }

            return fetchAndCacheLatestToken(audience, credentials).value();
        }
    }

    private boolean refreshTokenIfCachedAndDue(TokenAudience audience, OAuthClientCredentials credentials) {
        synchronized (refreshLocks.get(audience)) {
            CachedBearerToken cachedToken = tokenCache.get(audience);
            if (cachedToken != null && cachedToken.isUsable(clock)) {
                logTokenState(audience, cachedToken, "cached");
                return false;
            }

            CachedBearerToken storedToken = loadStoredToken(audience);
            if (cachedToken == null && storedToken == null) {
                logger.debug(
                        "external_auth_cache status='skip_refresh' provider='{}' reason='no_cached_token_yet'",
                        audience.label()
                );
                return false;
            }
            if (storedToken != null && storedToken.isUsable(clock)) {
                tokenCache.put(audience, storedToken);
                logTokenState(audience, storedToken, "cached_after_restart");
                return false;
            }

            fetchAndCacheLatestToken(audience, credentials);
            return true;
        }
    }

    private CachedBearerToken loadStoredToken(TokenAudience audience) {
        return tokenCacheStore.load(audience.cacheKey())
                .map(token -> new CachedBearerToken(
                        token.value(),
                        jwtTimeClaims(token.value()).issuedAt().orElse(Instant.EPOCH),
                        token.refreshAt(),
                        token.expiresAt()
                ))
                .orElse(null);
    }

    private CachedBearerToken fetchAndCacheLatestToken(TokenAudience audience, OAuthClientCredentials credentials) {
        CachedBearerToken freshToken = fetchToken(audience, credentials);
        CachedBearerToken storedAfterFetch = loadStoredToken(audience);
        if (storedAfterFetch != null
                && storedAfterFetch.isUsable(clock)
                && storedAfterFetch.isIssuedAfter(freshToken)) {
            tokenCache.put(audience, storedAfterFetch);
            logTokenState(audience, storedAfterFetch, "cached_newer_token");
            return storedAfterFetch;
        }

        tokenCache.put(audience, freshToken);
        tokenCacheStore.save(audience.cacheKey(), freshToken.toStoredToken());
        logTokenState(audience, freshToken, "refreshed");
        return freshToken;
    }

    private CachedBearerToken fetchToken(TokenAudience audience, OAuthClientCredentials credentials) {
        credentials.validate(audience.label());
        logger.info(
                "external_auth_request provider='{}' token_url='{}' client_id='{}' client_secret_configured='{}'",
                audience.label(),
                credentials.tokenUrl(),
                maskClientId(credentials.clientId()),
                credentials.clientSecret() != null && !credentials.clientSecret().isBlank()
        );

        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", credentials.clientId());
        form.add("client_secret", credentials.clientSecret());
        form.add("grant_type", "client_credentials");

        OAuthTokenResponse response;
        try {
            response = authClient.post()
                    .uri(credentials.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(OAuthTokenResponse.class);
        } catch (RestClientResponseException ex) {
            throw new ExternalApiAuthenticationException(
                    "Unable to authenticate with " + audience.label() + ": " + ex.getStatusCode(),
                    ex
            );
        } catch (RuntimeException ex) {
            throw new ExternalApiAuthenticationException(
                    "Unable to authenticate with " + audience.label(),
                    ex
            );
        }

        if (response == null || !StringUtils.hasText(response.accessToken())) {
            throw new ExternalApiAuthenticationException(
                    "Authentication response from " + audience.label() + " did not include an access token"
            );
        }

        Instant receivedAt = Instant.now(clock);
        JwtTimeClaims jwtTimeClaims = jwtTimeClaims(response.accessToken());
        Instant issuedAt = jwtTimeClaims.issuedAt().orElse(receivedAt);
        long expiresInSeconds = response.expiresIn() != null && response.expiresIn() > 0
                ? response.expiresIn()
                : DEFAULT_EXPIRES_IN_SECONDS;
        Instant responseExpiresAt = receivedAt.plusSeconds(expiresInSeconds);
        Instant expiresAt = jwtTimeClaims.expiresAt()
                .filter(expiry -> expiry.isAfter(receivedAt))
                .orElse(responseExpiresAt);
        if (response.expiresIn() != null && response.expiresIn() > 0 && responseExpiresAt.isBefore(expiresAt)) {
            expiresAt = responseExpiresAt;
        }
        if (!expiresAt.isAfter(receivedAt)) {
            throw new ExternalApiAuthenticationException(
                    "Authentication response from " + audience.label() + " included an already-expired access token"
            );
        }
        Instant refreshAt = calculateRefreshAt(receivedAt, expiresAt, credentials.safeRefreshBuffer());

        return new CachedBearerToken(response.accessToken(), issuedAt, refreshAt, expiresAt);
    }

    private void logTokenState(TokenAudience audience, CachedBearerToken token, String source) {
        Instant now = Instant.now(clock);
        String visibleToken = logRawTokens ? token.value() : token.fingerprint();
        String message = "external_auth provider='{}' status='{}' token='{}' refresh_in='{} mins' expires_in='{} mins'";
        Object[] args = {
                audience.label(),
                source,
                visibleToken,
                minutesUntil(now, token.refreshAt()),
                minutesUntil(now, token.expiresAt())
        };
        if (isOAuthRefreshLogSource(source)) {
            logger.info(message, args);
        } else if (logger.isDebugEnabled()) {
            logger.debug(message, args);
        }
    }

    private static boolean isOAuthRefreshLogSource(String source) {
        return "refreshed".equals(source)
                || "cached_newer_token".equals(source)
                || "cached_after_restart".equals(source);
    }

    private long minutesUntil(Instant now, Instant instant) {
        long seconds = Duration.between(now, instant).getSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return (seconds + 59) / 60;
    }

    private String maskClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return "<missing>";
        }
        if (clientId.length() <= 8) {
            return clientId.charAt(0) + "***" + clientId.charAt(clientId.length() - 1);
        }
        return clientId.substring(0, 4) + "..." + clientId.substring(clientId.length() - 4);
    }

    private Instant calculateRefreshAt(Instant issuedAt, Instant expiresAt, Duration refreshBuffer) {
        Instant refreshAt = expiresAt.minus(refreshBuffer);
        if (refreshAt.isAfter(issuedAt)) {
            return refreshAt;
        }

        long secondsUntilExpiry = Duration.between(issuedAt, expiresAt).getSeconds();
        long halfLifeSeconds = Math.max(1, secondsUntilExpiry / 2);
        return issuedAt.plusSeconds(halfLifeSeconds);
    }

    private JwtTimeClaims jwtTimeClaims(String token) {
        if (!StringUtils.hasText(token)) {
            return NO_JWT_TIME_CLAIMS;
        }
        String[] segments = token.split("\\.");
        if (segments.length < 2) {
            return NO_JWT_TIME_CLAIMS;
        }
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(padBase64Url(segments[1]));
            JsonNode payload = objectMapper.readTree(payloadBytes);
            return new JwtTimeClaims(epochSecondClaim(payload, "iat"), epochSecondClaim(payload, "exp"));
        } catch (IllegalArgumentException | IOException ex) {
            logger.debug("external_auth jwt timing claims could not be decoded: {}", ex.getMessage());
            return NO_JWT_TIME_CLAIMS;
        }
    }

    private Optional<Instant> epochSecondClaim(JsonNode payload, String claimName) {
        JsonNode claim = payload == null ? null : payload.get(claimName);
        if (claim == null || claim.isNull() || !claim.canConvertToLong()) {
            return Optional.empty();
        }
        long epochSeconds = claim.asLong();
        if (epochSeconds <= 0) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochSecond(epochSeconds));
    }

    private String padBase64Url(String value) {
        int padding = (4 - (value.length() % 4)) % 4;
        return padding == 0 ? value : value + "=".repeat(padding);
    }

    private enum TokenAudience {
        CYBRILLA_PRE_VERIFICATION("Cybrilla pre-verification"),
        FINPRIM_TENANT("Fintech Primitives tenant");

        private final String label;

        TokenAudience(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        String cacheKey() {
            return name();
        }
    }

    private record JwtTimeClaims(Optional<Instant> issuedAt, Optional<Instant> expiresAt) {
    }

    private record CachedBearerToken(String value, Instant issuedAt, Instant refreshAt, Instant expiresAt) {
        boolean isUsable(Clock clock) {
            return Instant.now(clock).isBefore(refreshAt);
        }

        boolean isIssuedAfter(CachedBearerToken other) {
            return issuedAt.isAfter(other.issuedAt());
        }

        String fingerprint() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
                return "sha256:" + HexFormat.of().formatHex(hash, 0, 6);
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 is not available", ex);
            }
        }

        StoredBearerToken toStoredToken() {
            return new StoredBearerToken(value, refreshAt, expiresAt);
        }
    }

    private record OAuthTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn,
            String scope
    ) {
    }
}
