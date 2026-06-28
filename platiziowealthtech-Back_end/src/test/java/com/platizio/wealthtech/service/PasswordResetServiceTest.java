package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platizio.wealthtech.domain.AuditEvent;
import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.PasswordResetToken;
import com.platizio.wealthtech.dto.ForgotPasswordRequest;
import com.platizio.wealthtech.dto.ResetPasswordRequest;
import com.platizio.wealthtech.repository.AuditEventRepository;
import com.platizio.wealthtech.repository.DistributorRepository;
import com.platizio.wealthtech.repository.PasswordResetTokenRepository;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class PasswordResetServiceTest {

    @Test
    void requestResetWithExposeTokenEnabledReturnsOneTimeTokenAndResetConsumesIt() {
        UUID distributorId = UUID.randomUUID();
        Distributor distributor = new Distributor();
        ReflectionTestUtils.setField(distributor, "id", distributorId);
        distributor.setEmail("user@example.com");

        List<PasswordResetToken> tokens = new ArrayList<>();
        List<AuditEvent> audits = new ArrayList<>();
        RecordingRefreshTokenService refreshTokenService = new RecordingRefreshTokenService();
        PasswordResetService service = service(List.of(distributor), tokens, audits, refreshTokenService);

        var response = service.requestReset(new ForgotPasswordRequest(" USER@example.com "));

        assertThat(response.resetToken()).isNotBlank();
        assertThat(tokens).hasSize(1);
        PasswordResetToken savedToken = tokens.getFirst();
        assertThat(savedToken.getDistributorId()).isEqualTo(distributorId);
        assertThat(savedToken.getExpiresAt()).isNotNull();

        var resetResponse = service.resetPassword(new ResetPasswordRequest(response.resetToken(), "NewPass@123"));

        assertThat(resetResponse.get("message")).contains("successful");
        assertThat(distributor.getPasswordHash()).isEqualTo("encoded:NewPass@123");
        assertThat(refreshTokenService.revokedDistributorId).isEqualTo(distributorId);
        assertThat(savedToken.getUsedAt()).isNotNull();
        assertThat(audits).extracting(AuditEvent::getActionType)
                .containsExactly("PASSWORD_RESET_REQUESTED", "PASSWORD_RESET_COMPLETED");

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(response.resetToken(), "NewPass@123")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid or expired reset token");
    }

    @Test
    void requestResetNeverReturnsTokenWhenExposeDisabled() {
        // DF-13-adjacent: with the flag off (production / demo default), the raw
        // reset token must never leak in the response, even for a known account.
        UUID distributorId = UUID.randomUUID();
        Distributor distributor = new Distributor();
        ReflectionTestUtils.setField(distributor, "id", distributorId);
        distributor.setEmail("user@example.com");

        List<PasswordResetToken> tokens = new ArrayList<>();
        List<AuditEvent> audits = new ArrayList<>();
        PasswordResetService service =
                service(List.of(distributor), tokens, audits, new RecordingRefreshTokenService(), false);

        var response = service.requestReset(new ForgotPasswordRequest("user@example.com"));

        assertThat(response.resetToken()).isNull();
        // The token is still generated and persisted (hashed) so the email link works.
        assertThat(tokens).hasSize(1);
        assertThat(tokens.getFirst().getDistributorId()).isEqualTo(distributorId);
    }

    @Test
    void requestResetUsesGenericResponseForUnknownEmails() {
        List<PasswordResetToken> tokens = new ArrayList<>();
        List<AuditEvent> audits = new ArrayList<>();

        var response = service(List.of(), tokens, audits, new RecordingRefreshTokenService())
                .requestReset(new ForgotPasswordRequest("missing@example.com"));

        assertThat(response.message()).contains("If an account exists");
        assertThat(response.resetToken()).isNull();
        assertThat(tokens).isEmpty();
        assertThat(audits).isEmpty();
    }

    private PasswordResetService service(
            List<Distributor> distributors,
            List<PasswordResetToken> tokens,
            List<AuditEvent> audits,
            RecordingRefreshTokenService refreshTokenService
    ) {
        return service(distributors, tokens, audits, refreshTokenService, true);
    }

    private PasswordResetService service(
            List<Distributor> distributors,
            List<PasswordResetToken> tokens,
            List<AuditEvent> audits,
            RecordingRefreshTokenService refreshTokenService,
            boolean exposeDevToken
    ) {
        return new PasswordResetService(
                distributorRepository(distributors),
                passwordResetTokenRepository(tokens),
                passwordEncoder(),
                new AuditService(auditEventRepository(audits)),
                refreshTokenService,
                15,
                exposeDevToken
        );
    }

    private DistributorRepository distributorRepository(List<Distributor> distributors) {
        return proxy(DistributorRepository.class, (proxy, method, args) -> switch (method.getName()) {
            case "findByEmail" -> {
                String email = String.valueOf(args[0]).toLowerCase(Locale.ROOT);
                yield distributors.stream()
                        .filter(distributor -> distributor.getEmail().equalsIgnoreCase(email))
                        .findFirst();
            }
            case "findById" -> distributors.stream()
                    .filter(distributor -> distributor.getId().equals(args[0]))
                    .findFirst();
            case "save" -> args[0];
            default -> unhandled(method.getName());
        });
    }

    private PasswordResetTokenRepository passwordResetTokenRepository(List<PasswordResetToken> tokens) {
        return proxy(PasswordResetTokenRepository.class, (proxy, method, args) -> switch (method.getName()) {
            case "findByTokenHash" -> tokens.stream()
                    .filter(token -> token.getTokenHash().equals(args[0]))
                    .findFirst();
            case "findByDistributorIdAndUsedAtIsNull" -> tokens.stream()
                    .filter(token -> token.getDistributorId().equals(args[0]))
                    .filter(token -> token.getUsedAt() == null)
                    .toList();
            case "save" -> {
                PasswordResetToken token = (PasswordResetToken) args[0];
                if (!tokens.contains(token)) {
                    tokens.add(token);
                }
                yield token;
            }
            default -> unhandled(method.getName());
        });
    }

    private AuditEventRepository auditEventRepository(List<AuditEvent> audits) {
        return proxy(AuditEventRepository.class, (proxy, method, args) -> switch (method.getName()) {
            case "save" -> {
                AuditEvent event = (AuditEvent) args[0];
                audits.add(event);
                yield event;
            }
            default -> unhandled(method.getName());
        });
    }

    private PasswordEncoder passwordEncoder() {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return "encoded:" + rawPassword;
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return encodedPassword.equals(encode(rawPassword));
            }
        };
    }

    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        Object proxy = Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] { type },
                (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + " fake";
                            case "hashCode" -> System.identityHashCode(instance);
                            case "equals" -> instance == args[0];
                            default -> unhandled(method.getName());
                        };
                    }
                    return handler.invoke(instance, method, args);
                }
        );
        return type.cast(proxy);
    }

    private Object unhandled(String methodName) {
        throw new UnsupportedOperationException("Unexpected repository method in test: " + methodName);
    }

    private static class RecordingRefreshTokenService extends RefreshTokenService {
        private UUID revokedDistributorId;

        RecordingRefreshTokenService() {
            super(null, null, 0);
        }

        @Override
        public void revokeAllForDistributor(UUID distributorId) {
            this.revokedDistributorId = distributorId;
        }
    }
}
