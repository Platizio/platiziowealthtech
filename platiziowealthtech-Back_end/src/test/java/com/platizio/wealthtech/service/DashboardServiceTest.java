package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.KycStatus;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.domain.TransactionOrder;
import com.platizio.wealthtech.domain.TransactionType;
import com.platizio.wealthtech.dto.SipDashboardDto;
import com.platizio.wealthtech.dto.SipItemDto;
import com.platizio.wealthtech.repository.InvestorRepository;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.platizio.wealthtech.repository.TransactionOrderRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

class DashboardServiceTest {

    @Test
    void getSipDashboardFetchesSipOrdersAtDatabaseLevel() {
        // B-71: this used to assert PageRequest.of(0, 50) was used, locking
        // in the silent 50-row truncation. The valuable invariant the test
        // was guarding — that filtering by transaction type happens at the
        // DB layer rather than via a fetch-all-then-filter-in-Java pattern
        // — is preserved here via the verify(...never()) on findByDistributorId.
        UUID distributorId = UUID.randomUUID();
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        TransactionOrderRepository orderRepository = mock(TransactionOrderRepository.class);
        ProductSchemeRepository schemeRepository = mock(ProductSchemeRepository.class);
        DashboardService dashboardService = new DashboardService(
                investorRepository,
                orderRepository,
                schemeRepository
        );

        when(orderRepository.findByDistributorIdAndTransactionType(
                eq(distributorId),
                eq(TransactionType.SIP)))
                .thenReturn(List.of());
        when(investorRepository.findByDistributorId(distributorId)).thenReturn(List.of());
        when(schemeRepository.findAllById(any())).thenReturn(List.of());
        when(orderRepository.findByDistributorIdAndTransactionTypeAndCreatedAtAfter(
                eq(distributorId),
                eq(TransactionType.SIP),
                any(OffsetDateTime.class)))
                .thenReturn(List.of());
        when(orderRepository.countByDistributorIdAndTransactionTypeGroupedByOrderStatus(
                distributorId,
                TransactionType.SIP))
                .thenReturn(List.of());

        dashboardService.getSipDashboard(distributorId);

        // The service must hit the type-filtered DB query, not pull every
        // order for the distributor and filter SIPs in-memory.
        verify(orderRepository).findByDistributorIdAndTransactionType(
                eq(distributorId), eq(TransactionType.SIP));
        verify(orderRepository, never()).findByDistributorId(distributorId);
        verify(investorRepository, never()).findByDistributorId(distributorId);
    }

    @Test
    void getSipDashboardUsesExplicitSipStatusLabelsAndGroupedCounts() {
        UUID distributorId = UUID.randomUUID();
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        TransactionOrderRepository orderRepository = mock(TransactionOrderRepository.class);
        ProductSchemeRepository schemeRepository = mock(ProductSchemeRepository.class);
        DashboardService dashboardService = new DashboardService(
                investorRepository,
                orderRepository,
                schemeRepository
        );

        when(orderRepository.findByDistributorIdAndTransactionType(
                eq(distributorId),
                eq(TransactionType.SIP)))
                .thenReturn(List.of(
                        order(OrderStatus.PROCESSING),
                        order(OrderStatus.DRAFT),
                        order(OrderStatus.COMPLETED),
                        order(OrderStatus.FAILED)
                ));
        when(investorRepository.findAllById(any())).thenReturn(List.of());
        when(schemeRepository.findAllById(any())).thenReturn(List.of());
        when(orderRepository.findByDistributorIdAndTransactionTypeAndCreatedAtAfter(
                eq(distributorId),
                eq(TransactionType.SIP),
                any(OffsetDateTime.class)))
                .thenReturn(List.of());
        when(orderRepository.countByDistributorIdAndTransactionTypeGroupedByOrderStatus(
                distributorId,
                TransactionType.SIP))
                .thenReturn(List.of(
                        statusCount(OrderStatus.ACTIVE, 2),
                        statusCount(OrderStatus.PROCESSING, 3),
                        statusCount(OrderStatus.SUCCESSFUL, 7),
                        statusCount(OrderStatus.DRAFT, 4),
                        statusCount(OrderStatus.COMPLETED, 5),
                        statusCount(OrderStatus.FAILED, 6)
                ));

        SipDashboardDto dashboard = dashboardService.getSipDashboard(distributorId);

        assertThat(dashboard.getSips())
                .extracting(SipItemDto::getStatus)
                .containsExactly("Active SIPs", "Paused SIPs", "Completed SIPs", "Failed SIPs");
        assertThat(dashboard.getStatusCounts()).containsAllEntriesOf(Map.of(
                "Active SIPs", 12L,
                "Paused SIPs", 4L,
                "Completed SIPs", 5L,
                "Failed SIPs", 6L
        ));
    }

    @Test
    void getActionCenterUsesBoundedStatusQueriesInsteadOfDistributorScans() {
        UUID distributorId = UUID.randomUUID();
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        TransactionOrderRepository orderRepository = mock(TransactionOrderRepository.class);
        ProductSchemeRepository schemeRepository = mock(ProductSchemeRepository.class);
        DashboardService dashboardService = new DashboardService(
                investorRepository,
                orderRepository,
                schemeRepository
        );

        when(investorRepository.findByDistributorIdAndKycStatusNot(
                eq(distributorId),
                eq(KycStatus.COMPLETED),
                any(Pageable.class)))
                .thenReturn(List.of());
        when(investorRepository.findByDistributorIdAndBankVerificationStatusNot(
                eq(distributorId),
                eq(BankVerificationStatus.VERIFIED),
                any(Pageable.class)))
                .thenReturn(List.of());
        when(orderRepository.findByDistributorIdAndOrderStatus(
                eq(distributorId),
                eq(OrderStatus.FAILED),
                any(Pageable.class)))
                .thenReturn(List.of());

        dashboardService.getActionCenter(distributorId);

        ArgumentCaptor<Pageable> kycPageCaptor = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> bankPageCaptor = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> failedOrderPageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(investorRepository).findByDistributorIdAndKycStatusNot(
                eq(distributorId),
                eq(KycStatus.COMPLETED),
                kycPageCaptor.capture());
        verify(investorRepository).findByDistributorIdAndBankVerificationStatusNot(
                eq(distributorId),
                eq(BankVerificationStatus.VERIFIED),
                bankPageCaptor.capture());
        verify(orderRepository).findByDistributorIdAndOrderStatus(
                eq(distributorId),
                eq(OrderStatus.FAILED),
                failedOrderPageCaptor.capture());
        assertDashboardPage(kycPageCaptor.getValue());
        assertDashboardPage(bankPageCaptor.getValue());
        assertDashboardPage(failedOrderPageCaptor.getValue());
        verify(investorRepository, never()).findByDistributorId(distributorId);
        verify(orderRepository, never()).findByDistributorId(distributorId);
    }

    @Test
    void getOnboardingPipelineUsesBoundedStatusQueriesInsteadOfDistributorScan() {
        UUID distributorId = UUID.randomUUID();
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        TransactionOrderRepository orderRepository = mock(TransactionOrderRepository.class);
        ProductSchemeRepository schemeRepository = mock(ProductSchemeRepository.class);
        DashboardService dashboardService = new DashboardService(
                investorRepository,
                orderRepository,
                schemeRepository
        );

        when(investorRepository.findByDistributorIdAndKycStatusNot(
                eq(distributorId),
                eq(KycStatus.COMPLETED),
                any(Pageable.class)))
                .thenReturn(List.of());
        when(investorRepository.findByDistributorIdAndKycStatusAndBankVerificationStatusNot(
                eq(distributorId),
                eq(KycStatus.COMPLETED),
                eq(BankVerificationStatus.VERIFIED),
                any(Pageable.class)))
                .thenReturn(List.of());
        when(investorRepository.findByDistributorIdAndKycStatusAndBankVerificationStatus(
                eq(distributorId),
                eq(KycStatus.COMPLETED),
                eq(BankVerificationStatus.VERIFIED),
                any(Pageable.class)))
                .thenReturn(List.of());

        dashboardService.getOnboardingPipeline(distributorId);

        ArgumentCaptor<Pageable> kycPageCaptor = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> bankPageCaptor = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> readyPageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(investorRepository).findByDistributorIdAndKycStatusNot(
                eq(distributorId),
                eq(KycStatus.COMPLETED),
                kycPageCaptor.capture());
        verify(investorRepository).findByDistributorIdAndKycStatusAndBankVerificationStatusNot(
                eq(distributorId),
                eq(KycStatus.COMPLETED),
                eq(BankVerificationStatus.VERIFIED),
                bankPageCaptor.capture());
        verify(investorRepository).findByDistributorIdAndKycStatusAndBankVerificationStatus(
                eq(distributorId),
                eq(KycStatus.COMPLETED),
                eq(BankVerificationStatus.VERIFIED),
                readyPageCaptor.capture());
        assertDashboardPage(kycPageCaptor.getValue());
        assertDashboardPage(bankPageCaptor.getValue());
        assertDashboardPage(readyPageCaptor.getValue());
        verify(investorRepository, never()).findByDistributorId(distributorId);
    }

    private void assertDashboardPage(Pageable pageable) {
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(50);
        Sort.Order createdAtOrder = pageable.getSort().getOrderFor("createdAt");
        assertThat(createdAtOrder).isNotNull();
        assertThat(createdAtOrder.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    private TransactionOrder order(OrderStatus status) {
        TransactionOrder order = new TransactionOrder();
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.setInvestorId(UUID.randomUUID());
        order.setDistributorId(UUID.randomUUID());
        order.setTransactionType(TransactionType.SIP);
        order.setOrderStatus(status);
        return order;
    }

    private TransactionOrderRepository.OrderStatusCount statusCount(OrderStatus status, long total) {
        return new TransactionOrderRepository.OrderStatusCount() {
            @Override
            public OrderStatus getOrderStatus() {
                return status;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
    }
}
