package com.platizio.wealthtech.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Context-free unit test (codebase convention — no @SpringBootTest infra).
 * Exercises the real Caffeine + bucket4j wiring via Spring's mock servlet API.
 */
class LoginRateLimitFilterTest {

    private LoginRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        filter = new LoginRateLimitFilter(mapper);
    }

    private MockHttpServletRequest authRequest(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    void nonAuthPathIsNeverThrottled() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/investors");
        request.setRemoteAddr("9.9.9.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // Far more than the bucket capacity — must always pass through.
        for (int i = 0; i < 50; i++) {
            filter.doFilter(authRequest("ignored"), new MockHttpServletResponse(), new MockFilterChain());
        }
        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void sixthAttemptFromSameIpIsRejectedWith429() throws ServletException, IOException {
        for (int i = 1; i <= 5; i++) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(authRequest("1.2.3.4"), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest())
                    .as("attempt %d within the limit should pass through", i)
                    .isNotNull();
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        MockFilterChain blockedChain = new MockFilterChain();
        filter.doFilter(authRequest("1.2.3.4"), blocked, blockedChain);

        assertThat(blockedChain.getRequest())
                .as("6th attempt must not reach the controller")
                .isNull();
        assertThat(blocked.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(blocked.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(blocked.getContentAsString())
                .contains("TOO_MANY_REQUESTS")
                .contains("Too many attempts. Try again in 60 seconds.");
    }

    @Test
    void bucketsAreIsolatedPerClientIp() throws ServletException, IOException {
        for (int i = 0; i < 5; i++) {
            filter.doFilter(authRequest("10.0.0.1"), new MockHttpServletResponse(), new MockFilterChain());
        }
        // 10.0.0.1 is now exhausted; a different IP must still be allowed.
        MockHttpServletResponse otherIp = new MockHttpServletResponse();
        MockFilterChain otherChain = new MockFilterChain();
        filter.doFilter(authRequest("10.0.0.2"), otherIp, otherChain);

        assertThat(otherChain.getRequest()).isNotNull();
        assertThat(otherIp.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void clientIpIsTakenFromFirstXForwardedForValue() throws ServletException, IOException {
        // Two requests from different socket addresses but the same first XFF
        // hop must share one bucket (proves XFF[0] is the key, not remoteAddr).
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest r = authRequest("172.16.0." + i);
            r.addHeader("X-Forwarded-For", "203.0.113.7, 70.41.3.18, 150.172.238.178");
            filter.doFilter(r, new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest sixth = authRequest("172.16.0.99");
        sixth.addHeader("X-Forwarded-For", "203.0.113.7, 10.10.10.10");
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        MockFilterChain blockedChain = new MockFilterChain();
        filter.doFilter(sixth, blocked, blockedChain);

        assertThat(blockedChain.getRequest()).isNull();
        assertThat(blocked.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }
}
