package com.platizio.wealthtech.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.service.AuthCookieService;
import com.platizio.wealthtech.service.BlockedTokenService;
import com.platizio.wealthtech.service.CustomUserDetailsService;
import com.platizio.wealthtech.service.JwtService;
import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

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
                new TestUserDetailsService(),
                new AuthCookieService("access_token", "refresh_token", false, "Lax", 1_800_000, 604_800_000),
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
        public boolean isTokenValid(String token) {
            return true;
        }

        @Override
        public String extractJti(String token) {
            return "test-jti";
        }

        @Override
        public String extractEmail(String token) {
            return "user@example.com";
        }
    }

    private static class TestUserDetailsService extends CustomUserDetailsService {

        TestUserDetailsService() {
            super(null);
        }

        @Override
        public UserDetails loadUserByUsername(String username) {
            return new User(
                    username,
                    "",
                    List.of(new SimpleGrantedAuthority("ROLE_SUB_DISTRIBUTOR"))
            );
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
