package com.platizio.wealthtech.config;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.security.AuthenticatedInvestorPrincipal;
import com.platizio.wealthtech.service.AuthCookieService;
import com.platizio.wealthtech.service.BlockedTokenService;
import com.platizio.wealthtech.service.JwtService;
import org.springframework.security.core.userdetails.UserDetails;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthFilter.class);
    private final JwtService jwtService;
    private final AuthCookieService authCookieService;
    private final BlockedTokenService blockedTokenService;

    public JwtAuthFilter(
            JwtService jwtService,
            AuthCookieService authCookieService,
            BlockedTokenService blockedTokenService
    ) {
        this.jwtService = jwtService;
        this.authCookieService = authCookieService;
        this.blockedTokenService = blockedTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if ("/favicon.ico".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean investorContext = isInvestorPath(path);
        Optional<String> tokenOptional = extractToken(request, investorContext);
        if (tokenOptional.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = tokenOptional.get();
        logger.debug("Processing request to {} with token", path);

        Claims claims;
        try {
            claims = jwtService.extractAllClaims(token);
        } catch (Exception ex) {
            logger.warn("Invalid token received for request to {}", path);
            filterChain.doFilter(request, response);
            return;
        }

        if (claims.getExpiration() == null || claims.getExpiration().before(new Date())) {
            logger.warn("Expired token received for request to {}", path);
            filterChain.doFilter(request, response);
            return;
        }
        if (blockedTokenService.isBlocked(claims.getId())) {
            logger.warn("Blocked token received for request to {}", path);
            filterChain.doFilter(request, response);
            return;
        }

        UserDetails principal = investorContext
                ? investorPrincipalFromClaims(claims)
                : distributorPrincipalFromClaims(claims);
        if (principal != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
            logger.debug("Authenticated user {} for request to {}", principal.getUsername(), path);
        }

        filterChain.doFilter(request, response);
    }

    private UserDetails distributorPrincipalFromClaims(Claims claims) {
        if (JwtService.TYPE_INVESTOR.equals(claims.get("typ", String.class))) {
            return null; // an investor token can never authenticate a distributor route
        }
        try {
            UUID distributorId = UUID.fromString(claims.getSubject());
            String email = claims.get("email", String.class);
            DistributorRole role = DistributorRole.valueOf(claims.get("role", String.class));
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
            if (role != DistributorRole.ADMIN) {
                authorities.add(new SimpleGrantedAuthority("ROLE_DISTRIBUTOR"));
            }
            return new AuthenticatedDistributorPrincipal(distributorId, email, "", role, authorities);
        } catch (RuntimeException ex) {
            logger.warn("JWT token is missing required distributor claims");
            return null;
        }
    }

    private UserDetails investorPrincipalFromClaims(Claims claims) {
        if (!JwtService.TYPE_INVESTOR.equals(claims.get("typ", String.class))) {
            return null; // only a typ=INVESTOR token authenticates an investor route
        }
        try {
            UUID accountId = UUID.fromString(claims.getSubject());
            String email = claims.get("email", String.class);
            return new AuthenticatedInvestorPrincipal(accountId, email);
        } catch (RuntimeException ex) {
            logger.warn("JWT token is missing required investor claims");
            return null;
        }
    }

    private boolean isInvestorPath(String path) {
        return path != null
                && (path.startsWith("/api/v1/investor/") || path.startsWith("/api/v1/investor-auth"));
    }

    private Optional<String> extractToken(HttpServletRequest request, boolean investorContext) {
        return investorContext
                ? authCookieService.readInvestorAccessToken(request)
                : authCookieService.readAccessToken(request);
    }
}
