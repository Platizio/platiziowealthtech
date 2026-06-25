package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.InvestorLinkRequest;
import com.platizio.wealthtech.domain.InvestorLinkRequestStatus;
import com.platizio.wealthtech.domain.OnboardingSubmission;
import com.platizio.wealthtech.domain.OnboardingSubmissionStatus;
import com.platizio.wealthtech.dto.ApprovalRequest;
import com.platizio.wealthtech.dto.InvestorLinkReviewResponse;
import com.platizio.wealthtech.repository.DistributorRepository;
import com.platizio.wealthtech.repository.InvestorLinkRequestRepository;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.security.AuthenticatedInvestorPrincipal;
import com.platizio.wealthtech.service.InvestorAccountOwnershipGuard;
import com.platizio.wealthtech.service.InvestorAuthService;
import com.platizio.wealthtech.service.InvestorLinkRequestService;
import com.platizio.wealthtech.service.OnboardingSubmissionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Pure-Mockito controller tests for the investor email-approval link endpoints
 * (R3/R5/R7) on {@link InvestorLinkController}. Mocks the services/repos and asserts:
 * the review screen returns the distributor + frozen profile details for the owner;
 * a non-owner is rejected with 403; approve drives the service with the JWT account id
 * (never a body value) → INVESTOR_APPROVED; reject is ownership-checked at the service
 * layer; and a distributor principal cannot touch any of these investor endpoints.
 */
class InvestorLinkControllerTest {

    private final InvestorAuthService investorAuthService = mock(InvestorAuthService.class);
    private final InvestorLinkRequestService linkRequestService = mock(InvestorLinkRequestService.class);
    private final InvestorLinkRequestRepository linkRequestRepository = mock(InvestorLinkRequestRepository.class);
    private final OnboardingSubmissionService onboardingSubmissionService = mock(OnboardingSubmissionService.class);
    private final InvestorAccountOwnershipGuard ownershipGuard = mock(InvestorAccountOwnershipGuard.class);
    private final DistributorRepository distributorRepository = mock(DistributorRepository.class);
    private final com.platizio.wealthtech.service.InvestorService investorService =
            mock(com.platizio.wealthtech.service.InvestorService.class);
    private final com.platizio.wealthtech.service.ProfileChangeApprovalService profileChangeApprovalService =
            mock(com.platizio.wealthtech.service.ProfileChangeApprovalService.class);

    private final InvestorLinkController controller = new InvestorLinkController(
            investorAuthService,
            linkRequestService,
            linkRequestRepository,
            onboardingSubmissionService,
            ownershipGuard,
            distributorRepository,
            investorService,
            profileChangeApprovalService);

    private final UUID accountId = UUID.randomUUID();
    private final UUID investorId = UUID.randomUUID();
    private final UUID distributorId = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();
    private static final String PAN = "ABCDE1234F";
    private static final String TOKEN = "tok-abc";

    // ── review ───────────────────────────────────────────────────────────────

    @Test
    void reviewReturnsDistributorAndFrozenDetailsForTheOwner() {
        stubAccount();
        InvestorLinkRequest request = pendingRequest();
        when(linkRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(request));
        when(ownershipGuard.assertOwns(accountId, investorId)).thenReturn(account(PAN));
        when(distributorRepository.findById(distributorId)).thenReturn(Optional.of(distributor("Priya Advisor")));
        when(onboardingSubmissionService.findById(submissionId)).thenReturn(Optional.of(submission()));

        InvestorLinkReviewResponse response = controller.review(TOKEN, investorAuth());

        assertThat(response.distributorDisplayName()).isEqualTo("Priya Advisor");
        assertThat(response.profileDetailsJson()).isEqualTo("{\"firstName\":\"Asha\"}");
        assertThat(response.contentSha256()).isEqualTo("hash-1");
        assertThat(response.revisionNo()).isEqualTo(2);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.expiresAt()).isEqualTo(request.getExpiresAt());
    }

    @Test
    void reviewRejectsANonOwnerWith403() {
        stubAccount();
        InvestorLinkRequest request = pendingRequest();
        when(linkRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(request));
        // The guard denies an account that does not own this investor (IDOR guard).
        when(ownershipGuard.assertOwns(accountId, investorId))
                .thenThrow(new AccessDeniedException("You are not authorized to act on this investor."));

        assertThatThrownBy(() -> controller.review(TOKEN, investorAuth()))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(distributorRepository, onboardingSubmissionService);
    }

    @Test
    void reviewRejectsAPanMismatchWith403() {
        stubAccount();
        InvestorLinkRequest request = pendingRequest();
        when(linkRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(request));
        // Owns the investorId, but the account PAN differs from the request PAN.
        when(ownershipGuard.assertOwns(accountId, investorId)).thenReturn(account("ZZZZZ9999Z"));

        assertThatThrownBy(() -> controller.review(TOKEN, investorAuth()))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── approve ──────────────────────────────────────────────────────────────

    @Test
    void approveCallsServiceWithJwtAccountIdAndReturnsInvestorApproved() {
        stubAccount();
        InvestorLinkRequest approved = pendingRequest();
        approved.setStatus(InvestorLinkRequestStatus.APPROVED);
        when(linkRequestService.approveByToken(accountId, TOKEN)).thenReturn(approved);

        Map<String, Object> result = controller.approve(TOKEN, investorAuth());

        // The account id passed to the service is the JWT-resolved id, never a body value.
        verify(linkRequestService).approveByToken(accountId, TOKEN);
        assertThat(result.get("linkingStatus")).isEqualTo("INVESTOR_APPROVED");
        assertThat(result.get("status")).isEqualTo("APPROVED");
    }

    @Test
    void approvePropagatesTheServiceError() {
        stubAccount();
        when(linkRequestService.approveByToken(accountId, TOKEN))
                .thenThrow(new IllegalStateException("This approval link has expired."));

        assertThatThrownBy(() -> controller.approve(TOKEN, investorAuth()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── reject ───────────────────────────────────────────────────────────────

    @Test
    void rejectIsOwnershipCheckedAtTheServiceLayer() {
        stubAccount();

        Map<String, Object> result = controller.reject(TOKEN, investorAuth());

        // Delegates to the ownership-guarded reject overload with the JWT account id.
        verify(linkRequestService).reject(accountId, TOKEN);
        assertThat(result.get("linkingStatus")).isEqualTo("REJECTED");
    }

    @Test
    void rejectPropagatesTheServiceOwnershipError() {
        stubAccount();
        doThrowOnReject();

        assertThatThrownBy(() -> controller.reject(TOKEN, investorAuth()))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── approve-and-skip (R7e/R10) ─────────────────────────────────────────────

    @Test
    void approveAndSkipFlipsTheOwnedInvestorToSkipped() {
        stubAccount();
        InvestorLinkRequest request = pendingRequest();
        when(linkRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(request));
        when(ownershipGuard.assertOwns(accountId, investorId)).thenReturn(account(PAN));

        Map<String, Object> result = controller.approveAndSkip(TOKEN, investorAuth());

        // The investor id is taken from the OWNED link request, and the JWT account id is the actor.
        verify(investorService).approveAndSkipForm(investorId, accountId);
        assertThat(result.get("linkingStatus")).isEqualTo("INVESTOR_SKIPPED");
    }

    @Test
    void approveAndSkipRejectsANonOwnerWith403() {
        stubAccount();
        InvestorLinkRequest request = pendingRequest();
        when(linkRequestRepository.findByToken(TOKEN)).thenReturn(Optional.of(request));
        when(ownershipGuard.assertOwns(accountId, investorId))
                .thenThrow(new AccessDeniedException("You are not authorized to act on this investor."));

        assertThatThrownBy(() -> controller.approveAndSkip(TOKEN, investorAuth()))
                .isInstanceOf(AccessDeniedException.class);

        verify(investorService, never()).approveAndSkipForm(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    // ── profile-changes list (the Approvals page) ──────────────────────────────

    @Test
    void listProfileChangesReturnsOnlyTheCallersLiveChallenges() {
        stubAccount();
        // account.getInvestorId() == investorId, so the list is scoped to MY investor.
        com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge live =
                challenge(UUID.randomUUID(),
                        com.platizio.wealthtech.domain.ProfileChangeApprovalStatus.CHALLENGE_SENT);
        com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge terminal =
                challenge(UUID.randomUUID(),
                        com.platizio.wealthtech.domain.ProfileChangeApprovalStatus.CONSUMED);
        when(profileChangeApprovalService.listForInvestor(investorId))
                .thenReturn(List.of(live, terminal));

        var page = controller.listProfileChanges(investorAuth());

        // Scoped to the JWT investor, and terminal challenges are filtered out.
        verify(profileChangeApprovalService).listForInvestor(investorId);
        assertThat(page).hasSize(1);
        assertThat(page.get(0).challengeId()).isEqualTo(live.getId());
        assertThat(page.get(0).status()).isEqualTo("CHALLENGE_SENT");
    }

    // ── request-otp ────────────────────────────────────────────────────────────

    @Test
    void requestProfileChangeOtpAssertsOwnershipAndUsesNonDistributorResend() {
        stubAccount();
        UUID challengeId = UUID.randomUUID();
        com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge challenge =
                challenge(challengeId, com.platizio.wealthtech.domain.ProfileChangeApprovalStatus.PENDING);
        when(profileChangeApprovalService.getChallenge(challengeId)).thenReturn(Optional.of(challenge));
        when(ownershipGuard.assertOwns(accountId, investorId)).thenReturn(account(PAN));
        when(profileChangeApprovalService.requestApprovalOtp(challengeId, accountId, false))
                .thenReturn(new com.platizio.wealthtech.dto.OtpRequestResponse("sent", 300, 30, null));

        var response = controller.requestProfileChangeOtp(challengeId, investorAuth());

        verify(ownershipGuard).assertOwns(accountId, investorId);
        verify(profileChangeApprovalService).requestApprovalOtp(challengeId, accountId, false);
        assertThat(response.devCode()).isNull();
    }

    // ── approve (approveProfileChange THEN applyProfileChange with recomputed hash) ──

    @Test
    void approveProfileChangeApprovesThenAppliesWithRecomputedHash() {
        stubAccount();
        UUID challengeId = UUID.randomUUID();
        String pendingJson = "{\"firstName\":\"Asha\",\"city\":\"Pune\"}";
        com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge challenge =
                challenge(challengeId, com.platizio.wealthtech.domain.ProfileChangeApprovalStatus.CHALLENGE_SENT);
        challenge.setPendingProfileJson(pendingJson);
        when(profileChangeApprovalService.getChallenge(challengeId)).thenReturn(Optional.of(challenge));
        when(ownershipGuard.assertOwns(accountId, investorId)).thenReturn(account(PAN));

        Map<String, Object> result = controller.approveProfileChange(
                challengeId, new ApprovalRequest(true, "123456"), request(), investorAuth());

        // 1) approveProfileChange with the JWT account id + body consent/otp + captured ip/ua/session,
        // THEN 2) applyProfileChange with the hash recomputed from the EXACT frozen pending profile.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(investorService);
        inOrder.verify(investorService).approveProfileChange(
                eq(investorId), eq(challengeId), eq(accountId), eq("123456"), eq(true),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        inOrder.verify(investorService).applyProfileChange(
                investorId, com.platizio.wealthtech.service.ConsentRecordService.sha256(pendingJson));
        assertThat(result.get("linkingStatus")).isEqualTo("READY");
    }

    @Test
    void approveProfileChangeRejectsAChallengeOwnedByAnotherInvestorWith403() {
        stubAccount();
        UUID challengeId = UUID.randomUUID();
        com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge challenge =
                challenge(challengeId, com.platizio.wealthtech.domain.ProfileChangeApprovalStatus.CHALLENGE_SENT);
        when(profileChangeApprovalService.getChallenge(challengeId)).thenReturn(Optional.of(challenge));
        // The guard denies an account that does not own this investor (cross-investor IDOR).
        when(ownershipGuard.assertOwns(accountId, investorId))
                .thenThrow(new AccessDeniedException("You are not authorized to act on this investor."));

        assertThatThrownBy(() -> controller.approveProfileChange(
                challengeId, new ApprovalRequest(true, "123456"), request(), investorAuth()))
                .isInstanceOf(AccessDeniedException.class);

        verify(investorService, never()).approveProfileChange(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(investorService, never()).applyProfileChange(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // ── reject ───────────────────────────────────────────────────────────────

    @Test
    void rejectProfileChangeIsOwnershipCheckedAndSendsBackToSkipped() {
        stubAccount();
        UUID challengeId = UUID.randomUUID();
        com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge challenge =
                challenge(challengeId, com.platizio.wealthtech.domain.ProfileChangeApprovalStatus.CHALLENGE_SENT);
        when(profileChangeApprovalService.getChallenge(challengeId)).thenReturn(Optional.of(challenge));
        when(ownershipGuard.assertOwns(accountId, investorId)).thenReturn(account(PAN));

        Map<String, Object> result = controller.rejectProfileChange(
                challengeId, new com.platizio.wealthtech.dto.ProfileChangeRejectRequest("not me"),
                investorAuth());

        verify(ownershipGuard).assertOwns(accountId, investorId);
        verify(investorService).rejectProfileChange(investorId, challengeId, "not me", accountId);
        assertThat(result.get("linkingStatus")).isEqualTo("INVESTOR_SKIPPED");
    }

    // ── FR-2FA-007 parity: a distributor principal cannot use these endpoints ──

    @Test
    void distributorPrincipalIsRejectedFromApproveAndSkip() {
        assertThatThrownBy(() -> controller.approveAndSkip(TOKEN, distributorAuth()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(investorService);
    }

    @Test
    void distributorPrincipalIsRejectedFromProfileChanges() {
        assertThatThrownBy(() -> controller.listProfileChanges(distributorAuth()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(profileChangeApprovalService);
    }

    @Test
    void distributorPrincipalIsRejectedFromReview() {
        assertThatThrownBy(() -> controller.review(TOKEN, distributorAuth()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(linkRequestRepository, linkRequestService, ownershipGuard);
    }

    @Test
    void distributorPrincipalIsRejectedFromApprove() {
        assertThatThrownBy(() -> controller.approve(TOKEN, distributorAuth()))
                .isInstanceOf(AccessDeniedException.class);
        verify(linkRequestService, never()).approveByToken(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void distributorPrincipalIsRejectedFromReject() {
        assertThatThrownBy(() -> controller.reject(TOKEN, distributorAuth()))
                .isInstanceOf(AccessDeniedException.class);
        verify(linkRequestService, never()).reject(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubAccount() {
        lenient().when(investorAuthService.requireAccount(accountId)).thenReturn(account(PAN));
    }

    private void doThrowOnReject() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("You are not authorized to act on this investor."))
                .when(linkRequestService).reject(accountId, TOKEN);
    }

    private InvestorAccount account(String pan) {
        InvestorAccount acc = new InvestorAccount();
        setId(acc, accountId);
        acc.setFullName("Asha Rao");
        acc.setPan(pan);
        acc.setEmail("asha@example.com");
        acc.setMobileNumber("9999999999");
        acc.setInvestorId(investorId);
        return acc;
    }

    private InvestorLinkRequest pendingRequest() {
        InvestorLinkRequest request = new InvestorLinkRequest();
        request.setInvestorId(investorId);
        request.setPan(PAN);
        request.setPendingDistributorId(distributorId);
        request.setToken(TOKEN);
        request.setStatus(InvestorLinkRequestStatus.PENDING);
        request.setOnboardingSubmissionId(submissionId);
        request.setExpiresAt(OffsetDateTime.now().plusDays(5));
        return request;
    }

    private OnboardingSubmission submission() {
        OnboardingSubmission s = new OnboardingSubmission();
        setId(s, submissionId);
        s.setInvestorId(investorId);
        s.setRevisionNo(2);
        s.setStatus(OnboardingSubmissionStatus.DRAFT_AWAITING_INVESTOR);
        s.setPayloadJson("{\"firstName\":\"Asha\"}");
        s.setContentSha256("hash-1");
        s.setSubmittedBy(distributorId);
        s.setSubmittedAt(OffsetDateTime.now());
        return s;
    }

    private Distributor distributor(String name) {
        Distributor d = new Distributor();
        setId(d, distributorId);
        d.setFullName(name);
        return d;
    }

    private com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge challenge(
            UUID id, com.platizio.wealthtech.domain.ProfileChangeApprovalStatus status) {
        com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge challenge =
                new com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge();
        setId(challenge, id);
        challenge.setInvestorId(investorId);
        challenge.setStatus(status);
        challenge.setChannel("EMAIL");
        challenge.setMaskedDestination("a***@example.com");
        challenge.setPendingProfileJson("{\"firstName\":\"Asha\"}");
        challenge.setProfileChangeSha256("hash-x");
        challenge.setConsentTemplateVersion("v1.0");
        challenge.setConsentRenderedText("consent text");
        return challenge;
    }

    private HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        lenient().when(req.getHeader("User-Agent")).thenReturn("JUnit");
        lenient().when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(req.getRequestedSessionId()).thenReturn("sess-1");
        return req;
    }

    private Authentication investorAuth() {
        AuthenticatedInvestorPrincipal principal =
                new AuthenticatedInvestorPrincipal(accountId, "asha@example.com");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private Authentication distributorAuth() {
        AuthenticatedDistributorPrincipal principal = new AuthenticatedDistributorPrincipal(
                UUID.randomUUID(), "dist@example.com", "", DistributorRole.SUB_DISTRIBUTOR,
                List.of(new SimpleGrantedAuthority("ROLE_SUB_DISTRIBUTOR")));
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static void setId(Object entity, UUID id) {
        try {
            java.lang.reflect.Field field =
                    com.platizio.wealthtech.common.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
