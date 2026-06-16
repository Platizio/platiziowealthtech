package com.platizio.wealthtech.integration.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@ConfigurationProperties(prefix = "finprim")
public class FinprimTenantProperties {

    private String baseUrl = "https://s.finprim.com";
    private Tenant tenant = new Tenant();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public OAuthClientCredentials credentials() {
        return new OAuthClientCredentials(
                resolveTokenUrl(),
                tenant.getAuth().getClientId(),
                tenant.getAuth().getClientSecret(),
                Duration.ofSeconds(tenant.getAuth().getRefreshBufferSeconds())
        );
    }

    public String tenantHeaderValue() {
        if (StringUtils.hasText(tenant.getId())) {
            return tenant.getId();
        }
        return tenant.getName();
    }

    /** Resolved OAuth token URL (Cybrilla doc template /v2/auth/tenant/token is rewritten to /v2/auth/{name}/token). */
    public String resolvedTokenUrl() {
        return resolveTokenUrl();
    }

    private String resolveTokenUrl() {
        if (StringUtils.hasText(tenant.getAuth().getTokenUrl())) {
            String configuredTokenUrl = tenant.getAuth().getTokenUrl();
            if (configuredTokenUrl.contains("/v2/auth/tenant/token")
                    && StringUtils.hasText(tenant.getName())
                    && !"tenant".equals(tenant.getName())) {
                return configuredTokenUrl.replace("/v2/auth/tenant/token", "/v2/auth/" + tenant.getName() + "/token");
            }
            return tenant.getAuth().getTokenUrl();
        }
        if (!StringUtils.hasText(tenant.getName())) {
            return "";
        }
        return UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("v2", "auth", tenant.getName(), "token")
                .toUriString();
    }

    public static class Tenant {
        private String name = "";
        private String id = "";
        private Auth auth = new Auth();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Auth getAuth() {
            return auth;
        }

        public void setAuth(Auth auth) {
            this.auth = auth;
        }
    }

    public static class Auth {
        private String tokenUrl = "";
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
