package com.platizio.wealthtech.integration;

/**
 * Backend boundary for Supabase Auth (GoTrue) email + phone OTP, used to verify
 * that an investor's email / mobile belongs to them. Supabase owns the OTP
 * lifecycle (generation, delivery, expiry); the app only asks it to send a code
 * and to verify one. On a successful verify the caller records the verification
 * on the investor.
 *
 * <p>Two implementations are selected by {@code supabase.auth.real-client-enabled}:
 * {@code RealSupabaseAuthClient} (live HTTP) and {@code DisabledSupabaseAuthClient}
 * (no-op + dev code) so local/demo/tests run without a Supabase project.
 */
public interface SupabaseAuthClient {

    /** Ask Supabase to email a one-time code to {@code email}. */
    void sendEmailOtp(String email);

    /** Ask Supabase to SMS a one-time code to {@code phoneE164} (e.g. +919876543210). */
    void sendSmsOtp(String phoneE164);

    /** @return true if {@code token} is the valid, unexpired code for {@code email}. */
    boolean verifyEmailOtp(String email, String token);

    /** @return true if {@code token} is the valid, unexpired code for {@code phoneE164}. */
    boolean verifySmsOtp(String phoneE164, String token);

    /** @return true when a real Supabase backend is configured (vs the disabled fallback). */
    boolean isEnabled();

    /**
     * @return true when the mobile/SMS OTP channel is usable. Distinct from
     * {@link #isEnabled()}: a live Supabase project can have email enabled while
     * the Phone provider is disabled (e.g. it requires a paid plan), in which case
     * SMS OTP must not be offered. The UI uses this to hide the mobile-OTP path.
     */
    boolean isSmsEnabled();
}
