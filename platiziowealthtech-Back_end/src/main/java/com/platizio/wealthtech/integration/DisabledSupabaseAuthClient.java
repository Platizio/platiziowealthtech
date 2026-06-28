package com.platizio.wealthtech.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op Supabase Auth client used when no Supabase project is configured
 * ({@code supabase.auth.real-client-enabled=false}, the default). It logs what
 * it "would" send and accepts a single well-known dev code so the email/mobile
 * verification flow can be exercised on local/demo/tests without a real backend.
 *
 * <p>This is only ever wired when the real client is disabled — production sets
 * {@code real-client-enabled=true} and never instantiates this. The dev-code
 * acceptance is logged loudly so it can never pass silently.
 */
@Component
@ConditionalOnProperty(
        prefix = "supabase.auth",
        name = "real-client-enabled",
        havingValue = "false",
        matchIfMissing = true)
public class DisabledSupabaseAuthClient implements SupabaseAuthClient {

    private static final Logger logger = LoggerFactory.getLogger(DisabledSupabaseAuthClient.class);

    /** The only code accepted while Supabase is disabled. Never reachable in prod. */
    static final String DEV_OTP = "000000";

    @Override
    public void sendEmailOtp(String email) {
        logger.warn("[SUPABASE DISABLED] Would email an OTP to {}. Verify with dev code {}.", email, DEV_OTP);
    }

    @Override
    public void sendSmsOtp(String phoneE164) {
        logger.warn("[SUPABASE DISABLED] Would SMS an OTP to {}. Verify with dev code {}.", phoneE164, DEV_OTP);
    }

    @Override
    public boolean verifyEmailOtp(String email, String token) {
        return DEV_OTP.equals(token);
    }

    @Override
    public boolean verifySmsOtp(String phoneE164, String token) {
        return DEV_OTP.equals(token);
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public boolean isSmsEnabled() {
        // The dev code 000000 verifies SMS too, so the mobile flow stays testable locally.
        return true;
    }
}
