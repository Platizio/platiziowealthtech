package com.platizio.wealthtech.integration.auth;

import java.time.Duration;
import org.springframework.util.StringUtils;

public record OAuthClientCredentials(
        String tokenUrl,
        String clientId,
        String clientSecret,
        Duration refreshBuffer
) {
    public void validate(String providerName) {
        if (!StringUtils.hasText(tokenUrl)) {
            throw new IllegalStateException(providerName + " token URL is not configured");
        }
        if (!StringUtils.hasText(clientId)) {
            throw new IllegalStateException(providerName + " client id is not configured");
        }
        if (!StringUtils.hasText(clientSecret)) {
            throw new IllegalStateException(providerName + " client secret is not configured");
        }
    }

    public Duration safeRefreshBuffer() {
        if (refreshBuffer == null || refreshBuffer.isNegative()) {
            return Duration.ZERO;
        }
        return refreshBuffer;
    }
}
