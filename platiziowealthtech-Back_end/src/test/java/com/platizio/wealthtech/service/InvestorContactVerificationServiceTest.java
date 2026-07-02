package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.ContactChannel;
import com.platizio.wealthtech.domain.ContactVerificationMethod;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.integration.SupabaseAuthClient;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InvestorContactVerificationServiceTest {

    @Mock private InvestorRepository investorRepository;
    @Mock private SupabaseAuthClient supabaseAuthClient;
    @Mock private AuditService auditService;

    private InvestorContactVerificationService service;

    private final UUID investorId = UUID.randomUUID();
    private final UUID distributorId = UUID.randomUUID();
    private Investor investor;

    @BeforeEach
    void setUp() {
        // Real SmsOtpService over the mocked Supabase client, so the demo-stub
        // routing (TODO(MSG91)) is exercised rather than mocked away.
        service = new InvestorContactVerificationService(
                investorRepository, supabaseAuthClient, new SmsOtpService(supabaseAuthClient), auditService);
        investor = new Investor();
        ReflectionTestUtils.setField(investor, "id", investorId);
        investor.setDistributorId(distributorId);
        investor.setEmail("priya@example.com");
        investor.setMobileNumber("9876543210");
        lenient().when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        lenient().when(investorRepository.save(any(Investor.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(supabaseAuthClient.isEnabled()).thenReturn(false);
    }

    @Test
    void requestEmailOtpDelegatesToSupabaseAndAudits() {
        service.requestEmailOtp(investorId, distributorId);

        verify(supabaseAuthClient).sendEmailOtp("priya@example.com");
        verify(auditService).log(eq("INVESTOR"), eq(investorId), eq("EMAIL_OTP_SENT"), eq(distributorId), isNull());
    }

    @Test
    void verifyEmailOtpMarksEmailVerifiedViaOtp() {
        when(supabaseAuthClient.verifyEmailOtp("priya@example.com", "123456")).thenReturn(true);

        var status = service.verifyEmailOtp(investorId, distributorId, "123456");

        assertThat(investor.getEmailVerified()).isTrue();
        assertThat(investor.getEmailVerificationMethod()).isEqualTo(ContactVerificationMethod.OTP.name());
        assertThat(investor.getEmailVerifiedAt()).isNotNull();
        assertThat(investor.getEmailBelongsTo()).isEqualTo("self"); // defaulted on OTP verify
        assertThat(status.emailVerified()).isTrue();
        verify(investorRepository).save(investor);
        verify(auditService).log(eq("INVESTOR"), eq(investorId), eq("EMAIL_OTP_VERIFIED"), eq(distributorId), isNull());
    }

    @Test
    void verifyEmailOtpRejectsWrongCodeWithoutMarkingVerified() {
        when(supabaseAuthClient.verifyEmailOtp("priya@example.com", "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.verifyEmailOtp(investorId, distributorId, "000000"))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(investor.getEmailVerified()).isFalse();
        verify(investorRepository, never()).save(any());
        verify(auditService).log(eq("INVESTOR"), eq(investorId), eq("EMAIL_VERIFICATION_FAILED"), eq(distributorId), isNull());
    }

    @Test
    void verifyMobileOtpConvertsToE164AndMarksVerified() {
        when(supabaseAuthClient.isSmsEnabled()).thenReturn(true);
        when(supabaseAuthClient.verifySmsOtp("+919876543210", "654321")).thenReturn(true);

        service.verifyMobileOtp(investorId, distributorId, "654321");

        assertThat(investor.getMobileVerified()).isTrue();
        assertThat(investor.getMobileVerificationMethod()).isEqualTo(ContactVerificationMethod.OTP.name());
        verify(auditService).log(eq("INVESTOR"), eq(investorId), eq("MOBILE_OTP_VERIFIED"), eq(distributorId), isNull());
    }

    @Test
    void requestMobileOtpFallsBackToDemoStubWhenSmsDisabled() {
        when(supabaseAuthClient.isSmsEnabled()).thenReturn(false);

        service.requestMobileOtp(investorId, distributorId);

        // Simulated send (TODO(MSG91)): nothing reaches Supabase, request still succeeds.
        verify(supabaseAuthClient, never()).sendSmsOtp(anyString());
        verify(auditService).log(eq("INVESTOR"), eq(investorId), eq("MOBILE_OTP_SENT"), eq(distributorId), isNull());
    }

    @Test
    void verifyMobileOtpAcceptsDemoCodeWhenSmsDisabled() {
        when(supabaseAuthClient.isSmsEnabled()).thenReturn(false);

        service.verifyMobileOtp(investorId, distributorId, "000000");

        assertThat(investor.getMobileVerified()).isTrue();
        assertThat(investor.getMobileVerificationMethod()).isEqualTo(ContactVerificationMethod.OTP.name());
        verify(supabaseAuthClient, never()).verifySmsOtp(anyString(), anyString());
    }

    @Test
    void verifyMobileOtpRejectsWrongDemoCodeWhenSmsDisabled() {
        when(supabaseAuthClient.isSmsEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.verifyMobileOtp(investorId, distributorId, "111111"))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(investor.getMobileVerified()).isFalse();
    }

    @Test
    void declareContactSetsSelfDeclaredMethodAndBelongsTo() {
        var status = service.declareContact(investorId, distributorId, ContactChannel.EMAIL, "spouse");

        assertThat(investor.getEmailVerified()).isTrue();
        assertThat(investor.getEmailVerificationMethod()).isEqualTo(ContactVerificationMethod.SELF_DECLARED.name());
        assertThat(investor.getEmailBelongsTo()).isEqualTo("spouse");
        assertThat(status.emailBelongsTo()).isEqualTo("spouse");
        verify(supabaseAuthClient, never()).sendEmailOtp(anyString());
        verify(auditService).log(eq("INVESTOR"), eq(investorId), eq("EMAIL_SELF_DECLARED"), eq(distributorId), anyString());
    }

    @Test
    void verifyEmailOtpAsInvestorMarksEmailVerifiedWithoutDistributorOwnershipCheck() {
        UUID accountId = UUID.randomUUID();
        when(supabaseAuthClient.verifyEmailOtp("priya@example.com", "123456")).thenReturn(true);

        var status = service.verifyEmailOtpAsInvestor(investorId, accountId, "123456");

        assertThat(investor.getEmailVerified()).isTrue();
        assertThat(investor.getEmailVerificationMethod()).isEqualTo(ContactVerificationMethod.OTP.name());
        assertThat(status.emailVerified()).isTrue();
        verify(investorRepository).save(investor);
        verify(auditService).log(eq("INVESTOR"), eq(investorId), eq("EMAIL_OTP_VERIFIED"), eq(accountId), isNull());
    }

    @Test
    void getStatusAsInvestorReturnsCurrentState() {
        investor.setEmailVerified(Boolean.TRUE);
        investor.setEmailVerificationMethod(ContactVerificationMethod.OTP.name());

        var status = service.getStatusAsInvestor(investorId);

        assertThat(status.emailVerified()).isTrue();
        assertThat(status.emailVerificationMethod()).isEqualTo(ContactVerificationMethod.OTP.name());
        verify(supabaseAuthClient, never()).sendEmailOtp(anyString());
    }

    @Test
    void crossDistributorActorIsRejected() {
        UUID otherDistributor = UUID.randomUUID();

        assertThatThrownBy(() -> service.requestEmailOtp(investorId, otherDistributor))
                .isInstanceOf(AccessDeniedException.class);

        verify(supabaseAuthClient, never()).sendEmailOtp(anyString());
    }
}
