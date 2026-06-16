package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.OtpPurpose;
import com.platizio.wealthtech.dto.ArnValidationRequest;
import com.platizio.wealthtech.dto.ArnValidationResponse;
import com.platizio.wealthtech.dto.AuthLoginRequest;
import com.platizio.wealthtech.dto.AuthResponse;
import com.platizio.wealthtech.dto.AuthSignupRequest;
import com.platizio.wealthtech.dto.DistributorVerificationStatusResponse;
import com.platizio.wealthtech.dto.ForgotPasswordRequest;
import com.platizio.wealthtech.dto.ForgotPasswordResponse;
import com.platizio.wealthtech.dto.OtpRequest;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.dto.OtpVerifyRequest;
import com.platizio.wealthtech.dto.ResetPasswordRequest;
import com.platizio.wealthtech.service.ArnValidationService;
import com.platizio.wealthtech.service.AuthCookieService;
import com.platizio.wealthtech.service.AuthService;
import com.platizio.wealthtech.service.OtpService;
import com.platizio.wealthtech.service.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Endpoints for user signup and login")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieService authCookieService;
    private final PasswordResetService passwordResetService;
    private final OtpService otpService;
    private final ArnValidationService arnValidationService;

    public AuthController(
            AuthService authService,
            AuthCookieService authCookieService,
            PasswordResetService passwordResetService,
            OtpService otpService,
            ArnValidationService arnValidationService
    ) {
        this.authService = authService;
        this.authCookieService = authCookieService;
        this.passwordResetService = passwordResetService;
        this.otpService = otpService;
        this.arnValidationService = arnValidationService;
    }

    @Operation(summary = "Validate a distributor ARN",
            description = "Validates an AMFI Registration Number (ARN) / KYD status with the configured provider "
                    + "before signup. Public — used by the distributor signup form.")
    @PostMapping("/arn-validation")
    public ArnValidationResponse validateArn(@Valid @RequestBody ArnValidationRequest request) {
        return arnValidationService.toResponse(arnValidationService.validate(request.arnNumber()));
    }

    @Operation(summary = "Distributor verification status",
            description = "Returns the authenticated distributor's ARN/KYD verdict and account approval state.")
    @GetMapping("/verification-status")
    public DistributorVerificationStatusResponse verificationStatus(Principal principal) {
        if (principal == null) {
            throw new BadCredentialsException("Not authenticated");
        }
        return authService.verificationStatus(principal.getName());
    }

    @Operation(summary = "Register a new distributor", description = "Creates a new distributor account and sets an HttpOnly JWT cookie.")
    @ApiResponse(responseCode = "200", description = "Successfully signed up", 
                 content = @Content(schema = @Schema(implementation = AuthResponse.class)))
    @PostMapping("/signup")
    public AuthResponse signup(@Valid @RequestBody AuthSignupRequest request, HttpServletResponse response) {
        AuthResponse authResponse = authService.signup(request);
        if (authResponse.token() != null && !authResponse.token().isBlank()) {
            authCookieService.writeAccessToken(response, authResponse.token());
        }
        return authResponse;
    }

    @Operation(summary = "Login", description = "Exchanges email and password for an HttpOnly JWT cookie.")
    @ApiResponse(responseCode = "200", description = "Successfully logged in", 
                 content = @Content(schema = @Schema(implementation = AuthResponse.class)))
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthLoginRequest request, HttpServletResponse response) {
        AuthResponse authResponse = authService.login(request);
        authCookieService.writeAccessToken(response, authResponse.token());
        UUID refreshToken = authService.createRefreshToken(authResponse.distributorId());
        authCookieService.writeRefreshToken(response, refreshToken);
        return withoutToken(authResponse);
    }

    @Operation(summary = "Request an email OTP", description = "Emails a one-time passcode for LOGIN or SIGNUP. Always returns a generic message to avoid leaking which emails are registered.")
    @PostMapping("/otp/request")
    public OtpRequestResponse requestOtp(@Valid @RequestBody OtpRequest request) {
        if (!authService.isOtpEligible(request.email(), request.purpose())) {
            return otpService.genericResponse();
        }
        return otpService.requestOtp(request.email(), request.purpose());
    }

    @Operation(summary = "Verify an email OTP", description = "Validates the passcode. For LOGIN it issues HttpOnly auth cookies; for SIGNUP it confirms the email is verified.")
    @ApiResponse(responseCode = "401", description = "Invalid or expired code")
    @PostMapping("/otp/verify")
    public AuthResponse verifyOtp(@Valid @RequestBody OtpVerifyRequest request, HttpServletResponse response) {
        otpService.verify(request.email(), request.purpose(), request.code());

        if (request.purpose() == OtpPurpose.LOGIN) {
            AuthResponse authResponse = authService.otpLogin(request.email());
            authCookieService.writeAccessToken(response, authResponse.token());
            UUID refreshToken = authService.createRefreshToken(authResponse.distributorId());
            authCookieService.writeRefreshToken(response, refreshToken);
            return withoutToken(authResponse);
        }

        // SIGNUP: the email is now proven; the client proceeds to the signup form.
        return new AuthResponse(null, null, request.email(), null, null, null, "Email verified");
    }

    @PostMapping("/forgot-password")
    public ForgotPasswordResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return passwordResetService.requestReset(request);
    }

    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return passwordResetService.resetPassword(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = authCookieService.readRefreshToken(request)
                .orElseThrow(() -> new BadCredentialsException("Refresh token is required"));
        AuthService.RefreshResult refreshResult = authService.refresh(refreshToken);
        authCookieService.writeAccessToken(response, refreshResult.authResponse().token());
        authCookieService.writeRefreshToken(response, refreshResult.refreshToken());
        return withoutToken(refreshResult.authResponse());
    }

    @GetMapping("/me")
    public AuthResponse me(Principal principal) {
        if (principal == null) {
            throw new org.springframework.security.authentication.BadCredentialsException("Not authenticated");
        }
        return authService.currentUser(principal.getName());
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request, HttpServletResponse response) {
        authCookieService.readAccessToken(request).ifPresent(authService::blockAccessToken);
        authCookieService.readRefreshToken(request).ifPresent(authService::revokeRefreshToken);
        authCookieService.clearAccessToken(response);
        authCookieService.clearRefreshToken(response);
        return Map.of("status", "logged_out");
    }

    private AuthResponse withoutToken(AuthResponse response) {
        return new AuthResponse(
                null,
                response.distributorId(),
                response.email(),
                response.fullName(),
                response.role(),
                response.status(),
                response.message()
        );
    }
}
