package com.platizio.wealthtech.integration.arn;

import com.fasterxml.jackson.databind.JsonNode;
import com.platizio.wealthtech.domain.ArnValidationStatus;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaUnavailableException;
import com.platizio.wealthtech.validation.ArnFormat;
import java.time.LocalDate;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Real ARN/KYD validation provider client. Active only when {@code arn-validation.real-client-enabled=true}
 * AND a {@code base-url} is configured. This is the plug-in point: the request path and the JSON→verdict
 * mapping below are intentionally generic and MUST be aligned to the actual provider's contract when its
 * credentials/spec are available. Until then the {@link MockArnValidationClient} placeholder is used.
 */
@Component
@ConditionalOnProperty(prefix = "arn-validation", name = "real-client-enabled", havingValue = "true")
public class RealArnValidationClient implements ArnValidationClient {

    private static final Logger logger = LoggerFactory.getLogger(RealArnValidationClient.class);

    private final RestClient restClient;
    private final ArnValidationProperties properties;

    public RealArnValidationClient(RestClient.Builder restClientBuilder, ArnValidationProperties properties) {
        this.properties = properties;
        if (!StringUtils.hasText(properties.getBaseUrl())) {
            throw new IllegalStateException(
                    "arn-validation.real-client-enabled=true but arn-validation.base-url is not configured");
        }
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
        logger.info("arn_validation_client mode='real' base_url='{}' source='{}'", properties.getBaseUrl(), properties.getSource());
    }

    @Override
    public ArnValidationResult validateArn(String arnNumber) {
        String arn = ArnFormat.normalize(arnNumber);
        String path = properties.getLookupPath().replace("{arn}", arn);
        try {
            JsonNode response = restClient.get()
                    .uri(path)
                    .headers(this::applyAuth)
                    .retrieve()
                    .body(JsonNode.class);
            return mapResponse(arn, response);
        } catch (RestClientResponseException ex) {
            // 404 from the registry means "ARN not found" — a verdict, not an outage.
            if (ex.getStatusCode().value() == 404) {
                return new ArnValidationResult(ArnValidationStatus.REJECTED, arn, null, null, null, null, null,
                        properties.getSource(), "ARN not found in the registry.");
            }
            throw new CybrillaApiException(
                    "ARN validation provider returned " + ex.getStatusCode() + " for ARN " + arn, ex);
        } catch (ResourceAccessException ex) {
            throw new CybrillaUnavailableException(
                    "ARN validation provider is unreachable. Please try again shortly.", ex);
        }
    }

    private void applyAuth(HttpHeaders headers) {
        if (StringUtils.hasText(properties.getApiKey())) {
            headers.setBearerAuth(properties.getApiKey().trim());
        }
    }

    private ArnValidationResult mapResponse(String arn, JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            throw new CybrillaApiException("ARN validation provider returned an empty response for ARN " + arn);
        }
        String name = text(body, "name", "distributor_name", "arn_holder_name");
        String firmName = text(body, "firm_name", "firm", "entity_name");
        String euin = text(body, "euin", "e_uin", "euin_number");
        String kydStatus = text(body, "kyd_status", "kyd");
        LocalDate expiry = date(body, "arn_expiry_date", "valid_till", "expiry_date");
        ArnValidationStatus status = resolveStatus(body, kydStatus, expiry);
        String message = switch (status) {
            case VERIFIED -> "ARN verified.";
            case EXPIRED -> "ARN registration has expired.";
            case KYD_INCOMPLETE -> "ARN is valid but KYD is incomplete.";
            default -> "ARN could not be verified with the registry.";
        };
        return new ArnValidationResult(status, arn, name, firmName, expiry, kydStatus, euin,
                properties.getSource(), message);
    }

    private ArnValidationStatus resolveStatus(JsonNode body, String kydStatus, LocalDate expiry) {
        String raw = text(body, "status", "arn_status");
        if (raw != null) {
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if (normalized.contains("EXPIRE")) {
                return ArnValidationStatus.EXPIRED;
            }
            if (normalized.contains("REJECT") || normalized.contains("INVALID") || normalized.contains("NOT_FOUND")) {
                return ArnValidationStatus.REJECTED;
            }
        }
        boolean valid = body.path("valid").asBoolean(raw != null && raw.toUpperCase(Locale.ROOT).contains("VALID"));
        if (!valid) {
            return ArnValidationStatus.REJECTED;
        }
        if (expiry != null && expiry.isBefore(LocalDate.now())) {
            return ArnValidationStatus.EXPIRED;
        }
        if (kydStatus != null && !kydStatus.trim().equalsIgnoreCase("COMPLETE")
                && !kydStatus.trim().equalsIgnoreCase("COMPLETED")) {
            return ArnValidationStatus.KYD_INCOMPLETE;
        }
        return ArnValidationStatus.VERIFIED;
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private static LocalDate date(JsonNode node, String... fields) {
        String raw = text(node, fields);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException ex) {
            logger.debug("arn_validation could not parse date '{}'", raw);
            return null;
        }
    }
}
