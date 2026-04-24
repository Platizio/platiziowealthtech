package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.dto.AuthLoginRequest;
import com.platizio.wealthtech.dto.AuthResponse;
import com.platizio.wealthtech.dto.AuthSignupRequest;
import com.platizio.wealthtech.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Endpoints for user signup and login")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Register a new distributor", description = "Creates a new distributor account and returns a JWT token.")
    @ApiResponse(responseCode = "200", description = "Successfully signed up", 
                 content = @Content(schema = @Schema(implementation = AuthResponse.class)))
    @PostMapping("/signup")
    public AuthResponse signup(@Valid @RequestBody AuthSignupRequest request) {
        return authService.signup(request);
    }

    @Operation(summary = "Login to get a JWT token", description = "Exchanges email and password for a JWT token to be used in subsequent requests.")
    @ApiResponse(responseCode = "200", description = "Successfully logged in", 
                 content = @Content(schema = @Schema(implementation = AuthResponse.class)))
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthLoginRequest request) {
        return authService.login(request);
    }
}
