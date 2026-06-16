package com.platizio.wealthtech.integration.arn;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the external ARN / KYD validation provider. All values are environment-driven
 * (see the {@code arn-validation} block in application.yml). When {@code realClientEnabled} is false
 * (the default), the {@link MockArnValidationClient} placeholder is used so distributor signup works
 * before a real provider/credentials are available.
 */
@Component
@ConfigurationProperties(prefix = "arn-validation")
public class ArnValidationProperties {

    /** When false (default) the mock placeholder validates ARNs; flip to true once a real provider is configured. */
    private boolean realClientEnabled = false;
    /** Base URL of the real ARN/KYD provider, e.g. https://api.provider.example. */
    private String baseUrl = "";
    /** Provider lookup path; {@code {arn}} is replaced with the (URL-encoded) ARN. */
    private String lookupPath = "/arn/{arn}";
    /** Optional API key sent as a Bearer token / X-API-Key header (provider dependent). */
    private String apiKey = "";
    /** Label stored on the distributor record as the validation source. */
    private String source = "AMFI";

    public boolean isRealClientEnabled() {
        return realClientEnabled;
    }

    public void setRealClientEnabled(boolean realClientEnabled) {
        this.realClientEnabled = realClientEnabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getLookupPath() {
        return lookupPath;
    }

    public void setLookupPath(String lookupPath) {
        this.lookupPath = lookupPath;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
