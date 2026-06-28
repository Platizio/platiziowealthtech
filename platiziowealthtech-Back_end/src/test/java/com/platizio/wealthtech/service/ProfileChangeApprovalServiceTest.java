package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.ConsentRecord;
import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.OtpPurpose;
import com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge;
import com.platizio.wealthtech.domain.ProfileChangeApprovalStatus;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.repository.ProfileChangeApprovalChallengeRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
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
class ProfileChangeApprovalServiceTest {

    @Mock private ProfileChangeApprovalChallengeRepository challengeRepository;
    @Mock private ConsentRecordService consentRecordService;
    @Mock private OtpService otpService;
    @Mock private InvestorAccountOwnershipGuard ownershipGuard;
    @Mock private AuditService auditService;

    private ProfileChangeApprovalService service;

    private final UUID investorId = UUID.randomUUID();
    private final UUID investorAccountId = UUID.randomUUID();
    private final UUID distributorId = UUID.randomUUID();
    private final String email = "investor@example.com";
    private final String pendingProfileJson = "{\"pan\":\"ABCDE1234F\",\"name\":\"Test Investor\"}";

    @BeforeEach
    void setUp() {
        service = new ProfileChangeApprovalService(
                challengeRepository, consentRecordService, otpService, ownershipGuard, auditService);
        lenient().when(challengeRepository.save(any(ProfileChangeApprovalChallenge.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(ownershipGuard.assertOwns(investorAccountId, investorId))
                .thenReturn(account());
        lenient().when(otpService.requestOtp(anyString(), eq(OtpPurpose.PROFILE_APPROVAL), any()))
                .thenReturn(new OtpRequestResponse("sent", 300, 30, null));
        lenient().when(consentRecordService.record(
                anyString(), any(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(consentRecord());
    }

    private InvestorAccount account() {
        InvestorAccount a = new InvestorAccount();
        ReflectionTestUtils.setField(a, "id", investorAccountId);
        a.setEmail(email);
        a.setInvestorId(investorId);
        a.setFullName("Test Investor");
        return a;
    }

    private ConsentRecord consentRecord() {
        ConsentRecord c = new ConsentRecord();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        return c;
    }

    private ProfileChangeApprovalChallenge challenge(ProfileChangeApprovalStatus status) {
        ProfileChangeApprovalChallenge c = new ProfileChangeApprovalChallenge();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setInvestorId(investorId);
        c.setStatus(status);
        c.setPendingProfileJson(pendingProfileJson);
        c.setProfileChangeSha256(ConsentRecordService.sha256(pendingProfileJson));
        c.setConsentTemplateVersion("v1.0");
        c.setConsentRenderedText("consent");
        c.setChannel("EMAIL");
        c.setOtpPurpose(OtpPurpose.PROFILE_APPROVAL);
        c.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        c.setDeliveryAttempts(0);
        return c;
    }

    // ---- createChallenge ----------------------------------------------------

    @Test
    void createChallengeFreezesSnapshotAndHashAtPending() {
        when(challengeRepository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(eq(investorId), any()))
                .thenReturn(java.util.Optional.empty());

        ProfileChangeApprovalChallenge created = service.createChallenge(
                investorId, pendingProfileJson, distributorId);

        assertThat(created.getStatus()).isEqualTo(ProfileChangeApprovalStatus.PENDING);
        assertThat(created.getPendingProfileJson()).isEqualTo(pendingProfileJson);
        assertThat(created.getProfileChangeSha256())
                .isEqualTo(ConsentRecordService.sha256(pendingProfileJson));
        assertThat(created.getInvestorId()).isEqualTo(investorId);
        assertThat(created.getOtpPurpose()).isEqualTo(OtpPurpose.PROFILE_APPROVAL);
        assertThat(created.getConsentRenderedText()).isNotBlank();
    }

    @Test
    void createChallengeSupersedesAnExistingLiveChallenge() {
        ProfileChangeApprovalChallenge live = challenge(ProfileChangeApprovalStatus.CHALLENGE_SENT);
        when(challengeRepository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(eq(investorId), any()))
                .thenReturn(java.util.Optional.of(live));

        ProfileChangeApprovalChallenge created = service.createChallenge(
                investorId, pendingProfileJson, distributorId);

        assertThat(live.getStatus()).isEqualTo(ProfileChangeApprovalStatus.SUPERSEDED);
        assertThat(created.getStatus()).isEqualTo(ProfileChangeApprovalStatus.PENDING);
    }

    // ---- requestApprovalOtp -------------------------------------------------

    @Test
    void requestApprovalOtpFlipsToChallengeSentAndIncrementsAttempts() {
        ProfileChangeApprovalChallenge pending = challenge(ProfileChangeApprovalStatus.PENDING);
        UUID challengeId = pending.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(java.util.Optional.of(pending));

        OtpRequestResponse response = service.requestApprovalOtp(challengeId, investorAccountId, false);

        assertThat(pending.getStatus()).isEqualTo(ProfileChangeApprovalStatus.CHALLENGE_SENT);
        assertThat(pending.getDeliveryAttempts()).isEqualTo(1);
        assertThat(pending.getExpiresAt()).isNotNull();
        assertThat(pending.getMaskedDestination()).isNotNull();
        assertThat(response.devCode()).isNull();
        verify(ownershipGuard).assertOwns(investorAccountId, investorId);
        verify(otpService).requestOtp(eq(email), eq(OtpPurpose.PROFILE_APPROVAL), eq(challengeId));
    }

    @Test
    void requestApprovalOtpResendIncrementsAttemptsAgainAndNeverLeaksCode() {
        ProfileChangeApprovalChallenge sent = challenge(ProfileChangeApprovalStatus.CHALLENGE_SENT);
        sent.setDeliveryAttempts(1);
        UUID challengeId = sent.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(java.util.Optional.of(sent));

        OtpRequestResponse response = service.requestApprovalOtp(challengeId, investorAccountId, true);

        assertThat(sent.getDeliveryAttempts()).isEqualTo(2);
        assertThat(sent.getStatus()).isEqualTo(ProfileChangeApprovalStatus.CHALLENGE_SENT);
        assertThat(response.devCode()).isNull();
        verify(otpService).requestOtp(eq(email), eq(OtpPurpose.PROFILE_APPROVAL), eq(challengeId));
    }

    @Test
    void requestApprovalOtpRejectsTerminalChallenge() {
        ProfileChangeApprovalChallenge consumed = challenge(ProfileChangeApprovalStatus.CONSUMED);
        UUID challengeId = consumed.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(java.util.Optional.of(consumed));

        assertThatThrownBy(() -> service.requestApprovalOtp(challengeId, investorAccountId, false))
                .isInstanceOf(IllegalStateException.class);

        verify(otpService, never()).requestOtp(anyString(), any(), any());
    }

    @Test
    void requestApprovalOtpRejectsMissingChallenge() {
        UUID challengeId = UUID.randomUUID();
        when(challengeRepository.findById(challengeId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.requestApprovalOtp(challengeId, investorAccountId, false))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---- approve ------------------------------------------------------------

    @Test
    void approveHappyPathRecordsConsentAndMovesToApproved() {
        ProfileChangeApprovalChallenge sent = challenge(ProfileChangeApprovalStatus.CHALLENGE_SENT);
        UUID challengeId = sent.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(java.util.Optional.of(sent));

        ProfileChangeApprovalChallenge approved = service.approve(
                challengeId, investorAccountId, "123456", true,
                "203.0.113.7", "Mozilla/5.0", "sess-1");

        assertThat(approved.getStatus()).isEqualTo(ProfileChangeApprovalStatus.APPROVED);
        assertThat(approved.getApprovedAt()).isNotNull();
        assertThat(approved.getConsentRecordId()).isNotNull();
        assertThat(approved.getIpAddress()).isEqualTo("203.0.113.7");
        assertThat(approved.getSessionId()).isEqualTo("sess-1");
        verify(ownershipGuard).assertOwns(investorAccountId, investorId);
        verify(otpService).verify(eq(email), eq(OtpPurpose.PROFILE_APPROVAL), eq("123456"), eq(challengeId));
        verify(consentRecordService).record(
                eq(ConsentRecordService.SUBJECT_INVESTOR), eq(investorAccountId),
                eq("profile_change_approval"), anyString(), anyString(), any(), any());
    }

    @Test
    void approveRejectsBadOrExpiredOtp() {
        ProfileChangeApprovalChallenge sent = challenge(ProfileChangeApprovalStatus.CHALLENGE_SENT);
        UUID challengeId = sent.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(java.util.Optional.of(sent));
        org.mockito.Mockito.doThrow(new BadCredentialsException("bad"))
                .when(otpService).verify(eq(email), eq(OtpPurpose.PROFILE_APPROVAL), eq("000000"), eq(challengeId));

        assertThatThrownBy(() -> service.approve(
                challengeId, investorAccountId, "000000", true, null, null, null))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(sent.getStatus()).isEqualTo(ProfileChangeApprovalStatus.CHALLENGE_SENT);
    }

    @Test
    void approveRejectsWhenChallengeExpired() {
        ProfileChangeApprovalChallenge sent = challenge(ProfileChangeApprovalStatus.CHALLENGE_SENT);
        sent.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        UUID challengeId = sent.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(java.util.Optional.of(sent));

        assertThatThrownBy(() -> service.approve(
                challengeId, investorAccountId, "123456", true, null, null, null))
                .isInstanceOf(IllegalStateException.class);

        verify(otpService, never()).verify(anyString(), any(), anyString(), any());
    }

    @Test
    void approveRejectsConsentNotAcceptedWithoutCallingVerify() {
        ProfileChangeApprovalChallenge sent = challenge(ProfileChangeApprovalStatus.CHALLENGE_SENT);
        UUID challengeId = sent.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(java.util.Optional.of(sent));

        assertThatThrownBy(() -> service.approve(
                challengeId, investorAccountId, "123456", false, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(otpService, never()).verify(anyString(), any(), anyString(), any());
        assertThat(sent.getStatus()).isEqualTo(ProfileChangeApprovalStatus.CHALLENGE_SENT);
    }

    @Test
    void approveRejectsWhenAccountDoesNotOwnInvestor() {
        ProfileChangeApprovalChallenge sent = challenge(ProfileChangeApprovalStatus.CHALLENGE_SENT);
        UUID challengeId = sent.getId();
        UUID otherAccountId = UUID.randomUUID();
        when(challengeRepository.findById(challengeId)).thenReturn(java.util.Optional.of(sent));
        when(ownershipGuard.assertOwns(otherAccountId, investorId))
                .thenThrow(new AccessDeniedException("not yours"));

        assertThatThrownBy(() -> service.approve(
                challengeId, otherAccountId, "123456", true, null, null, null))
                .isInstanceOf(AccessDeniedException.class);

        verify(otpService, never()).verify(anyString(), any(), anyString(), any());
    }

    @Test
    void approveRejectsWhenStatusNotChallengeSent() {
        ProfileChangeApprovalChallenge pending = challenge(ProfileChangeApprovalStatus.PENDING);
        UUID challengeId = pending.getId();
        when(challengeRepository.findById(challengeId)).thenReturn(java.util.Optional.of(pending));

        assertThatThrownBy(() -> service.approve(
                challengeId, investorAccountId, "123456", true, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- assertApprovedAndConsume (atomic gate) -----------------------------

    @Test
    void assertApprovedAndConsumePassesAndFlipsApprovedToConsumed() {
        ProfileChangeApprovalChallenge approved = challenge(ProfileChangeApprovalStatus.APPROVED);
        String hash = approved.getProfileChangeSha256();
        when(challengeRepository.findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(
                investorId, ProfileChangeApprovalStatus.APPROVED))
                .thenReturn(java.util.Optional.of(approved));

        assertThatCode(() -> service.assertApprovedAndConsume(investorId, hash))
                .doesNotThrowAnyException();

        // Atomic: the same call that authorized the apply also burned the challenge.
        assertThat(approved.getStatus()).isEqualTo(ProfileChangeApprovalStatus.CONSUMED);
        assertThat(approved.getConsumedAt()).isNotNull();
        verify(challengeRepository).save(approved);
        verify(auditService).log(anyString(), eq(investorId), eq("PROFILE_APPROVAL_CONSUMED"), any(), any());
    }

    @Test
    void assertApprovedAndConsumeThrowsWhenNoApprovedChallenge() {
        // A SUPERSEDED/EXPIRED/already-CONSUMED challenge is not APPROVED → not found → blocked.
        when(challengeRepository.findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(
                investorId, ProfileChangeApprovalStatus.APPROVED))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.assertApprovedAndConsume(investorId, "anyhash"))
                .isInstanceOf(IllegalStateException.class);

        verify(challengeRepository, never()).save(any(ProfileChangeApprovalChallenge.class));
    }

    @Test
    void assertApprovedAndConsumeThrowsOnHashMismatchWithoutConsuming() {
        ProfileChangeApprovalChallenge approved = challenge(ProfileChangeApprovalStatus.APPROVED);
        when(challengeRepository.findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(
                investorId, ProfileChangeApprovalStatus.APPROVED))
                .thenReturn(java.util.Optional.of(approved));

        assertThatThrownBy(() -> service.assertApprovedAndConsume(investorId, "DIFFERENTHASH"))
                .isInstanceOf(IllegalStateException.class);

        // Hash drift must NOT burn the challenge (re-request, not silently consumed).
        assertThat(approved.getStatus()).isEqualTo(ProfileChangeApprovalStatus.APPROVED);
        verify(challengeRepository, never()).save(any(ProfileChangeApprovalChallenge.class));
    }

    // ---- supersedeOnEdit ----------------------------------------------------

    @Test
    void supersedeOnEditMovesLiveChallengeToSuperseded() {
        ProfileChangeApprovalChallenge live = challenge(ProfileChangeApprovalStatus.APPROVED);
        when(challengeRepository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(eq(investorId), any()))
                .thenReturn(java.util.Optional.of(live));

        service.supersedeOnEdit(investorId, distributorId);

        assertThat(live.getStatus()).isEqualTo(ProfileChangeApprovalStatus.SUPERSEDED);
        verify(auditService).log(anyString(), eq(investorId), anyString(), eq(distributorId), any());
    }

    @Test
    void supersedeOnEditIsNoOpWhenNoLiveChallenge() {
        when(challengeRepository.findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(eq(investorId), any()))
                .thenReturn(java.util.Optional.empty());

        assertThatCode(() -> service.supersedeOnEdit(investorId, distributorId))
                .doesNotThrowAnyException();
    }
}
