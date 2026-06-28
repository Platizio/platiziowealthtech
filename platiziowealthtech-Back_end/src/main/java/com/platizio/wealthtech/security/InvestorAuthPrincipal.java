package com.platizio.wealthtech.security;

import java.util.UUID;

/**
 * Marker for an authenticated investor-portal session. Deliberately separate from
 * the distributor {@link JwtAuthPrincipal} so that distributor controllers (which
 * cast to {@code JwtAuthPrincipal}) structurally reject investor tokens and vice
 * versa.
 */
public interface InvestorAuthPrincipal {
    UUID getInvestorAccountId();
}
