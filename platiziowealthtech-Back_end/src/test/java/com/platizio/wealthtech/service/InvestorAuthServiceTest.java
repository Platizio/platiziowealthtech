package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.InvestorAccountStatus;
import com.platizio.wealthtech.domain.OtpPurpose;
import com.platizio.wealthtech.dto.InvestorSignupRequest;
import com.platizio.wealthtech.repository.DistributorRepository;
import com.platizio.wealthtech.repository.InvestorAccountRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InvestorAuthServiceTest {

    @Mock private InvestorAccountRepository accountRepository;
    @Mock private InvestorRepository investorRepository;
    @Mock private OtpService otpService;
    @Mock private JwtService jwtService;
    @Mock private TermsAcceptanceService termsAcceptanceService;
    @Mock private ConsentRecordService consentRecordService;
    @Mock private DistributorRepository distributorRepository;

    private InvestorAuthService service;

    @BeforeEach
    void setUp() {
        service = new InvestorAuthService(accountRepository, investorRepository, otpService, jwtService,
                termsAcceptanceService, consentRecordService, distributorRepository);
        lenient().when(accountRepository.save(any(InvestorAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(jwtService.generateInvestorToken(any(), anyString())).thenReturn("investor.jwt.token");
    }

    private InvestorSignupRequest signupRequest() {
        return new InvestorSignupRequest(
                "Priya Sharma", "ABCDE1234F", "Priya@Example.com", "9876543210",
                "123456", "v1.0", true, true);
    }

    @Test
    void signupOtpEligibleOnlyWhenNoAccountExists() {
        when(accountRepository.findByEmailIgnoreCase("new@example.com")).thenReturn(Optional.empty());
        assertThat(service.isOtpEligible("new@example.com", OtpPurpose.INVESTOR_SIGNUP)).isTrue();

        InvestorAccount existing = new InvestorAccount();
        existing.setStatus(InvestorAccountStatus.ACTIVE);
        when(accountRepository.findByEmailIgnoreCase("taken@example.com")).thenReturn(Optional.of(existing));
        assertThat(service.isOtpEligible("taken@example.com", OtpPurpose.INVESTOR_SIGNUP)).isFalse();
    }

    @Test
    void loginOtpEligibleOnlyForActiveAccount() {
        InvestorAccount active = new InvestorAccount();
        active.setStatus(InvestorAccountStatus.ACTIVE);
        when(accountRepository.findByEmailIgnoreCase("a@example.com")).thenReturn(Optional.of(active));
        assertThat(service.isOtpEligible("a@example.com", OtpPurpose.INVESTOR_LOGIN)).isTrue();

        when(accountRepository.findByEmailIgnoreCase("none@example.com")).thenReturn(Optional.empty());
        assertThat(service.isOtpEligible("none@example.com", OtpPurpose.INVESTOR_LOGIN)).isFalse();
    }

    @Test
    void signupVerifiesOtpCreatesActiveAccountAndRecordsConsent() {
        when(accountRepository.existsByEmailIgnoreCase("priya@example.com")).thenReturn(false);
        when(accountRepository.existsByPan("ABCDE1234F")).thenReturn(false);
        // Invite-only signup: a distributor must have already onboarded this PAN.
        Investor invited = new Investor();
        ReflectionTestUtils.setField(invited, "id", UUID.randomUUID());
        when(investorRepository.findByPan("ABCDE1234F")).thenReturn(Optional.of(invited));

        InvestorAuthService.InvestorAuthResult result = service.signup(signupRequest(), "203.0.113.7", "UA");

        // OTP consumed against the normalized (lowercased) email
        verify(otpService).verify("priya@example.com", OtpPurpose.INVESTOR_SIGNUP, "123456");
        ArgumentCaptor<InvestorAccount> captor = ArgumentCaptor.forClass(InvestorAccount.class);
        verify(accountRepository).save(captor.capture());
        InvestorAccount saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("priya@example.com");
        assertThat(saved.getPan()).isEqualTo("ABCDE1234F");
        assertThat(saved.getMobileNumber()).isEqualTo("9876543210");
        assertThat(saved.getEmailVerified()).isTrue();
        assertThat(saved.getMobileVerified()).isTrue();   // dummy-verified at registration (no live SMS yet)
        assertThat(saved.getStatus()).isEqualTo(InvestorAccountStatus.ACTIVE);
        assertThat(result.token()).isEqualTo("investor.jwt.token");
        // T&C + email-ownership consent evidence persisted
        verify(termsAcceptanceService).record(eq("INVESTOR"), any(), eq("investor_tnc"), eq("v1.0"), any(), any(), any());
        verify(consentRecordService).record(eq("INVESTOR"), any(), eq("investor_tnc"), eq("v1.0"), anyString(), any(), any());
        verify(consentRecordService).record(eq("INVESTOR"), any(), eq("contact_ownership_email"), anyString(), anyString(), any(), any());
    }

    @Test
    void signupLinksDistributorDraftOnMatchingPan() {
        when(accountRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(accountRepository.existsByPan(anyString())).thenReturn(false);
        Investor draft = new Investor();
        UUID investorId = UUID.randomUUID();
        ReflectionTestUtils.setField(draft, "id", investorId);
        when(investorRepository.findByPan("ABCDE1234F")).thenReturn(Optional.of(draft));

        service.signup(signupRequest(), null, null);

        ArgumentCaptor<InvestorAccount> captor = ArgumentCaptor.forClass(InvestorAccount.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getInvestorId()).isEqualTo(investorId);
    }

    @Test
    void signupRejectsDuplicateWithoutVerifyingOrSaving() {
        when(accountRepository.existsByEmailIgnoreCase("priya@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.signup(signupRequest(), null, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(otpService, never()).verify(anyString(), any(), anyString());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void signupPropagatesBadOtp() {
        when(accountRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(accountRepository.existsByPan(anyString())).thenReturn(false);
        Investor invited = new Investor();
        ReflectionTestUtils.setField(invited, "id", UUID.randomUUID());
        when(investorRepository.findByPan(anyString())).thenReturn(Optional.of(invited));
        doThrow(new BadCredentialsException("bad code"))
                .when(otpService).verify("priya@example.com", OtpPurpose.INVESTOR_SIGNUP, "123456");

        assertThatThrownBy(() -> service.signup(signupRequest(), null, null))
                .isInstanceOf(BadCredentialsException.class);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void otpLoginVerifiesAndReturnsTokenForActiveAccount() {
        InvestorAccount active = new InvestorAccount();
        ReflectionTestUtils.setField(active, "id", UUID.randomUUID());
        active.setEmail("a@example.com");
        active.setStatus(InvestorAccountStatus.ACTIVE);
        active.setInvestorId(UUID.randomUUID()); // R6: login requires a linked distributor
        when(accountRepository.findByEmailIgnoreCase("a@example.com")).thenReturn(Optional.of(active));

        InvestorAuthService.InvestorAuthResult result = service.otpLogin("A@Example.com", "654321");

        verify(otpService).verify("a@example.com", OtpPurpose.INVESTOR_LOGIN, "654321");
        assertThat(result.token()).isEqualTo("investor.jwt.token");
    }

    @Test
    void otpLoginRejectsWhenNoActiveAccount() {
        when(accountRepository.findByEmailIgnoreCase("a@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.otpLogin("a@example.com", "654321"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void requireAccountRejectsBlockedAccountOnEverySessionUse() {
        UUID accountId = UUID.randomUUID();
        InvestorAccount blocked = new InvestorAccount();
        blocked.setStatus(InvestorAccountStatus.BLOCKED);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(blocked));

        assertThatThrownBy(() -> service.requireAccount(accountId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requireAccountReturnsActiveAccount() {
        UUID accountId = UUID.randomUUID();
        InvestorAccount active = new InvestorAccount();
        active.setStatus(InvestorAccountStatus.ACTIVE);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(active));

        assertThat(service.requireAccount(accountId)).isSameAs(active);
    }
}
