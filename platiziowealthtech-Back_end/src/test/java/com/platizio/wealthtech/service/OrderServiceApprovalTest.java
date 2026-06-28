package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.TransactionApprovalChallenge;
import com.platizio.wealthtech.domain.TransactionApprovalStatus;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.OtpRequestResponse;
import com.platizio.wealthtech.repository.InvestorAccountRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import com.platizio.wealthtech.security.JwtAuthPrincipal;
import jakarta.persistence.EntityNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pure-Mockito tests for the Phase-2 distributor approval orchestration on
 * {@link OrderService}: request-investor-approval freezes a challenge against the
 * resolved investor account and flips the order to PENDING_INVESTOR_ACTION;
 * resend-approval-link delegates with {@code isDistributorResend=true} and never
 * exposes the code; ownership is enforced before any side effect.
 */
class OrderServiceApprovalTest {

    private final TransactionOrderRepository orderRepository = mock(TransactionOrderRepository.class);
    private final TransactionApprovalService approvalService = mock(TransactionApprovalService.class);
    private final InvestorAccountRepository accountRepository = mock(InvestorAccountRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);

    private final OrderService orderService = new OrderService(
            orderRepository, null, null, auditService, notificationService, null, null, null,
            approvalService, accountRepository);

    private final UUID distributorId = UUID.randomUUID();
    private final UUID investorId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @Test
    void requestInvestorApprovalCreatesPurchaseChallengeAndSetsPendingInvestorAction() {
        UUID orderId = UUID.randomUUID();
        TransactionOrder order = order(orderId, TransactionType.LUMPSUM_PURCHASE);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(accountRepository.findByInvestorId(investorId)).thenReturn(Optional.of(account()));
        when(approvalService.createChallenge(orderId, TransactionType.PURCHASE, accountId))
                .thenReturn(challenge());
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrderService.ApprovalRequestResult result =
                orderService.requestInvestorApproval(orderId, principal(DistributorRole.SUB_DISTRIBUTOR, distributorId));

        verify(approvalService).createChallenge(orderId, TransactionType.PURCHASE, accountId);
        ArgumentCaptor<TransactionOrder> saved = ArgumentCaptor.forClass(TransactionOrder.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getOrderStatus()).isEqualTo(OrderStatus.PENDING_INVESTOR_ACTION);
        assertThat(result.transactionType()).isEqualTo("PURCHASE");
        assertThat(result.maskedDestination()).isEqualTo("i***@example.com");
    }

    @Test
    void requestInvestorApprovalUsesSipChallengeForSipOrders() {
        UUID orderId = UUID.randomUUID();
        TransactionOrder order = order(orderId, TransactionType.SIP);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(accountRepository.findByInvestorId(investorId)).thenReturn(Optional.of(account()));
        when(approvalService.createChallenge(orderId, TransactionType.SIP, accountId)).thenReturn(challenge());
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orderService.requestInvestorApproval(orderId, principal(DistributorRole.SUB_DISTRIBUTOR, distributorId));

        verify(approvalService).createChallenge(orderId, TransactionType.SIP, accountId);
    }

    @Test
    void requestInvestorApprovalRejectsForeignDistributor() {
        UUID orderId = UUID.randomUUID();
        TransactionOrder order = order(orderId, TransactionType.LUMPSUM_PURCHASE);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.requestInvestorApproval(
                orderId, principal(DistributorRole.SUB_DISTRIBUTOR, UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(approvalService);
    }

    @Test
    void requestInvestorApprovalFailsWhenNoInvestorAccountLinked() {
        UUID orderId = UUID.randomUUID();
        TransactionOrder order = order(orderId, TransactionType.LUMPSUM_PURCHASE);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(accountRepository.findByInvestorId(investorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.requestInvestorApproval(
                orderId, principal(DistributorRole.SUB_DISTRIBUTOR, distributorId)))
                .isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(approvalService);
    }

    @Test
    void resendApprovalLinkDelegatesWithDistributorResendFlagAndHidesCode() {
        UUID orderId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        TransactionOrder order = order(orderId, TransactionType.LUMPSUM_PURCHASE);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(approvalService.requestApprovalOtp(challengeId, distributorId, true))
                .thenReturn(new OtpRequestResponse("sent", 300, 30, null));

        OtpRequestResponse response = orderService.resendApprovalLink(
                orderId, challengeId, principal(DistributorRole.SUB_DISTRIBUTOR, distributorId));

        verify(approvalService).requestApprovalOtp(challengeId, distributorId, true);
        assertThat(response.devCode()).isNull();
    }

    @Test
    void resendApprovalLinkRejectsForeignDistributor() {
        UUID orderId = UUID.randomUUID();
        TransactionOrder order = order(orderId, TransactionType.LUMPSUM_PURCHASE);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.resendApprovalLink(
                orderId, UUID.randomUUID(), principal(DistributorRole.SUB_DISTRIBUTOR, UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(approvalService);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private TransactionOrder order(UUID orderId, TransactionType type) {
        TransactionOrder order = new TransactionOrder();
        setId(order, orderId);
        order.setDistributorId(distributorId);
        order.setInvestorId(investorId);
        order.setTransactionType(type);
        order.setOrderStatus(OrderStatus.CREATED);
        return order;
    }

    private InvestorAccount account() {
        InvestorAccount account = new InvestorAccount();
        setId(account, accountId);
        account.setInvestorId(investorId);
        account.setEmail("investor@example.com");
        return account;
    }

    private TransactionApprovalChallenge challenge() {
        TransactionApprovalChallenge challenge = new TransactionApprovalChallenge();
        setId(challenge, UUID.randomUUID());
        challenge.setStatus(TransactionApprovalStatus.PENDING);
        challenge.setMaskedDestination("i***@example.com");
        return challenge;
    }

    private JwtAuthPrincipal principal(DistributorRole role, UUID id) {
        return new JwtAuthPrincipal() {
            @Override
            public UUID getDistributorId() {
                return id;
            }

            @Override
            public DistributorRole getRole() {
                return role;
            }
        };
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
