package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.InvestorLinkRequest;
import com.platizio.wealthtech.domain.InvestorLinkRequestStatus;
import com.platizio.wealthtech.domain.InvestorLinkingStatus;
import com.platizio.wealthtech.domain.NotificationType;
import com.platizio.wealthtech.domain.OnboardingSubmission;
import com.platizio.wealthtech.repository.DistributorRepository;
import com.platizio.wealthtech.repository.InvestorLinkRequestRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class InvestorLinkRequestServiceTest {

    @Mock private InvestorLinkRequestRepository linkRequestRepository;
    @Mock private InvestorRepository investorRepository;
    @Mock private OnboardingSubmissionService onboardingSubmissionService;
    @Mock private InvestorAccountOwnershipGuard ownershipGuard;
    @Mock private NotificationService notificationService;
    @Mock private EmailService emailService;
    @Mock private DistributorRepository distributorRepository;
    private InvestorLinkRequestService service;

    private final UUID investorId = UUID.randomUUID();
    private final UUID distributorId = UUID.randomUUID();
    private final UUID investorAccountId = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new InvestorLinkRequestService(
                linkRequestRepository, investorRepository, onboardingSubmissionService, ownershipGuard,
                notificationService, emailService, distributorRepository, "http://localhost:3000");
        lenient().when(linkRequestRepository.save(any(InvestorLinkRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(investorRepository.save(any(Investor.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** A self-service investor account confirmed-linked to {@code investorId} with the given PAN. */
    private InvestorAccount account(String pan) {
        InvestorAccount acc = new InvestorAccount();
        acc.setFullName("Asha Rao");
        acc.setPan(pan);
        acc.setEmail("asha@example.com");
        acc.setMobileNumber("9999999999");
        acc.setInvestorId(investorId);
        return acc;
    }

    private Investor investor() {
        Investor inv = new Investor();
        inv.setFullName("Asha Rao");
        inv.setPan("ABCDE1234F");
        inv.setEmail("asha@example.com");
        inv.setMobileNumber("9999999999");
        inv.setLinkingStatus(InvestorLinkingStatus.READY);
        return inv;
    }

    private OnboardingSubmission submission() {
        OnboardingSubmission s = new OnboardingSubmission();
        s.setInvestorId(investorId);
        // id is generated; emulate persistence by reflecting nothing — use a fresh stub.
        return s;
    }

    @Test
    void sendToInvestorCreatesPendingRequestAndParksInvestorWithoutLinkingDistributor() {
        Investor inv = investor();
        when(investorRepository.findForUpdateById(investorId)).thenReturn(Optional.of(inv));
        when(linkRequestRepository.findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(
                eq(investorId), eq(InvestorLinkRequestStatus.PENDING))).thenReturn(Optional.empty());
        OnboardingSubmission sub = submission();
        when(onboardingSubmissionService.submitForInvestorReview(eq(investorId), eq("{\"a\":1}"), eq(distributorId)))
                .thenReturn(sub);

        InvestorLinkRequest result = service.sendToInvestor(investorId, distributorId, "{\"a\":1}");

        // request fields
        assertThat(result.getStatus()).isEqualTo(InvestorLinkRequestStatus.PENDING);
        assertThat(result.getInvestorId()).isEqualTo(investorId);
        assertThat(result.getPendingDistributorId()).isEqualTo(distributorId);
        assertThat(result.getPan()).isEqualTo("ABCDE1234F");
        assertThat(result.getToken()).isNotBlank();
        assertThat(result.getExpiresAt()).isAfter(OffsetDateTime.now().plusDays(6));
        // investor parked but NOT linked
        assertThat(inv.getLinkingStatus()).isEqualTo(InvestorLinkingStatus.PENDING_INVESTOR_APPROVAL);
        assertThat(inv.getPendingDistributorId()).isEqualTo(distributorId);
        assertThat(inv.getDistributorId()).isNull();
        // a submission was frozen for investor review
        verify(onboardingSubmissionService).submitForInvestorReview(investorId, "{\"a\":1}", distributorId);
    }

    @Test
    void sendToInvestorEmailsTheInvestorTheApprovalLink() {
        Investor inv = investor();
        when(investorRepository.findForUpdateById(investorId)).thenReturn(Optional.of(inv));
        when(linkRequestRepository.findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(
                eq(investorId), eq(InvestorLinkRequestStatus.PENDING))).thenReturn(Optional.empty());
        when(onboardingSubmissionService.submitForInvestorReview(any(), any(), any())).thenReturn(submission());

        InvestorLinkRequest result = service.sendToInvestor(investorId, distributorId, "{\"a\":1}");

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(to.capture(), any(), body.capture());
        assertThat(to.getValue()).isEqualTo("asha@example.com");
        assertThat(body.getValue()).contains("/investor/approve?token=" + result.getToken());
    }

    @Test
    void sendToInvestorStillCreatesTheLinkWhenEmailThrows() {
        Investor inv = investor();
        when(investorRepository.findForUpdateById(investorId)).thenReturn(Optional.of(inv));
        when(linkRequestRepository.findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(
                eq(investorId), eq(InvestorLinkRequestStatus.PENDING))).thenReturn(Optional.empty());
        when(onboardingSubmissionService.submitForInvestorReview(any(), any(), any())).thenReturn(submission());
        when(emailService.sendHtml(any(), any(), any())).thenThrow(new IllegalStateException("smtp down"));

        // best-effort: a mail failure must NOT propagate or roll back the link request
        InvestorLinkRequest result = service.sendToInvestor(investorId, distributorId, "{\"a\":1}");

        assertThat(result.getStatus()).isEqualTo(InvestorLinkRequestStatus.PENDING);
        assertThat(result.getToken()).isNotBlank();
    }

    @Test
    void sendToInvestorStillCreatesTheLinkWhenEmailDisabled() {
        Investor inv = investor();
        when(investorRepository.findForUpdateById(investorId)).thenReturn(Optional.of(inv));
        when(linkRequestRepository.findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(
                eq(investorId), eq(InvestorLinkRequestStatus.PENDING))).thenReturn(Optional.empty());
        when(onboardingSubmissionService.submitForInvestorReview(any(), any(), any())).thenReturn(submission());
        when(emailService.sendHtml(any(), any(), any())).thenReturn(false); // SMTP disabled → no-op

        InvestorLinkRequest result = service.sendToInvestor(investorId, distributorId, "{\"a\":1}");

        assertThat(result.getStatus()).isEqualTo(InvestorLinkRequestStatus.PENDING);
        assertThat(result.getToken()).isNotBlank();
    }

    @Test
    void sendToInvestorSupersedesExistingLiveRequest() {
        Investor inv = investor();
        when(investorRepository.findForUpdateById(investorId)).thenReturn(Optional.of(inv));
        InvestorLinkRequest prior = new InvestorLinkRequest();
        prior.setInvestorId(investorId);
        prior.setStatus(InvestorLinkRequestStatus.PENDING);
        when(linkRequestRepository.findFirstByInvestorIdAndStatusOrderByCreatedAtDesc(
                eq(investorId), eq(InvestorLinkRequestStatus.PENDING))).thenReturn(Optional.of(prior));
        when(onboardingSubmissionService.submitForInvestorReview(any(), any(), any()))
                .thenReturn(submission());

        service.sendToInvestor(investorId, distributorId, "{\"a\":1}");

        assertThat(prior.getStatus()).isEqualTo(InvestorLinkRequestStatus.SUPERSEDED);
    }

    @Test
    void approveByTokenLinksDistributorAndMarksApproved() {
        Investor inv = investor();
        InvestorLinkRequest request = pendingRequest(OffsetDateTime.now().plusDays(3));
        when(linkRequestRepository.findByToken("tok")).thenReturn(Optional.of(request));
        // The approving account owns this investor and its PAN matches the request PAN.
        when(ownershipGuard.assertOwns(investorAccountId, investorId)).thenReturn(account("ABCDE1234F"));
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(inv));

        InvestorLinkRequest result = service.approveByToken(investorAccountId, "tok");

        assertThat(inv.getDistributorId()).isEqualTo(distributorId);
        assertThat(inv.getLinkingStatus()).isEqualTo(InvestorLinkingStatus.INVESTOR_APPROVED);
        assertThat(result.getStatus()).isEqualTo(InvestorLinkRequestStatus.APPROVED);
        assertThat(result.getApprovedAt()).isNotNull();
        // PA6/R8: the now-linked distributor is notified in-app, scoped to the right
        // distributor + investor and typed INVESTOR_LINK_APPROVED.
        verify(notificationService).createForDistributor(
                eq(distributorId), eq(investorId), eq(NotificationType.INVESTOR_LINK_APPROVED), any(), any());
    }

    @Test
    void approveByTokenSecondTimeIsRejectedBeforeEmittingDuplicateNotification() {
        // Idempotency: a second approveByToken on an already-APPROVED request throws BEFORE any
        // linking/emit, so no duplicate notification is created.
        InvestorLinkRequest request = pendingRequest(OffsetDateTime.now().plusDays(3));
        request.setStatus(InvestorLinkRequestStatus.APPROVED);
        when(linkRequestRepository.findByToken("tok")).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.approveByToken(investorAccountId, "tok"))
                .isInstanceOf(IllegalStateException.class);

        verify(notificationService, never())
                .createForDistributor(any(), any(), any(), any(), any());
    }

    @Test
    void approveByTokenIsCaseInsensitiveOnPanAndStillLinks() {
        Investor inv = investor();
        InvestorLinkRequest request = pendingRequest(OffsetDateTime.now().plusDays(3));
        when(linkRequestRepository.findByToken("tok")).thenReturn(Optional.of(request));
        // Same PAN but differing case / whitespace — must normalize to a match.
        when(ownershipGuard.assertOwns(investorAccountId, investorId)).thenReturn(account(" abcde1234f "));
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(inv));

        InvestorLinkRequest result = service.approveByToken(investorAccountId, "tok");

        assertThat(result.getStatus()).isEqualTo(InvestorLinkRequestStatus.APPROVED);
        assertThat(inv.getDistributorId()).isEqualTo(distributorId);
    }

    @Test
    void approveByTokenThrowsForbiddenWhenAccountDoesNotOwnInvestor() {
        InvestorLinkRequest request = pendingRequest(OffsetDateTime.now().plusDays(3));
        when(linkRequestRepository.findByToken("tok")).thenReturn(Optional.of(request));
        // The ownership guard rejects an account that is not linked to this investor.
        when(ownershipGuard.assertOwns(investorAccountId, investorId))
                .thenThrow(new AccessDeniedException("You are not authorized to act on this investor."));

        assertThatThrownBy(() -> service.approveByToken(investorAccountId, "tok"))
                .isInstanceOf(AccessDeniedException.class);
        // No linking happens when ownership fails.
        verify(investorRepository, never()).save(any());
    }

    @Test
    void approveByTokenThrowsForbiddenWhenPanMismatch() {
        InvestorLinkRequest request = pendingRequest(OffsetDateTime.now().plusDays(3));
        when(linkRequestRepository.findByToken("tok")).thenReturn(Optional.of(request));
        // Account owns the investor (guard passes) but its PAN differs from the request PAN.
        when(ownershipGuard.assertOwns(investorAccountId, investorId)).thenReturn(account("ZZZZZ9999Z"));

        assertThatThrownBy(() -> service.approveByToken(investorAccountId, "tok"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Approval does not match your account.");
        verify(investorRepository, never()).save(any());
    }

    @Test
    void approveByTokenThrowsWhenStatusNotPending() {
        InvestorLinkRequest request = pendingRequest(OffsetDateTime.now().plusDays(3));
        request.setStatus(InvestorLinkRequestStatus.APPROVED);
        when(linkRequestRepository.findByToken("tok")).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.approveByToken(investorAccountId, "tok"))
                .isInstanceOf(IllegalStateException.class);
        verify(investorRepository, never()).save(any());
    }

    @Test
    void approveByTokenThrowsWhenExpired() {
        InvestorLinkRequest request = pendingRequest(OffsetDateTime.now().minusMinutes(1));
        when(linkRequestRepository.findByToken("tok")).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.approveByToken(investorAccountId, "tok"))
                .isInstanceOf(IllegalStateException.class);
        verify(investorRepository, never()).save(any());
        // The expired PENDING link is retired to EXPIRED, not left lingering as PENDING.
        assertThat(request.getStatus()).isEqualTo(InvestorLinkRequestStatus.EXPIRED);
        verify(linkRequestRepository).save(request);
        // PA6/R8: an expired link does NOT notify the distributor.
        verify(notificationService, never())
                .createForDistributor(any(), any(), any(), any(), any());
    }

    @Test
    void approveByTokenThrowsNotFoundWhenTokenMissing() {
        when(linkRequestRepository.findByToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveByToken(investorAccountId, "nope"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void rejectMarksRequestAndInvestorRejected() {
        Investor inv = investor();
        InvestorLinkRequest request = pendingRequest(OffsetDateTime.now().plusDays(3));
        when(linkRequestRepository.findByToken("tok")).thenReturn(Optional.of(request));
        when(ownershipGuard.assertOwns(investorAccountId, investorId)).thenReturn(account("ABCDE1234F"));
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(inv));

        service.reject(investorAccountId, "tok");

        assertThat(request.getStatus()).isEqualTo(InvestorLinkRequestStatus.REJECTED);
        assertThat(inv.getLinkingStatus()).isEqualTo(InvestorLinkingStatus.REJECTED);
        assertThat(inv.getDistributorId()).isNull();
        // PA6/R8: a rejection does NOT notify the distributor.
        verify(notificationService, never())
                .createForDistributor(any(), any(), any(), any(), any());
    }

    @Test
    void rejectIsBlockedForANonOwningAccount() {
        InvestorLinkRequest request = pendingRequest(OffsetDateTime.now().plusDays(3));
        when(linkRequestRepository.findByToken("tok")).thenReturn(Optional.of(request));
        // The guard denies a caller that does not own this investor (IDOR guard).
        when(ownershipGuard.assertOwns(investorAccountId, investorId))
                .thenThrow(new AccessDeniedException("You are not authorized to act on this investor."));

        assertThatThrownBy(() -> service.reject(investorAccountId, "tok"))
                .isInstanceOf(AccessDeniedException.class);

        // No state is flipped and the distributor is never notified.
        assertThat(request.getStatus()).isEqualTo(InvestorLinkRequestStatus.PENDING);
        verify(investorRepository, never()).save(any());
        verify(notificationService, never())
                .createForDistributor(any(), any(), any(), any(), any());
    }

    @Test
    void rejectIsBlockedWhenPanDoesNotMatchTheOwningAccount() {
        InvestorLinkRequest request = pendingRequest(OffsetDateTime.now().plusDays(3));
        when(linkRequestRepository.findByToken("tok")).thenReturn(Optional.of(request));
        // The account owns the investorId but its PAN differs from the request PAN.
        when(ownershipGuard.assertOwns(investorAccountId, investorId)).thenReturn(account("ZZZZZ9999Z"));

        assertThatThrownBy(() -> service.reject(investorAccountId, "tok"))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(request.getStatus()).isEqualTo(InvestorLinkRequestStatus.PENDING);
        verify(investorRepository, never()).save(any());
    }

    private InvestorLinkRequest pendingRequest(OffsetDateTime expiresAt) {
        InvestorLinkRequest request = new InvestorLinkRequest();
        request.setInvestorId(investorId);
        request.setPan("ABCDE1234F");
        request.setPendingDistributorId(distributorId);
        request.setToken("tok");
        request.setStatus(InvestorLinkRequestStatus.PENDING);
        request.setOnboardingSubmissionId(submissionId);
        request.setExpiresAt(expiresAt);
        return request;
    }
}
