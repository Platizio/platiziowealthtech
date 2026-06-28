package com.platizio.wealthtech.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Authenticated investor-portal principal (authority {@code ROLE_INVESTOR}).
 * Does NOT implement the distributor {@code JwtAuthPrincipal}, so it cannot be
 * used by distributor-scoped controllers.
 */
public final class AuthenticatedInvestorPrincipal implements InvestorAuthPrincipal, UserDetails {

    private final UUID investorAccountId;
    private final String email;
    private final List<GrantedAuthority> authorities;

    public AuthenticatedInvestorPrincipal(UUID investorAccountId, String email) {
        this.investorAccountId = investorAccountId;
        this.email = email;
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_INVESTOR"));
    }

    @Override
    public UUID getInvestorAccountId() {
        return investorAccountId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return email;
    }
}
