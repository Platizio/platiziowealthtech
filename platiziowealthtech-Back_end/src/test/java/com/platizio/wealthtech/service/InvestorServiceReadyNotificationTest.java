package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorStatus;
import com.platizio.wealthtech.domain.NotificationType;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PA6/R8 (receive-half): the READY transition (markReadyAfterInvestorApproval) notifies the
 * linked distributor in-app, typed INVESTOR_FORM_SUBMITTED. Pure-Mockito; the notification
 * service is wired via the optional setter and null-guarded.
 */
@ExtendWith(MockitoExtension.class)
class InvestorServiceReadyNotificationTest {

    @Mock private InvestorRepository investorRepository;
    @Mock private AuditService auditService;
    @Mock private DistributorService distributorService;
    @Mock private NotificationService notificationService;

    private InvestorService service;

    private final UUID investorId = UUID.randomUUID();
    private final UUID distributorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new InvestorService(
                investorRepository,
                null,
                distributorService,
                auditService,
                null,
                null,
                900_000L,
                null,
                null,
                null,
                null);
        service.setNotificationService(notificationService);
        lenient().when(investorRepository.save(any(Investor.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // The acting distributor owns the investor (actorId == distributor_id passes the guard).
        Distributor actor = new Distributor();
        actor.setRole(DistributorRole.SUB_DISTRIBUTOR);
        lenient().when(distributorService.getDistributor(distributorId)).thenReturn(actor);
    }

    private Investor linkedInvestor() {
        Investor i = new Investor();
        ReflectionTestUtils.setField(i, "id", investorId);
        i.setDistributorId(distributorId);
        return i;
    }

    @Test
    void markReady_notifiesLinkedDistributor_withFormSubmittedType() {
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(linkedInvestor()));

        Investor result = service.markReadyAfterInvestorApproval(investorId, distributorId);

        assertThat(result.getInvestorStatus()).isEqualTo(InvestorStatus.READY_FOR_TRANSACTIONS);
        verify(notificationService).createForDistributor(
                eq(distributorId), eq(investorId), eq(NotificationType.INVESTOR_FORM_SUBMITTED), any(), any());
    }

    @Test
    void markReady_withoutNotificationService_isNoOp() {
        // Legacy/short-constructor path: notificationService not injected → null-guarded, no emit.
        service.setNotificationService(null);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(linkedInvestor()));

        Investor result = service.markReadyAfterInvestorApproval(investorId, distributorId);

        assertThat(result.getInvestorStatus()).isEqualTo(InvestorStatus.READY_FOR_TRANSACTIONS);
        verify(notificationService, never()).createForDistributor(any(), any(), any(), any(), any());
    }
}
