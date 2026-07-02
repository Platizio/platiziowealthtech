package com.platizio.wealthtech.service;

import com.platizio.wealthtech.integration.SupabaseAuthClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Mobile/SMS OTP channel for investor flows (contact verification and login).
 *
 * <p>TODO(MSG91): this class is THE single plug-in point for the real MSG91
 * integration. When MSG91 goes live, replace the demo branches below with the
 * MSG91 send-OTP / verify-OTP API calls (keeping this method surface), and the
 * contact-verification and mobile-login flows pick it up unchanged.
 *
 * <p>Until then the channel works in two modes:
 * <ul>
 *   <li><b>Supabase Phone provider enabled</b> ({@code supabase.auth.sms-enabled=true})
 *       — delegate send/verify to {@link SupabaseAuthClient} (real SMS).</li>
 *   <li><b>Demo stub</b> (default; Supabase's current plan has no SMS OTP) — the
 *       send is simulated and the fixed demo code {@value #DEMO_OTP} verifies.
 *       Logged loudly so it can never pass silently.</li>
 * </ul>
 */
@Service
public class SmsOtpService {

    private static final Logger logger = LoggerFactory.getLogger(SmsOtpService.class);

    /** The only code accepted in demo mode. Same value the disabled Supabase client uses. */
    static final String DEMO_OTP = "000000";

    private final SupabaseAuthClient supabaseAuthClient;

    public SmsOtpService(SupabaseAuthClient supabaseAuthClient) {
        this.supabaseAuthClient = supabaseAuthClient;
    }

    /** Sends (or, in demo mode, simulates sending) an OTP to {@code phoneE164}. */
    public void sendOtp(String phoneE164) {
        if (supabaseAuthClient.isSmsEnabled()) {
            supabaseAuthClient.sendSmsOtp(phoneE164);
            return;
        }
        // TODO(MSG91): replace this simulated send with the MSG91 send-OTP API call.
        logger.warn("[SMS DEMO] Would SMS an OTP to {}. Verify with demo code {}.", phoneE164, DEMO_OTP);
    }

    /** @return true when {@code code} is valid for {@code phoneE164} (demo code in demo mode). */
    public boolean verifyOtp(String phoneE164, String code) {
        if (supabaseAuthClient.isSmsEnabled()) {
            return supabaseAuthClient.verifySmsOtp(phoneE164, code);
        }
        // TODO(MSG91): replace this demo-code check with the MSG91 verify-OTP API call.
        boolean accepted = DEMO_OTP.equals(code);
        if (accepted) {
            logger.warn("[SMS DEMO] Accepted the demo OTP for {}.", phoneE164);
        }
        return accepted;
    }

    /** @return true while the demo stub (not a real SMS provider) answers this channel. */
    public boolean isDemo() {
        return !(supabaseAuthClient.isEnabled() && supabaseAuthClient.isSmsEnabled());
    }

    /**
     * The mobile OTP channel is always offerable: real SMS when a provider is
     * configured, the demo stub otherwise. Kept as a method so a future
     * configuration can switch the channel off entirely.
     */
    public boolean isAvailable() {
        return true;
    }
}
