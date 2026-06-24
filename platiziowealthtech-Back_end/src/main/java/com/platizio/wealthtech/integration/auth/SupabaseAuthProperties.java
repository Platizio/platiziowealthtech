package com.platizio.wealthtech.integration.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Supabase Auth (GoTrue) connection settings for backend-mediated email/mobile
 * OTP. The browser never talks to Supabase directly — the Spring backend calls
 * {@code {url}/auth/v1/otp} and {@code /auth/v1/verify} with the anon key, in
 * keeping with the "browser -> Platizio /api/v1 only" rule.
 *
 * <p>{@code real-client-enabled} defaults to {@code false}: with no Supabase
 * project configured (local / demo / tests) the {@code DisabledSupabaseAuthClient}
 * is wired instead, so the app boots and the flow can be exercised with a dev
 * code. Production sets {@code real-client-enabled=true} + url + keys.
 */
@Component
@ConfigurationProperties(prefix = "supabase.auth")
public class SupabaseAuthProperties {

    /** Project base URL, e.g. https://abcd1234.supabase.co (no trailing slash). */
    private String url = "";

    /** Public anon key — sent as both {@code apikey} and bearer token. */
    private String anonKey = "";

    /** Service-role key (reserved for future admin calls; not required for OTP). */
    private String serviceRoleKey = "";

    /** When sending an OTP, create the Supabase user if it doesn't exist yet. */
    private boolean createUser = true;

    /** Selects RealSupabaseAuthClient (true) vs DisabledSupabaseAuthClient (false). */
    private boolean realClientEnabled = false;

    /**
     * Whether the mobile/SMS OTP channel is usable. Defaults to {@code false}:
     * even with a live project, the Supabase Phone provider may be disabled (e.g.
     * it requires a paid plan), so SMS OTP is blocked until this is set true via
     * {@code SUPABASE_SMS_ENABLED}. Email OTP is unaffected.
     */
    private boolean smsEnabled = false;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getAnonKey() { return anonKey; }
    public void setAnonKey(String anonKey) { this.anonKey = anonKey; }
    public String getServiceRoleKey() { return serviceRoleKey; }
    public void setServiceRoleKey(String serviceRoleKey) { this.serviceRoleKey = serviceRoleKey; }
    public boolean isCreateUser() { return createUser; }
    public void setCreateUser(boolean createUser) { this.createUser = createUser; }
    public boolean isRealClientEnabled() { return realClientEnabled; }
    public void setRealClientEnabled(boolean realClientEnabled) { this.realClientEnabled = realClientEnabled; }
    public boolean isSmsEnabled() { return smsEnabled; }
    public void setSmsEnabled(boolean smsEnabled) { this.smsEnabled = smsEnabled; }
}
