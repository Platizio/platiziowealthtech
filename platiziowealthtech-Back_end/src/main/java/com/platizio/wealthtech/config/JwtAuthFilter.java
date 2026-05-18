package com.platizio.wealthtech.config;

import com.platizio.wealthtech.service.CustomUserDetailsService;
import com.platizio.wealthtech.service.JwtService;
import com.platizio.wealthtech.service.AuthCookieService;
import com.platizio.wealthtech.service.BlockedTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthFilter.class);
    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;
    private final AuthCookieService authCookieService;
    private final BlockedTokenService blockedTokenService;

    public JwtAuthFilter(
            JwtService jwtService,
            CustomUserDetailsService userDetailsService,
            AuthCookieService authCookieService,
            BlockedTokenService blockedTokenService
    ) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
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

        Optional<String> tokenOptional = extractToken(request);
        if (tokenOptional.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = tokenOptional.get();
        logger.debug("Processing request to {} with token", path);

        if (!jwtService.isTokenValid(token)) {
            logger.warn("Invalid token received for request to {}", path);
            filterChain.doFilter(request, response);
            return;
        }
        if (blockedTokenService.isBlocked(jwtService.extractJti(token))) {
            logger.warn("Blocked token received for request to {}", path);
            filterChain.doFilter(request, response);
            return;
        }

        String email = jwtService.extractEmail(token);

        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);
            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
            logger.info("Authenticated user {} for request to {}", email, path);
        }

        filterChain.doFilter(request, response);
    }

    private Optional<String> extractToken(HttpServletRequest request) {
        return authCookieService.readAccessToken(request);
    }
}
