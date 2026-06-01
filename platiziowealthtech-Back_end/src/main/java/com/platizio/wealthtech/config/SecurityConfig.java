package com.platizio.wealthtech.config;

import com.platizio.wealthtech.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platizio.wealthtech.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.time.OffsetDateTime;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final LoginRateLimitFilter loginRateLimitFilter;
    private final CustomUserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          LoginRateLimitFilter loginRateLimitFilter,
                          CustomUserDetailsService userDetailsService,
                          ObjectMapper objectMapper) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.loginRateLimitFilter = loginRateLimitFilter;
        this.userDetailsService = userDetailsService;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/auth/**",
                    // NOTE: /api/v1/debug/external-auth/** is deliberately NOT
                    // permit-listed. The ExternalAuthDebugController is already
                    // gated by @ConditionalOnProperty(external-auth.debug.enabled,
                    // default=false) so the bean only exists when explicitly
                    // opted in; on top of that, when it IS enabled it must still
                    // require an authenticated principal (falls through to
                    // .anyRequest().authenticated() below). Do not re-add it here.
                    "/actuator/health",
                    "/actuator/info",
                    "/v3/api-docs/**",
                    "/v3/api-docs",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/swagger-ui/index.html",
                    "/investor-actions/**",
                    "/investor-action/**",
                    "/favicon.ico",
                    "/"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    ApiErrorResponse error = new ApiErrorResponse(
                            OffsetDateTime.now(), 401, "UNAUTHORIZED", 
                            authException.getMessage(), request.getRequestURI());
                    response.getWriter().write(objectMapper.writeValueAsString(error));
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    ApiErrorResponse error = new ApiErrorResponse(
                            OffsetDateTime.now(), 403, "FORBIDDEN", 
                            "Access denied", request.getRequestURI());
                    response.getWriter().write(objectMapper.writeValueAsString(error));
                })
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            // Rate limiter runs ahead of JwtAuthFilter so an attacker flooding
            // /api/v1/auth/login is rejected with 429 before any work is done.
            .addFilterBefore(loginRateLimitFilter, JwtAuthFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        // B-73: pin allowed request headers to the actual set the app uses.
        // Previously this was List.of("*"), which the Fetch spec treats as
        // literal (not a wildcard) when allowCredentials=true — Spring papered
        // over that by reflecting requested headers back, but the loose
        // posture violated least-privilege.
        //   • Content-Type      — apiFetch sends "application/json" on every
        //                         JSON POST/PUT/PATCH.
        //   • Authorization     — not used today (auth is via HttpOnly cookies)
        //                         but standard and forward-compatible.
        //   • X-Requested-With  — legacy AJAX convention; harmless to permit.
        //   • X-Forwarded-For   — read by LoginRateLimitFilter (B-46) for
        //                         per-IP rate limiting when behind a proxy.
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "X-Forwarded-For"));
        // B-73: do NOT expose Set-Cookie. It is on the Fetch spec's forbidden
        // response-header list — JavaScript can never read it via
        // response.headers.get('Set-Cookie') regardless of CORS exposure, so
        // the prior setExposedHeaders(List.of("Set-Cookie")) was zero-effect
        // misleading config. Auth in this app intentionally uses HttpOnly
        // cookies so JS *cannot* see them; exposing Set-Cookie suggested the
        // opposite.
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
