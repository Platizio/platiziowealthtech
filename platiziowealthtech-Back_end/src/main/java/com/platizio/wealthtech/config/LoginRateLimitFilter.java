package com.platizio.wealthtech.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.platizio.wealthtech.dto.ApiErrorResponse;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * B-46: per-IP rate limiting on the unauthenticated auth endpoints to blunt
 * brute-force / credential-stuffing attacks.
 *
 * Design notes:
 *  - Extends OncePerRequestFilter (codebase convention, see JwtAuthFilter)
 *    rather than a raw Filter: it dedupes across FORWARD/ASYNC dispatches.
 *  - Buckets live in a Caffeine cache with expireAfterAccess so the IP->bucket
 *    map self-evicts; an unbounded ConcurrentHashMap keyed by a spoofable
 *    header would itself be a memory-exhaustion DoS vector.
 *  - SECURITY CAVEAT: X-Forwarded-For is client-supplied and trivially
 *    spoofable unless a trusted reverse proxy overwrites it. This filter is
 *    only a robust per-client control when the app sits behind such a proxy
 *    (configure server.forward-headers-strategy=NATIVE/FRAMEWORK). Treat it as
 *    defence-in-depth, not a complete anti-automation solution.
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    /** Only these exact POST paths are throttled. Uses /api/v1 (the real prefix). */
    private static final Set<String> RATE_LIMITED_PATHS =
            Set.of(
                    "/api/v1/auth/login",
                    "/api/v1/auth/signup",
                    // Throttle OTP issuance so an attacker can't email-bomb an
                    // address by rotating accounts (per-email cooldown already
                    // caps repeats for a single inbox). Verify is bounded by the
                    // per-code attempt counter in OtpService.
                    "/api/v1/auth/otp/request");

    private static final int CAPACITY = 5;
    private static final int REFILL_TOKENS = 5;
    private static final Duration REFILL_PERIOD = Duration.ofSeconds(60);
    private static final long IDLE_BUCKET_EXPIRY_MINUTES = 10;
    private static final String LIMIT_MESSAGE = "Too many attempts. Try again in 60 seconds.";

    private final ObjectMapper objectMapper;
    private final Cache<String, Bucket> bucketsByClientIp;

    public LoginRateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.bucketsByClientIp = Caffeine.newBuilder()
                .expireAfterAccess(IDLE_BUCKET_EXPIRY_MINUTES, TimeUnit.MINUTES)
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!isRateLimited(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        Bucket bucket = bucketsByClientIp.get(clientIp, ip -> newBucket());

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
            return;
        }

        logger.warn("Rate limit exceeded for IP {} on {}", clientIp, request.getRequestURI());
        writeTooManyRequests(request, response);
    }

    private boolean isRateLimited(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && RATE_LIMITED_PATHS.contains(request.getRequestURI());
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(CAPACITY)
                .refillIntervally(REFILL_TOKENS, REFILL_PERIOD)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * First value of X-Forwarded-For (the original client) when present,
     * otherwise the direct socket address. See the class-level security caveat.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletRequest request,
                                      HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Standards-compliant hint that pairs with the "60 seconds" message.
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(REFILL_PERIOD.toSeconds()));
        ApiErrorResponse body = new ApiErrorResponse(
                OffsetDateTime.now(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "TOO_MANY_REQUESTS",
                LIMIT_MESSAGE,
                request.getRequestURI());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
