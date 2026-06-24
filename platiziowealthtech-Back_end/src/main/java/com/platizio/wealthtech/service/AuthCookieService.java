package com.platizio.wealthtech.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class AuthCookieService {

    private static final Logger logger = LoggerFactory.getLogger(AuthCookieService.class);

    private final String cookieName;
    private final String refreshCookieName;
    private final String investorCookieName;
    private final boolean secure;
    private final String sameSite;
    private final long expirationMs;
    private final long refreshExpirationMs;
    private final long investorExpirationMs;

    public AuthCookieService(
            @Value("${app.auth.cookie-name:access_token}") String cookieName,
            @Value("${app.auth.refresh-cookie-name:refresh_token}") String refreshCookieName,
            @Value("${app.auth.investor-cookie-name:investor_access_token}") String investorCookieName,
            @Value("${app.auth.cookie-secure}") boolean secure,
            @Value("${app.auth.cookie-same-site:Lax}") String sameSite,
            @Value("${jwt.expiration-ms}") long expirationMs,
            @Value("${app.auth.refresh-token-expiration-ms}") long refreshExpirationMs,
            @Value("${app.auth.investor-access-expiration-ms:3600000}") long investorExpirationMs
    ) {
        this.cookieName = cookieName;
        this.refreshCookieName = refreshCookieName;
        this.investorCookieName = investorCookieName;
        this.secure = secure;
        this.sameSite = resolveSameSite(sameSite, secure);
        this.expirationMs = expirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
        this.investorExpirationMs = investorExpirationMs;
    }

    public String investorCookieName() {
        return investorCookieName;
    }

    /** Read the investor access token from its dedicated cookie (or Bearer header). */
    public Optional<String> readInvestorAccessToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return Optional.of(authHeader.substring(7));
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> investorCookieName.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    public void writeInvestorAccessToken(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(investorCookieName, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ofMillis(investorExpirationMs))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearInvestorAccessToken(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(investorCookieName, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String resolveSameSite(String configured, boolean secure) {
        if ("None".equalsIgnoreCase(configured) && !secure) {
            logger.warn(
                    "app.auth.cookie-same-site=None requires cookie-secure=true; using Lax for HTTP dev so login cookies are stored.");
            return "Lax";
        }
        return configured;
    }

    public String cookieName() {
        return cookieName;
    }

    public String refreshCookieName() {
        return refreshCookieName;
    }

    public Optional<String> readAccessToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return Optional.of(authHeader.substring(7));
        }

        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }

        return Arrays.stream(cookies)
                .filter(cookie -> cookieName.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    public void writeAccessToken(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ofMillis(expirationMs))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void writeRefreshToken(HttpServletResponse response, UUID refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, refreshToken.toString())
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ofMillis(refreshExpirationMs))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearAccessToken(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearRefreshToken(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public Optional<String> readRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> refreshCookieName.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }
}
