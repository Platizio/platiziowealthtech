package com.platizio.wealthtech.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.RedemptionRecord;
import com.platizio.wealthtech.domain.RedemptionStatus;
import com.platizio.wealthtech.domain.TransactionApprovalChallenge;
import com.platizio.wealthtech.domain.TransactionApprovalStatus;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.ApprovalRequest;
import com.platizio.wealthtech.dto.WithdrawalRequest;
import com.platizio.wealthtech.security.AuthenticatedDistributorPrincipal;
import com.platizio.wealthtech.security.AuthenticatedInvestorPrincipal;
import com.platizio.wealthtech.service.ConsentRecordService;
import com.platizio.wealthtech.service.HoldingsService;
import com.platizio.wealthtech.service.InvestorService;
import com.platizio.wealthtech.service.InvestorDocumentService;
import com.platizio.wealthtech.service.NomineeService;
import com.platizio.wealthtech.service.ProductService;
import com.platizio.wealthtech.service.InvestorActionService;
import com.platizio.wealthtech.service.InvestorActionService.InvestorActionPage;
import com.platizio.wealthtech.service.InvestorAuthService;
import com.platizio.wealthtech.service.InvestorContactVerificationService;
import com.platizio.wealthtech.service.InvestorKycService;
import com.platizio.wealthtech.service.OnboardingSubmissionService;
import com.platizio.wealthtech.service.OrderService;
import com.platizio.wealthtech.service.PortfolioService;
import com.platizio.wealthtech.service.TransactionApprovalService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
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
 * Pure-Mockito controller tests for the Phase-2 investor Approval Center, holdings
 * and withdrawal endpoints on {@link InvestorPortalController}. Mocks the services
 * and asserts the approve → provider-submit wiring per challenge type, the withdrawal
 * draft/challenge wiring, and the FR-2FA-007 distributor-403 + no-OTP guarantees.
 */
class InvestorPortalApprovalControllerTest {

    private final TransactionApprovalService approvalService = mock(TransactionApprovalService.class);
    private final InvestorActionService investorActionService = mock(InvestorActionService.class);
    private final OrderService orderService = mock(OrderService.class);
    private final PortfolioService portfolioService = mock(PortfolioService.class);
    private final InvestorAuthService investorAuthService = mock(InvestorAuthService.class);

    private final InvestorPortalController controller = new InvestorPortalController(
            investorAuthService,
            mock(OnboardingSubmissionService.class),
            mock(InvestorContactVerificationService.class),
            mock(ConsentRecordService.class),
            approvalService,
            investorActionService,
            orderService,
            portfolioService,
            mock(HoldingsService.class),
            mock(InvestorKycService.class),
            mock(ProductService.class),
            mock(InvestorService.class),
            mock(NomineeService.class),
            mock(InvestorDocumentService.class));

    private final UUID accountId = UUID.randomUUID();
    private final UUID investorId = UUID.randomUUID();

    // ── approve → provider submit wiring per type ────────────────────────────

    @Test
    void approvePurchaseDrivesConfirmPurchaseForOrder() {
        UUID orderId = UUID.randomUUID();
        TransactionApprovalChallenge challenge = challenge(orderId, TransactionType.PURCHASE);
        stubAccount();
        when(approvalService.getChallenge(challenge.getId())).thenReturn(Optional.of(challenge));
        when(investorActionService.confirmPurchaseForOrder(orderId))
                .thenReturn(page(orderId, "https://pay.example/redirect", "Continue to payment"));

        Map<String, Object> result = controller.approve(
                challenge.getId(), new ApprovalRequest(true, "123456"), request(), investorAuth());

        verify(approvalService).approve(eq(challenge.getId()), eq(accountId), eq("123456"), eq(true),
                any(), any(), any());
        verify(investorActionService).confirmPurchaseForOrder(orderId);
        verify(orderService, never()).submitRedemptionToProvider(any());
        assertThat(result.get("nextAction")).isEqualTo("PAYMENT_REDIRECT");
        assertThat(result.get("redirectUrl")).isEqualTo("https://pay.example/redirect");
    }

    @Test
    void approveSipDrivesStartSipMandateForOrder() {
        UUID orderId = UUID.randomUUID();
        TransactionApprovalChallenge challenge = challenge(orderId, TransactionType.SIP);
        stubAccount();
        when(approvalService.getChallenge(challenge.getId())).thenReturn(Optional.of(challenge));
        when(investorActionService.startSipMandateForOrder(orderId))
                .thenReturn(page(orderId, "https://mandate.example/url", "Authorize your mandate"));

        Map<String, Object> result = controller.approve(
                challenge.getId(), new ApprovalRequest(true, "654321"), request(), investorAuth());

        verify(investorActionService).startSipMandateForOrder(orderId);
        verify(investorActionService, never()).confirmPurchaseForOrder(any());
        assertThat(result.get("nextAction")).isEqualTo("MANDATE_URL");
        assertThat(result.get("redirectUrl")).isEqualTo("https://mandate.example/url");
    }

    @Test
    void approveRedemptionDrivesSubmitRedemptionToProvider() {
        UUID redemptionId = UUID.randomUUID();
        TransactionApprovalChallenge challenge = challenge(redemptionId, TransactionType.REDEMPTION);
        stubAccount();
        when(approvalService.getChallenge(challenge.getId())).thenReturn(Optional.of(challenge));
        RedemptionRecord submitted = new RedemptionRecord();
        submitted.setRedemptionStatus(RedemptionStatus.SUBMITTED);
        when(orderService.submitRedemptionToProvider(redemptionId)).thenReturn(submitted);

        Map<String, Object> result = controller.approve(
                challenge.getId(), new ApprovalRequest(true, "000111"), request(), investorAuth());

        verify(orderService).submitRedemptionToProvider(redemptionId);
        verify(investorActionService, never()).confirmPurchaseForOrder(any());
        assertThat(result.get("nextAction")).isEqualTo("SUBMITTED");
        assertThat(result.get("status")).isEqualTo("SUBMITTED");
    }

    @Test
    void approveRejectsChallengeOwnedByAnotherInvestor() {
        TransactionApprovalChallenge challenge = challenge(UUID.randomUUID(), TransactionType.PURCHASE);
        challenge.setInvestorAccountId(UUID.randomUUID()); // a different account
        stubAccount();
        when(approvalService.getChallenge(challenge.getId())).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> controller.approve(
                challenge.getId(), new ApprovalRequest(true, "123456"), request(), investorAuth()))
                .isInstanceOf(AccessDeniedException.class);

        verify(approvalService, never()).approve(any(), any(), any(), anyBoolean(), any(), any(), any());
        verify(investorActionService, never()).confirmPurchaseForOrder(any());
    }

    // ── otp/request never returns the code ───────────────────────────────────

    @Test
    void investorOtpRequestUsesNonDistributorResendFlag() {
        TransactionApprovalChallenge challenge = challenge(UUID.randomUUID(), TransactionType.PURCHASE);
        stubAccount();
        when(approvalService.getChallenge(challenge.getId())).thenReturn(Optional.of(challenge));
        when(approvalService.requestApprovalOtp(challenge.getId(), accountId, false))
                .thenReturn(new com.platizio.wealthtech.dto.OtpRequestResponse("sent", 300, 30, null));

        var response = controller.requestApprovalOtp(challenge.getId(), investorAuth());

        verify(approvalService).requestApprovalOtp(challenge.getId(), accountId, false);
        assertThat(response.devCode()).isNull();
    }

    // ── holdings ─────────────────────────────────────────────────────────────

    @Test
    void holdingsAreInvestorScoped() {
        stubAccount();
        when(portfolioService.getInvestorHoldings(investorId)).thenReturn(List.of());

        controller.holdings(investorAuth());

        verify(portfolioService).getInvestorHoldings(investorId);
    }

    // ── withdrawals ──────────────────────────────────────────────────────────

    @Test
    void requestToDistributorCreatesDraftOnlyNoChallengeNoProvider() {
        UUID orderId = UUID.randomUUID();
        stubAccount();
        RedemptionRecord draft = new RedemptionRecord();
        draft.setRedemptionStatus(RedemptionStatus.PENDING_INVESTOR_ACTION);
        when(orderService.createRedemptionDraft(
                orderId, accountId, WithdrawalRequest.WithdrawalMode.AMOUNT, new BigDecimal("1000"), false))
                .thenReturn(draft);

        Map<String, Object> result = controller.requestWithdrawalToDistributor(
                new WithdrawalRequest(orderId, null, WithdrawalRequest.WithdrawalMode.AMOUNT,
                        new BigDecimal("1000"), false),
                investorAuth());

        verify(orderService).createRedemptionDraft(
                orderId, accountId, WithdrawalRequest.WithdrawalMode.AMOUNT, new BigDecimal("1000"), false);
        verify(approvalService, never()).createChallenge(any(), any(), any());
        verify(orderService, never()).submitRedemptionToProvider(any());
        assertThat(result.get("mode")).isEqualTo("REQUEST_TO_DISTRIBUTOR");
    }

    @Test
    void selfWithdrawalCreatesDraftThenRedemptionChallenge() {
        UUID orderId = UUID.randomUUID();
        UUID redemptionId = UUID.randomUUID();
        stubAccount();
        RedemptionRecord draft = new RedemptionRecord();
        draft.setRedemptionStatus(RedemptionStatus.PENDING_INVESTOR_ACTION);
        // assign id via reflection-free helper: use a real saved-style record
        when(orderService.createRedemptionDraft(
                orderId, accountId, WithdrawalRequest.WithdrawalMode.UNITS, new BigDecimal("10"), false))
                .thenReturn(savedRedemption(redemptionId));
        TransactionApprovalChallenge challenge = challenge(redemptionId, TransactionType.REDEMPTION);
        when(approvalService.createChallenge(redemptionId, TransactionType.REDEMPTION, accountId))
                .thenReturn(challenge);

        Map<String, Object> result = controller.requestWithdrawalSelf(
                new WithdrawalRequest(orderId, null, WithdrawalRequest.WithdrawalMode.UNITS,
                        new BigDecimal("10"), false),
                investorAuth());

        verify(orderService).createRedemptionDraft(
                orderId, accountId, WithdrawalRequest.WithdrawalMode.UNITS, new BigDecimal("10"), false);
        verify(approvalService).createChallenge(redemptionId, TransactionType.REDEMPTION, accountId);
        verify(orderService, never()).submitRedemptionToProvider(any());
        assertThat(result.get("challengeId")).isEqualTo(challenge.getId());
        assertThat(result.get("mode")).isEqualTo("SELF");
    }

    // ── FR-2FA-007: a distributor principal cannot use the investor endpoints ──

    @Test
    void distributorPrincipalIsRejectedFromApprove() {
        assertThatThrownBy(() -> controller.approve(
                UUID.randomUUID(), new ApprovalRequest(true, "123456"), request(), distributorAuth()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(approvalService, investorActionService, orderService);
    }

    @Test
    void distributorPrincipalIsRejectedFromOtpRequest() {
        assertThatThrownBy(() -> controller.requestApprovalOtp(UUID.randomUUID(), distributorAuth()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(approvalService);
    }

    @Test
    void distributorPrincipalIsRejectedFromListApprovals() {
        assertThatThrownBy(() -> controller.listApprovals(distributorAuth()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(approvalService);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubAccount() {
        InvestorAccount account = new InvestorAccount();
        setId(account, accountId);
        account.setInvestorId(investorId);
        account.setEmail("investor@example.com");
        when(investorAuthService.requireAccount(accountId)).thenReturn(account);
    }

    private TransactionApprovalChallenge challenge(UUID transactionId, TransactionType type) {
        TransactionApprovalChallenge challenge = new TransactionApprovalChallenge();
        setId(challenge, UUID.randomUUID());
        challenge.setTransactionId(transactionId);
        challenge.setTransactionType(type);
        challenge.setInvestorAccountId(accountId);
        challenge.setInvestorId(investorId);
        challenge.setStatus(TransactionApprovalStatus.CHALLENGE_SENT);
        challenge.setMaskedDestination("i***@example.com");
        challenge.setChannel("EMAIL");
        return challenge;
    }

    private RedemptionRecord savedRedemption(UUID id) {
        RedemptionRecord record = new RedemptionRecord();
        setId(record, id);
        record.setRedemptionStatus(RedemptionStatus.PENDING_INVESTOR_ACTION);
        return record;
    }

    private InvestorActionPage page(UUID orderId, String redirect, String message) {
        return new InvestorActionPage(
                "token", orderId, "ext", "Investor", "investor@example.com", "Scheme", "AMC",
                new BigDecimal("1000"), null, "LUMPSUM_PURCHASE", OrderStatus.PAYMENT_PENDING,
                "NET_BANKING", null, null, null, null, null, null, false, message, redirect,
                false, false, "{}");
    }

    private Authentication investorAuth() {
        AuthenticatedInvestorPrincipal principal =
                new AuthenticatedInvestorPrincipal(accountId, "investor@example.com");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private Authentication distributorAuth() {
        AuthenticatedDistributorPrincipal principal = new AuthenticatedDistributorPrincipal(
                UUID.randomUUID(), "dist@example.com", "", DistributorRole.SUB_DISTRIBUTOR,
                List.of(new SimpleGrantedAuthority("ROLE_SUB_DISTRIBUTOR")));
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("User-Agent")).thenReturn("JUnit");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        return request;
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
