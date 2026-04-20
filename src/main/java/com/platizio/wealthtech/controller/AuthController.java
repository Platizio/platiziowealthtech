package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.dto.AuthLoginRequest;
import com.platizio.wealthtech.dto.AuthResponse;
import com.platizio.wealthtech.dto.AuthSignupRequest;
import com.platizio.wealthtech.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    public AuthResponse signup(@Valid @RequestBody AuthSignupRequest request) {
        return authService.signup(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthLoginRequest request) {
        return authService.login(request);
    }
}
