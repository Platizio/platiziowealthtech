package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.InvestorLinkingStatus;
import com.platizio.wealthtech.domain.ProfileChangeApprovalChallenge;
import com.platizio.wealthtech.dto.DistributorProfileSubmitRequest;
import com.platizio.wealthtech.dto.DistributorProfileSubmitResponse;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.service.InvestorService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Controller tests for the DISTRIBUTOR-facing half of the skip-form path (investor.md R10):
 * {@code POST /{investorId}/profile/submit} and {@code PUT /{investorId}/profile}. Both must
 * resolve the acting distributor from the JWT principal — never from any body field — and pass
 * that JWT-derived id straight through to {@link InvestorService}.
 */
@ExtendWith(MockitoExtension.class)
class InvestorControllerDistributorProfileTest {

    @Mock private InvestorService investorService;
    private InvestorController controller;

    private final UUID distributorId = UUID.randomUUID();
    private final UUID investorId = UUID.randomUUID();
    private final UUID challengeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new InvestorController(
                investorService, null, null, null, null, null, null, null);
    }

    private DistributorProfileSubmitRequest request() {
        return new DistributorProfileSubmitRequest("{\"profile\":\"filled-by-distributor\"}");
    }

    @Test
    void submitProfileReturnsDistributorFillingAndChallengeOn200() throws Exception {
        ProfileChangeApprovalChallenge challenge = challenge(challengeId);
        when(investorService.submitProfileForInvestorReview(
                eq(investorId), eq("{\"profile\":\"filled-by-distributor\"}"), eq(distributorId)))
                .thenReturn(challenge);

        DistributorProfileSubmitResponse response =
                controller.submitProfile(investorId, request(), auth(distributorId));

        assertThat(response.investorId()).isEqualTo(investorId);
        assertThat(response.linkingStatus()).isEqualTo(InvestorLinkingStatus.DISTRIBUTOR_FILLING);
        assertThat(response.challengeId()).isEqualTo(challengeId);
        verify(investorService)
                .submitProfileForInvestorReview(investorId, "{\"profile\":\"filled-by-distributor\"}", distributorId);
    }

    @Test
    void updateProfileReturnsDistributorFillingAndChallengeOn200() throws Exception {
        ProfileChangeApprovalChallenge challenge = challenge(challengeId);
        when(investorService.updateProfile(
                eq(investorId), eq("{\"profile\":\"filled-by-distributor\"}"), eq(distributorId)))
                .thenReturn(challenge);

        DistributorProfileSubmitResponse response =
                controller.updateProfile(investorId, request(), auth(distributorId));

        assertThat(response.investorId()).isEqualTo(investorId);
        assertThat(response.linkingStatus()).isEqualTo(InvestorLinkingStatus.DISTRIBUTOR_FILLING);
        assertThat(response.challengeId()).isEqualTo(challengeId);
        verify(investorService)
                .updateProfile(investorId, "{\"profile\":\"filled-by-distributor\"}", distributorId);
    }

    @Test
    void submitProfileUsesJwtDistributorIdNotAnyBodyValue() throws Exception {
        // The authenticated principal carries `distributorId`; a *different* id is what a
        // malicious body might try to smuggle. The DTO carries no distributor field, and the
        // controller must pass the JWT-derived id (distributorId) — never the attacker's.
        UUID attackerSuppliedDistributorId = UUID.randomUUID();
        assertThat(attackerSuppliedDistributorId).isNotEqualTo(distributorId);

        when(investorService.submitProfileForInvestorReview(any(), any(), any()))
                .thenReturn(challenge(challengeId));

        controller.submitProfile(investorId, request(), auth(distributorId));

        // Service is called with the principal's distributorId, never the attacker's value.
        verify(investorService).submitProfileForInvestorReview(
                investorId, "{\"profile\":\"filled-by-distributor\"}", distributorId);
        verify(investorService, never()).submitProfileForInvestorReview(
                any(), any(), eq(attackerSuppliedDistributorId));
    }

    @Test
    void submitProfilePropagatesCrossDistributorForbidden() {
        when(investorService.submitProfileForInvestorReview(any(), any(), eq(distributorId)))
                .thenThrow(new AccessDeniedException(
                        "Acting distributor is not linked to this investor"));

        // AccessDeniedException → 403 FORBIDDEN via GlobalExceptionHandler.
        assertThatThrownBy(() -> controller.submitProfile(investorId, request(), auth(distributorId)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updateProfilePropagatesWrongStateBadRequest() {
        when(investorService.updateProfile(any(), any(), eq(distributorId)))
                .thenThrow(new IllegalStateException(
                        "Cannot update the pending profile from linking_status=READY; expected DISTRIBUTOR_FILLING."));

        // IllegalStateException → 400 BAD_REQUEST via GlobalExceptionHandler.
        assertThatThrownBy(() -> controller.updateProfile(investorId, request(), auth(distributorId)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ProfileChangeApprovalChallenge challenge(UUID id) throws Exception {
        ProfileChangeApprovalChallenge challenge = new ProfileChangeApprovalChallenge();
        java.lang.reflect.Field idField =
                com.platizio.wealthtech.common.BaseEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(challenge, id);
        return challenge;
    }

    private Authentication auth(UUID distributorId) {
        AuthenticatedDistributorPrincipal principal = new AuthenticatedDistributorPrincipal(
                distributorId,
                "user@example.com",
                "",
                DistributorRole.SUB_DISTRIBUTOR,
                List.of(new SimpleGrantedAuthority("ROLE_SUB_DISTRIBUTOR"))
        );
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
