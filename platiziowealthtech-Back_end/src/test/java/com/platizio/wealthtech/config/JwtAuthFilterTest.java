package com.platizio.wealthtech.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.service.AuthCookieService;
import com.platizio.wealthtech.service.BlockedTokenService;
import com.platizio.wealthtech.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blockedTokenDoesNotAuthenticateRequest() throws Exception {
        JwtAuthFilter filter = filter(true);
        MockHttpServletRequest request = bearerRequest();

        filter.doFilter(request, new MockHttpServletResponse(), noopChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void unblockedTokenAuthenticatesRequest() throws Exception {
        JwtAuthFilter filter = filter(false);
        MockHttpServletRequest request = bearerRequest();

        filter.doFilter(request, new MockHttpServletResponse(), noopChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    private JwtAuthFilter filter(boolean blocked) {
        return new JwtAuthFilter(
                new TestJwtService(),
                new AuthCookieService("access_token", "refresh_token", "investor_access_token", false, "Lax", 1_800_000, 604_800_000, 3_600_000),
                new TestBlockedTokenService(blocked)
        );
    }

    private MockHttpServletRequest bearerRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/orders");
        request.addHeader("Authorization", "Bearer valid.jwt");
        return request;
    }

    private FilterChain noopChain() {
        return (request, response) -> {
        };
    }

    private static class TestJwtService extends JwtService {

        @Override
        public Claims extractAllClaims(String token) {
            return Jwts.claims()
                    .id("test-jti")
                    .subject(UUID.randomUUID().toString())
                    .add("email", "user@example.com")
                    .add("role", "SUB_DISTRIBUTOR")
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .build();
        }
    }

    private static class TestBlockedTokenService extends BlockedTokenService {

        private final boolean blocked;

        TestBlockedTokenService(boolean blocked) {
            super(null);
            this.blocked = blocked;
        }

        @Override
        public boolean isBlocked(String jti) {
            return blocked;
        }
    }
}
