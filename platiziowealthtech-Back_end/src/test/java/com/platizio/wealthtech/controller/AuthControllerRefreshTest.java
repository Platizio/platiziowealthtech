package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.DistributorStatus;
import com.platizio.wealthtech.dto.AuthLoginRequest;
import com.platizio.wealthtech.dto.AuthResponse;
import com.platizio.wealthtech.service.AuthCookieService;
import com.platizio.wealthtech.service.AuthService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.HttpHeaders;

class AuthControllerRefreshTest {

    @Test
    void loginWritesAccessAndRefreshCookies() {
        UUID refreshToken = UUID.randomUUID();
        RecordingAuthService authService = new RecordingAuthService(refreshToken);
        AuthController controller = new AuthController(authService, cookieService(), null, null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AuthResponse authResponse = controller.login(new AuthLoginRequest("user@example.com", "password"), response);

        assertThat(authResponse.token()).isNull();
        List<String> cookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).anySatisfy(cookie -> {
            assertThat(cookie).contains("access_token=access.jwt");
            assertThat(cookie).contains("HttpOnly");
            assertThat(cookie).contains("Max-Age=900");
        });
        assertThat(cookies).anySatisfy(cookie -> {
            assertThat(cookie).contains("refresh_token=" + refreshToken);
            assertThat(cookie).contains("HttpOnly");
            assertThat(cookie).contains("Max-Age=604800");
        });
    }

    @Test
    void refreshUsesRefreshCookieAndWritesNewAccessCookie() {
        UUID refreshToken = UUID.randomUUID();
        UUID rotatedRefreshToken = UUID.randomUUID();
        RecordingAuthService authService = new RecordingAuthService(refreshToken, rotatedRefreshToken);
        AuthController controller = new AuthController(authService, cookieService(), null, null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", refreshToken.toString()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AuthResponse authResponse = controller.refresh(request, response);

        assertThat(authService.refreshedToken).isEqualTo(refreshToken.toString());
        assertThat(authResponse.message()).isEqualTo("Token refreshed");
        assertThat(authResponse.token()).isNull();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(cookie -> assertThat(cookie).contains("access_token=refreshed.jwt"));
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(cookie -> assertThat(cookie).contains("refresh_token=" + rotatedRefreshToken));
    }

    @Test
    void logoutRevokesRefreshTokenAndClearsBothCookies() {
        UUID refreshToken = UUID.randomUUID();
        RecordingAuthService authService = new RecordingAuthService(refreshToken);
        AuthController controller = new AuthController(authService, cookieService(), null, null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie("access_token", "access.jwt"),
                new Cookie("refresh_token", refreshToken.toString())
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.logout(request, response);

        assertThat(authService.blockedAccessToken).isEqualTo("access.jwt");
        assertThat(authService.revokedToken).isEqualTo(refreshToken.toString());
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(cookie -> assertThat(cookie).contains("access_token=").contains("Max-Age=0"));
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(cookie -> assertThat(cookie).contains("refresh_token=").contains("Max-Age=0"));
    }

    private AuthCookieService cookieService() {
        return new AuthCookieService(
                "access_token",
                "refresh_token",
                "investor_access_token",
                false,
                "Lax",
                900_000,
                604_800_000,
                3_600_000
        );
    }

    private static class RecordingAuthService extends AuthService {

        private final UUID refreshToken;
        private final UUID rotatedRefreshToken;
        private String refreshedToken;
        private String revokedToken;
        private String blockedAccessToken;

        RecordingAuthService(UUID refreshToken) {
            this(refreshToken, UUID.randomUUID());
        }

        RecordingAuthService(UUID refreshToken, UUID rotatedRefreshToken) {
            super(null, null, null, null, null, null, null);
            this.refreshToken = refreshToken;
            this.rotatedRefreshToken = rotatedRefreshToken;
        }

        @Override
        public AuthResponse login(AuthLoginRequest request) {
            return response("access.jwt", "Login successful");
        }

        @Override
        public UUID createRefreshToken(UUID distributorId) {
            return refreshToken;
        }

        @Override
        public RefreshResult refresh(String refreshToken) {
            refreshedToken = refreshToken;
            return new RefreshResult(response("refreshed.jwt", "Token refreshed"), rotatedRefreshToken);
        }

        @Override
        public void revokeRefreshToken(String refreshToken) {
            revokedToken = refreshToken;
        }

        @Override
        public void blockAccessToken(String accessToken) {
            blockedAccessToken = accessToken;
        }

        private AuthResponse response(String token, String message) {
            return new AuthResponse(
                    token,
                    UUID.randomUUID(),
                    "user@example.com",
                    "User",
                    DistributorRole.SUB_DISTRIBUTOR,
                    DistributorStatus.APPROVED,
                    message
            );
        }
    }
}
