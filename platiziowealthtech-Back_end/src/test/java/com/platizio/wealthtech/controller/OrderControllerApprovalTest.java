package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.security.AuthenticatedInvestorPrincipal;
import com.platizio.wealthtech.security.JwtAuthPrincipal;
import com.platizio.wealthtech.service.OrderService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Pure-Mockito tests for the Phase-2 distributor approval endpoints on
 * {@link OrderController}: request-investor-approval (challenge summary, never an OTP),
 * resend-approval-link (response never carries the live code), and the FR-2FA-007
 * guarantee that an investor principal cannot drive the distributor endpoints.
 */
class OrderControllerApprovalTest {

    private final OrderService orderService = mock(OrderService.class);
    private final OrderController controller = new OrderController(orderService, null);

    @Test
    void requestInvestorApprovalReturnsChallengeSummaryNeverOtp() {
        UUID orderId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        OrderService.ApprovalRequestResult summary = new OrderService.ApprovalRequestResult(
                challengeId, orderId, "PURCHASE", "PENDING", "i***@example.com");
        when(orderService.requestInvestorApproval(eq(orderId), any(JwtAuthPrincipal.class)))
                .thenReturn(summary);

        OrderService.ApprovalRequestResult result =
                controller.requestInvestorApproval(orderId, distributorAuth());

        verify(orderService).requestInvestorApproval(eq(orderId), any(JwtAuthPrincipal.class));
        assertThat(result.challengeId()).isEqualTo(challengeId);
        assertThat(result.maskedDestination()).isEqualTo("i***@example.com");
        // The summary record structurally has no OTP/code field.
        assertThat(OrderService.ApprovalRequestResult.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("code", "otp", "devCode");
    }

    @Test
    void resendApprovalLinkResponseNeverContainsTheOtpCode() {
        UUID orderId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        // The OtpRequestResponse from the service hides the code (devCode null in prod/demo).
        when(orderService.resendApprovalLink(eq(orderId), eq(challengeId), any(JwtAuthPrincipal.class)))
                .thenReturn(new OtpRequestResponse("A passcode was sent.", 300, 30, null));

        OtpRequestResponse response = controller.resendApprovalLink(orderId, challengeId, distributorAuth());

        verify(orderService).resendApprovalLink(eq(orderId), eq(challengeId), any(JwtAuthPrincipal.class));
        assertThat(response.devCode()).isNull();
        assertThat(response.message()).doesNotContainPattern("\\d{4,}");
    }

    @Test
    void investorPrincipalCannotRequestInvestorApproval() {
        assertThatThrownBy(() -> controller.requestInvestorApproval(UUID.randomUUID(), investorAuth()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(orderService);
    }

    @Test
    void investorPrincipalCannotResendApprovalLink() {
        assertThatThrownBy(() ->
                controller.resendApprovalLink(UUID.randomUUID(), UUID.randomUUID(), investorAuth()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(orderService);
    }

    private Authentication distributorAuth() {
        AuthenticatedDistributorPrincipal principal = new AuthenticatedDistributorPrincipal(
                UUID.randomUUID(), "dist@example.com", "", DistributorRole.SUB_DISTRIBUTOR,
                List.of(new SimpleGrantedAuthority("ROLE_SUB_DISTRIBUTOR")));
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private Authentication investorAuth() {
        AuthenticatedInvestorPrincipal principal =
                new AuthenticatedInvestorPrincipal(UUID.randomUUID(), "investor@example.com");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
