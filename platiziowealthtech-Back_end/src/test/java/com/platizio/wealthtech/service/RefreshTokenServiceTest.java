package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.AuthRefreshToken;
import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.repository.AuthRefreshTokenRepository;
import com.platizio.wealthtech.repository.DistributorRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RefreshTokenServiceTest {

    @Test
    void createTokenStoresSha256HashOnly() {
        AuthRefreshTokenRepository refreshTokenRepository = mock(AuthRefreshTokenRepository.class);
        DistributorRepository distributorRepository = mock(DistributorRepository.class);
        RefreshTokenService service = new RefreshTokenService(
                refreshTokenRepository,
                distributorRepository,
                604_800_000
        );
        when(refreshTokenRepository.save(any(AuthRefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UUID rawToken = service.createToken(UUID.randomUUID());

        ArgumentCaptor<AuthRefreshToken> tokenCaptor = ArgumentCaptor.forClass(AuthRefreshToken.class);
        verify(refreshTokenRepository).save(tokenCaptor.capture());
        AuthRefreshToken savedToken = tokenCaptor.getValue();
        assertThat(savedToken.getTokenHash()).hasSize(64);
        assertThat(savedToken.getTokenHash()).isEqualTo(service.sha256(rawToken.toString()));
        assertThat(savedToken.getTokenHash()).isNotEqualTo(rawToken.toString());
        assertThat(savedToken.getRevoked()).isFalse();
        assertThat(savedToken.getExpiresAt()).isAfter(OffsetDateTime.now().plusDays(6));
    }

    @Test
    void rotateMarksOldTokenRevokedAndStoresNewHash() {
        UUID distributorId = UUID.randomUUID();
        UUID rawToken = UUID.randomUUID();
        AuthRefreshTokenRepository refreshTokenRepository = mock(AuthRefreshTokenRepository.class);
        DistributorRepository distributorRepository = mock(DistributorRepository.class);
        RefreshTokenService service = new RefreshTokenService(
                refreshTokenRepository,
                distributorRepository,
                604_800_000
        );
        AuthRefreshToken existingToken = new AuthRefreshToken();
        existingToken.setDistributorId(distributorId);
        existingToken.setTokenHash(service.sha256(rawToken.toString()));
        existingToken.setExpiresAt(OffsetDateTime.now().plusDays(1));
        existingToken.setRevoked(false);
        when(refreshTokenRepository.findByTokenHash(service.sha256(rawToken.toString())))
                .thenReturn(Optional.of(existingToken));
        when(distributorRepository.findById(distributorId)).thenReturn(Optional.of(new Distributor()));
        when(refreshTokenRepository.save(any(AuthRefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RefreshTokenService.RotatedRefreshToken rotated = service.rotate(rawToken.toString());

        ArgumentCaptor<AuthRefreshToken> tokenCaptor = ArgumentCaptor.forClass(AuthRefreshToken.class);
        verify(refreshTokenRepository, org.mockito.Mockito.times(2)).save(tokenCaptor.capture());
        List<AuthRefreshToken> savedTokens = tokenCaptor.getAllValues();
        assertThat(savedTokens.get(0)).isSameAs(existingToken);
        assertThat(savedTokens.get(0).getRevoked()).isTrue();
        assertThat(savedTokens.get(0).getRevokedAt()).isNotNull();
        assertThat(rotated.refreshToken()).isNotEqualTo(rawToken);
        assertThat(savedTokens.get(1).getTokenHash()).isEqualTo(service.sha256(rotated.refreshToken().toString()));
        assertThat(savedTokens.get(1).getTokenHash()).isNotEqualTo(rotated.refreshToken().toString());
    }

    @Test
    void rotateWithinGracePeriodReusesNewTokenWithoutRevokingAgain() {
        UUID distributorId = UUID.randomUUID();
        UUID rawToken = UUID.randomUUID();
        AuthRefreshTokenRepository refreshTokenRepository = mock(AuthRefreshTokenRepository.class);
        DistributorRepository distributorRepository = mock(DistributorRepository.class);
        RefreshTokenService service = new RefreshTokenService(
                refreshTokenRepository,
                distributorRepository,
                604_800_000
        );
        AuthRefreshToken existingToken = new AuthRefreshToken();
        existingToken.setDistributorId(distributorId);
        existingToken.setTokenHash(service.sha256(rawToken.toString()));
        existingToken.setExpiresAt(OffsetDateTime.now().plusDays(1));
        existingToken.setRevoked(false);
        when(refreshTokenRepository.findByTokenHash(service.sha256(rawToken.toString())))
                .thenReturn(Optional.of(existingToken));
        when(distributorRepository.findById(distributorId)).thenReturn(Optional.of(new Distributor()));
        when(refreshTokenRepository.save(any(AuthRefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RefreshTokenService.RotatedRefreshToken first = service.rotate(rawToken.toString());
        RefreshTokenService.RotatedRefreshToken second = service.rotate(rawToken.toString());

        assertThat(second.refreshToken()).isEqualTo(first.refreshToken());
        verify(refreshTokenRepository, org.mockito.Mockito.times(2)).save(any(AuthRefreshToken.class));
    }
}
