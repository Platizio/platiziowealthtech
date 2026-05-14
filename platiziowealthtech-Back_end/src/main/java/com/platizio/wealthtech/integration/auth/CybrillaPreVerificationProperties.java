package com.platizio.wealthtech.integration.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cybrilla.pre-verification")
public class CybrillaPreVerificationProperties {

    private String baseUrl = "https://api.sandbox.cybrilla.com";
    private Auth auth = new Auth();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public OAuthClientCredentials credentials() {
        return new OAuthClientCredentials(
                auth.getTokenUrl(),
                auth.getClientId(),
                auth.getClientSecret(),
                Duration.ofSeconds(auth.getRefreshBufferSeconds())
        );
    }

    public static class Auth {
        private String tokenUrl = "https://s.finprim.com/v2/auth/cybrillarta/token";
        private String clientId = "";
        private String clientSecret = "";
        private long refreshBufferSeconds = 120;

        public String getTokenUrl() {
            return tokenUrl;
        }

        public void setTokenUrl(String tokenUrl) {
            this.tokenUrl = tokenUrl;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public long getRefreshBufferSeconds() {
            return refreshBufferSeconds;
        }

        public void setRefreshBufferSeconds(long refreshBufferSeconds) {
            this.refreshBufferSeconds = refreshBufferSeconds;
        }
    }
}
