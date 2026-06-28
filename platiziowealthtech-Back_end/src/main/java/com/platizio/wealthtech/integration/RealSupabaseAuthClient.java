package com.platizio.wealthtech.integration;

import com.platizio.wealthtech.integration.auth.SupabaseAuthProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Live Supabase Auth client. Calls the GoTrue REST API server-side:
 * <ul>
 *   <li>{@code POST {url}/auth/v1/otp} — send an email or SMS OTP.</li>
 *   <li>{@code POST {url}/auth/v1/verify} — verify an OTP (200 = valid; 4xx = rejected).</li>
 * </ul>
 * Both calls send the anon key as {@code apikey} + bearer. Active only when
 * {@code supabase.auth.real-client-enabled=true}.
 *
 * @see <a href="https://supabase.com/docs/reference/api/auth-otp">Supabase Auth OTP</a>
 */
@Component
@ConditionalOnProperty(prefix = "supabase.auth", name = "real-client-enabled", havingValue = "true")
public class RealSupabaseAuthClient implements SupabaseAuthClient {

    private static final Logger logger = LoggerFactory.getLogger(RealSupabaseAuthClient.class);

    private final RestClient restClient;
    private final SupabaseAuthProperties properties;

    public RealSupabaseAuthClient(RestClient.Builder restClientBuilder, SupabaseAuthProperties properties) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.getUrl()).build();
    }

    @Override
    public void sendEmailOtp(String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("create_user", properties.isCreateUser());
        sendOtp(body, "send email OTP");
    }

    @Override
    public void sendSmsOtp(String phoneE164) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", phoneE164);
        body.put("create_user", properties.isCreateUser());
        sendOtp(body, "send SMS OTP");
    }

    @Override
    public boolean verifyEmailOtp(String email, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "email");
        body.put("email", email);
        body.put("token", token);
        return verify(body, "verify email OTP");
    }

    @Override
    public boolean verifySmsOtp(String phoneE164, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "sms");
        body.put("phone", phoneE164);
        body.put("token", token);
        return verify(body, "verify SMS OTP");
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isSmsEnabled() {
        return properties.isSmsEnabled();
    }

    private void sendOtp(Map<String, Object> body, String operation) {
        try {
            restClient.post()
                    .uri("/auth/v1/otp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::authHeaders)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new SupabaseAuthException(
                    "Supabase failed to " + operation + ": " + ex.getStatusCode() + " " + ex.getResponseBodyAsString(),
                    ex);
        } catch (ResourceAccessException ex) {
            throw new SupabaseAuthException("Supabase unreachable while trying to " + operation, ex);
        }
    }

    private boolean verify(Map<String, Object> body, String operation) {
        try {
            restClient.post()
                    .uri("/auth/v1/verify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::authHeaders)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true; // 200 → a session was issued, so the code was valid
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                // Wrong / expired code (GoTrue uses 400/403). Logged so a 401 from a
                // bad apikey is still diagnosable, but treated as "not verified".
                logger.info("Supabase rejected {} ({}): {}", operation, ex.getStatusCode(), ex.getResponseBodyAsString());
                return false;
            }
            throw new SupabaseAuthException(
                    "Supabase failed to " + operation + ": " + ex.getStatusCode(), ex);
        } catch (ResourceAccessException ex) {
            throw new SupabaseAuthException("Supabase unreachable while trying to " + operation, ex);
        }
    }

    private void authHeaders(HttpHeaders headers) {
        headers.set("apikey", properties.getAnonKey());
        headers.setBearerAuth(properties.getAnonKey());
    }
}
