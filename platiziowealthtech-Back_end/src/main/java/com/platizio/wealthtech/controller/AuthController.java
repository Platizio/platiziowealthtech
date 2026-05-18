package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.dto.AuthLoginRequest;
import com.platizio.wealthtech.dto.AuthResponse;
import com.platizio.wealthtech.dto.AuthSignupRequest;
import com.platizio.wealthtech.service.AuthCookieService;
import com.platizio.wealthtech.service.AuthService;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Endpoints for user signup and login")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieService authCookieService;

    public AuthController(AuthService authService, AuthCookieService authCookieService) {
        this.authService = authService;
        this.authCookieService = authCookieService;
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
        return authResponse;
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = authCookieService.readRefreshToken(request)
                .orElseThrow(() -> new BadCredentialsException("Refresh token is required"));
        AuthResponse authResponse = authService.refreshAccessToken(refreshToken);
        authCookieService.writeAccessToken(response, authResponse.token());
        return authResponse;
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

    @Scheduled(fixedDelayString = "${app.auth.blocked-token-purge-interval-ms:3600000}")
    public void purgeExpiredBlockedTokens() {
        authService.purgeExpiredBlockedTokens();
    }
}
