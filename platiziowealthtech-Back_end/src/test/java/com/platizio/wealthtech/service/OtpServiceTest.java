package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.EmailOtp;
import com.platizio.wealthtech.domain.OtpPurpose;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.repository.EmailOtpRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.BadCredentialsException;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock
    private EmailOtpRepository otpRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private Environment environment;

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        otpService = new OtpService(otpRepository, emailService, environment, 6, 5, 5, 30);
        lenient().when(otpRepository.save(any(EmailOtp.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void requestOtpGeneratesCodeAndReturnsDevCodeWhenEmailDisabledOnLocalProfile() {
        when(otpRepository.findByEmailAndPurposeAndConsumedAtIsNull("user@example.com", OtpPurpose.LOGIN))
                .thenReturn(List.of());
        when(emailService.sendHtml(anyString(), anyString(), anyString())).thenReturn(false);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);

        OtpRequestResponse response = otpService.requestOtp("User@Example.com", OtpPurpose.LOGIN);

        assertThat(response.devCode()).isNotNull().hasSize(6);
        assertThat(response.expiresInSeconds()).isEqualTo(300);
        assertThat(response.resendInSeconds()).isEqualTo(30);

        ArgumentCaptor<EmailOtp> captor = ArgumentCaptor.forClass(EmailOtp.class);
        verify(otpRepository).save(captor.capture());
        EmailOtp saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("user@example.com");
        assertThat(saved.getCodeHash()).isNotBlank();
        assertThat(saved.getCodeHash()).doesNotContain(response.devCode()); // stored hashed, not plaintext
    }

    @Test
    void verifySucceedsWithCorrectCodeAndBurnsIt() {
        when(otpRepository.findByEmailAndPurposeAndConsumedAtIsNull(anyString(), eq(OtpPurpose.LOGIN)))
                .thenReturn(List.of());
        when(emailService.sendHtml(anyString(), anyString(), anyString())).thenReturn(false);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);

        ArgumentCaptor<EmailOtp> captor = ArgumentCaptor.forClass(EmailOtp.class);
        OtpRequestResponse response = otpService.requestOtp("user@example.com", OtpPurpose.LOGIN);
        verify(otpRepository).save(captor.capture());
        EmailOtp saved = captor.getValue();

        when(otpRepository.findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                "user@example.com", OtpPurpose.LOGIN)).thenReturn(Optional.of(saved));

        otpService.verify("user@example.com", OtpPurpose.LOGIN, response.devCode());

        assertThat(saved.getConsumedAt()).isNotNull();
    }

    @Test
    void verifyRejectsWrongCodeAndIncrementsAttempts() {
        when(otpRepository.findByEmailAndPurposeAndConsumedAtIsNull(anyString(), eq(OtpPurpose.LOGIN)))
                .thenReturn(List.of());
        when(emailService.sendHtml(anyString(), anyString(), anyString())).thenReturn(false);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);

        ArgumentCaptor<EmailOtp> captor = ArgumentCaptor.forClass(EmailOtp.class);
        otpService.requestOtp("user@example.com", OtpPurpose.LOGIN);
        verify(otpRepository).save(captor.capture());
        EmailOtp saved = captor.getValue();

        when(otpRepository.findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                "user@example.com", OtpPurpose.LOGIN)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> otpService.verify("user@example.com", OtpPurpose.LOGIN, "000000"))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(saved.getAttempts()).isEqualTo(1);
        assertThat(saved.getConsumedAt()).isNull();
    }

    @Test
    void verifyThrowsWhenNoActiveCodeExists() {
        when(otpRepository.findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                "user@example.com", OtpPurpose.LOGIN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> otpService.verify("user@example.com", OtpPurpose.LOGIN, "123456"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void requestOtpRejectsInvalidEmail() {
        assertThatThrownBy(() -> otpService.requestOtp("not-an-email", OtpPurpose.LOGIN))
                .isInstanceOf(IllegalArgumentException.class);
        verify(otpRepository, never()).save(any());
    }
}
