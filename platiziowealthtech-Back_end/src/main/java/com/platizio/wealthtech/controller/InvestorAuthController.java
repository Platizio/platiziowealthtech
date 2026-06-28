package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.OtpPurpose;
import com.platizio.wealthtech.dto.InvestorAuthResponse;
import com.platizio.wealthtech.dto.InvestorOtpRequest;
import com.platizio.wealthtech.dto.InvestorOtpVerifyRequest;
import com.platizio.wealthtech.dto.InvestorSignupRequest;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.service.AuthCookieService;
import com.platizio.wealthtech.service.BlockedTokenService;
import com.platizio.wealthtech.service.InvestorAuthService;
import com.platizio.wealthtech.service.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Passwordless investor-portal auth (SRS FR-AUTH). Public (permitAll) — issues the
 * HttpOnly investor cookie on a verified login/signup. The {@code purpose} on the
 * OTP request is the client value {@code LOGIN}/{@code SIGNUP}, mapped here to the
 * investor-specific {@link OtpPurpose} so it can never collide with distributor OTPs.
 */
@RestController
@RequestMapping("/api/v1/investor-auth")
@Tag(name = "Investor Authentication", description = "Passwordless investor-portal signup and login")
public class InvestorAuthController {

    private final InvestorAuthService investorAuthService;
    private final AuthCookieService authCookieService;
    private final JwtService jwtService;
    private final BlockedTokenService blockedTokenService;

    public InvestorAuthController(
            InvestorAuthService investorAuthService,
            AuthCookieService authCookieService,
            JwtService jwtService,
            BlockedTokenService blockedTokenService) {
        this.investorAuthService = investorAuthService;
        this.authCookieService = authCookieService;
        this.jwtService = jwtService;
        this.blockedTokenService = blockedTokenService;
    }

    @Operation(summary = "Request an investor email OTP",
            description = "Emails a one-time passcode for LOGIN or SIGNUP. Always returns a generic message to avoid leaking which emails are registered.")
    @PostMapping("/otp/request")
    public OtpRequestResponse requestOtp(@Valid @RequestBody InvestorOtpRequest request) {
        return investorAuthService.requestOtp(request.email(), mapPurpose(request.purpose()));
    }

    @Operation(summary = "Verify an investor login OTP",
            description = "Validates the passcode and, on success, sets the HttpOnly investor cookie.")
    @PostMapping("/login/otp/verify")
    public InvestorAuthResponse verifyLoginOtp(
            @Valid @RequestBody InvestorOtpVerifyRequest request, HttpServletResponse response) {
        InvestorAuthService.InvestorAuthResult result = investorAuthService.otpLogin(request.email(), request.code());
        authCookieService.writeInvestorAccessToken(response, result.token());
        return InvestorAuthResponse.from(result.account());
    }

    @Operation(summary = "Investor self-signup",
            description = "Creates the passwordless investor account (after email OTP) and sets the HttpOnly investor cookie.")
    @PostMapping("/signup")
    public InvestorAuthResponse signup(
            @Valid @RequestBody InvestorSignupRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        InvestorAuthService.InvestorAuthResult result = investorAuthService.signup(
                request, clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        authCookieService.writeInvestorAccessToken(response, result.token());
        return InvestorAuthResponse.from(result.account());
    }

    @Operation(summary = "Investor logout", description = "Revokes the current investor token (jti) and clears the HttpOnly cookie.")
    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request, HttpServletResponse response) {
        // Revoke server-side too (parity with distributor logout): clearing the cookie
        // alone leaves a stolen token usable until it expires.
        authCookieService.readInvestorAccessToken(request).ifPresent(token -> {
            if (jwtService.isTokenValid(token)) {
                blockedTokenService.block(jwtService.extractJti(token), jwtService.extractExpiresAt(token));
            }
        });
        authCookieService.clearInvestorAccessToken(response);
        return Map.of("status", "logged_out");
    }

    private OtpPurpose mapPurpose(String purpose) {
        String normalized = purpose == null ? "" : purpose.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LOGIN" -> OtpPurpose.INVESTOR_LOGIN;
            case "SIGNUP" -> OtpPurpose.INVESTOR_SIGNUP;
            default -> throw new IllegalArgumentException("Unsupported OTP purpose: " + purpose);
        };
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
