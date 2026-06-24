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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock
    private EmailOtpRepository otpRepository;

    @Mock
    private EmailService emailService;

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        // exposeDevCode = true so the devCode-dependent tests can read the issued code.
        otpService = new OtpService(otpRepository, emailService, 6, 5, 5, 30, true);
        lenient().when(otpRepository.save(any(EmailOtp.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void requestOtpGeneratesCodeAndReturnsDevCodeWhenExposeDevCodeEnabled() {
        when(otpRepository.findByEmailAndPurposeAndConsumedAtIsNull("user@example.com", OtpPurpose.LOGIN))
                .thenReturn(List.of());
        when(emailService.sendHtml(anyString(), anyString(), anyString())).thenReturn(false);

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
    void requestOtpNeverReturnsDevCodeWhenExposeDevCodeDisabled() {
        // DF-13: with the flag off (production / demo default), the live code must
        // never leak in the API response even when email delivery is disabled.
        OtpService prodOtpService = new OtpService(otpRepository, emailService, 6, 5, 5, 30, false);
        when(otpRepository.findByEmailAndPurposeAndConsumedAtIsNull("user@example.com", OtpPurpose.LOGIN))
                .thenReturn(List.of());
        when(emailService.sendHtml(anyString(), anyString(), anyString())).thenReturn(false);

        OtpRequestResponse response = prodOtpService.requestOtp("user@example.com", OtpPurpose.LOGIN);

        assertThat(response.devCode()).isNull();
        // The challenge is still persisted (hashed) so verification works against the logged code.
        verify(otpRepository).save(any(EmailOtp.class));
    }

    @Test
    void verifySucceedsWithCorrectCodeAndBurnsIt() {
        when(otpRepository.findByEmailAndPurposeAndConsumedAtIsNull(anyString(), eq(OtpPurpose.LOGIN)))
                .thenReturn(List.of());
        when(emailService.sendHtml(anyString(), anyString(), anyString())).thenReturn(false);

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

    // ---- reference-scoped binding (transaction-approval cross-consume guard) ----

    @Test
    void requestOtpWithReferenceBindsCodeToReferenceAndSkipsUnscopedLookup() {
        UUID ref = UUID.randomUUID();
        when(otpRepository.findByEmailAndPurposeAndReferenceIdAndConsumedAtIsNull(
                "user@example.com", OtpPurpose.TRANSACTION_APPROVAL, ref)).thenReturn(List.of());
        when(emailService.sendHtml(anyString(), anyString(), anyString())).thenReturn(false);

        otpService.requestOtp("User@Example.com", OtpPurpose.TRANSACTION_APPROVAL, ref);

        ArgumentCaptor<EmailOtp> captor = ArgumentCaptor.forClass(EmailOtp.class);
        verify(otpRepository).save(captor.capture());
        assertThat(captor.getValue().getReferenceId()).isEqualTo(ref);
        // Invalidation is scoped to this reference, so a sibling challenge's live code is untouched.
        verify(otpRepository, never()).findByEmailAndPurposeAndConsumedAtIsNull(anyString(), any());
    }

    @Test
    void verifyWithReferenceMatchesOnlyTheBoundChallengeCode() {
        UUID ref = UUID.randomUUID();
        when(otpRepository.findByEmailAndPurposeAndReferenceIdAndConsumedAtIsNull(
                anyString(), eq(OtpPurpose.TRANSACTION_APPROVAL), eq(ref))).thenReturn(List.of());
        when(emailService.sendHtml(anyString(), anyString(), anyString())).thenReturn(false);

        ArgumentCaptor<EmailOtp> captor = ArgumentCaptor.forClass(EmailOtp.class);
        OtpRequestResponse response =
                otpService.requestOtp("user@example.com", OtpPurpose.TRANSACTION_APPROVAL, ref);
        verify(otpRepository).save(captor.capture());
        EmailOtp bound = captor.getValue();

        when(otpRepository.findFirstByEmailAndPurposeAndReferenceIdAndConsumedAtIsNullOrderByCreatedAtDesc(
                "user@example.com", OtpPurpose.TRANSACTION_APPROVAL, ref)).thenReturn(Optional.of(bound));

        otpService.verify("user@example.com", OtpPurpose.TRANSACTION_APPROVAL, response.devCode(), ref);

        assertThat(bound.getConsumedAt()).isNotNull();
        // The unscoped "latest by (email,purpose)" lookup — which could grab a sibling
        // challenge's code — is never consulted on the reference-bound path.
        verify(otpRepository, never())
                .findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(anyString(), any());
    }
}
