package com.platizio.wealthtech.security;

import com.platizio.wealthtech.domain.DistributorRole;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class AuthenticatedDistributorPrincipal implements JwtAuthPrincipal, UserDetails {

    private final UUID distributorId;
    private final String email;
    private final String password;
    private final DistributorRole role;
    private final List<GrantedAuthority> authorities;

    public AuthenticatedDistributorPrincipal(
            UUID distributorId,
            String email,
            String password,
            DistributorRole role,
            Collection<? extends GrantedAuthority> authorities
    ) {
        this.distributorId = distributorId;
        this.email = email;
        this.password = password;
        this.role = role;
        this.authorities = List.copyOf(authorities);
    }

    public UUID distributorId() {
        return distributorId;
    }

    public UUID getDistributorId() {
        return distributorId;
    }

    public DistributorRole role() {
        return role;
    }

    public DistributorRole getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
