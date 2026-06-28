package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtServiceInvestorTokenTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", "test-secret-key-at-least-32-bytes-long-1234567890");
        ReflectionTestUtils.setField(jwtService, "expirationMs", 900000L);
        ReflectionTestUtils.setField(jwtService, "investorExpirationMs", 3600000L);
    }

    @Test
    void investorTokenCarriesInvestorTypeAndSubject() {
        UUID accountId = UUID.randomUUID();

        String token = jwtService.generateInvestorToken(accountId, "investor@example.com");

        assertThat(jwtService.extractType(token)).isEqualTo(JwtService.TYPE_INVESTOR);
        assertThat(jwtService.extractSubject(token)).isEqualTo(accountId.toString());
        assertThat(jwtService.extractEmail(token)).isEqualTo("investor@example.com");
        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    @Test
    void distributorTokenCarriesDistributorType() {
        String token = jwtService.generateToken(UUID.randomUUID(), "dist@example.com", "MASTER_DISTRIBUTOR");

        assertThat(jwtService.extractType(token)).isEqualTo(JwtService.TYPE_DISTRIBUTOR);
    }
}
