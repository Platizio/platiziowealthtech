package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtServiceSecretValidationTest {

    @Test
    void validateSecretRejectsMissingJwtSecret() {
        JwtService jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", " ");

        assertThatThrownBy(jwtService::validateSecret)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JWT_SECRET must be set");
    }

    @Test
    void validateSecretAcceptsConfiguredJwtSecret() {
        JwtService jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", "configured-secret-with-enough-entropy");

        assertThatCode(jwtService::validateSecret).doesNotThrowAnyException();
    }
}
