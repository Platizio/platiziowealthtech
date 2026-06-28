package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorLinkingStatus;
import com.platizio.wealthtech.dto.SendToInvestorRequest;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvestorServiceCreateOrUpdatePendingTest {

    @Mock private InvestorRepository investorRepository;
    @Mock private AuditService auditService;
    private InvestorService service;

    private final UUID distributorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Only the repository + audit collaborators are exercised by createOrUpdatePendingInvestor.
        service = new InvestorService(investorRepository, null, null, auditService, null);
        lenient().when(investorRepository.save(any(Investor.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private SendToInvestorRequest request() {
        return new SendToInvestorRequest(
                "Asha Rao", "9999999999", "asha@example.com", "ABCDE1234F",
                LocalDate.of(1990, 1, 1), "{\"step\":1}");
    }

    @Test
    void newPanParksPendingWithDistributorNullAndPendingSet() {
        when(investorRepository.findByPan("ABCDE1234F")).thenReturn(Optional.empty());

        Investor result = service.createOrUpdatePendingInvestor(request(), distributorId);

        assertThat(result.getLinkingStatus()).isEqualTo(InvestorLinkingStatus.PENDING_INVESTOR_APPROVAL);
        assertThat(result.getDistributorId()).isNull();
        assertThat(result.getPendingDistributorId()).isEqualTo(distributorId);
        assertThat(result.getFullName()).isEqualTo("Asha Rao");
        assertThat(result.getPan()).isEqualTo("ABCDE1234F");
        assertThat(result.getEmail()).isEqualTo("asha@example.com");
        assertThat(result.getMobileNumber()).isEqualTo("9999999999");
        assertThat(result.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        // households are NOT NULL — a self folio keys its own household until linked.
        assertThat(result.getHouseholdId()).isNotNull();
    }

    @Test
    void existingPendingPanIsRefreshedAndReParked() {
        Investor existing = new Investor();
        existing.setPan("ABCDE1234F");
        existing.setFullName("Old Name");
        existing.setLinkingStatus(InvestorLinkingStatus.PENDING_INVESTOR_APPROVAL);
        UUID priorDistributor = UUID.randomUUID();
        existing.setPendingDistributorId(priorDistributor);
        when(investorRepository.findByPan("ABCDE1234F")).thenReturn(Optional.of(existing));

        Investor result = service.createOrUpdatePendingInvestor(request(), distributorId);

        assertThat(result).isSameAs(existing);
        assertThat(result.getFullName()).isEqualTo("Asha Rao");
        assertThat(result.getDistributorId()).isNull();
        assertThat(result.getPendingDistributorId()).isEqualTo(distributorId);
        assertThat(result.getLinkingStatus()).isEqualTo(InvestorLinkingStatus.PENDING_INVESTOR_APPROVAL);
    }

    @Test
    void existingReadyInvestorIsRejectedAndNotClobbered() {
        Investor existing = new Investor();
        existing.setPan("ABCDE1234F");
        existing.setFullName("Linked Name");
        existing.setDistributorId(UUID.randomUUID());
        existing.setLinkingStatus(InvestorLinkingStatus.READY);
        when(investorRepository.findByPan("ABCDE1234F")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createOrUpdatePendingInvestor(request(), distributorId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already linked");
        // The live linked investor is untouched (R5 guard) and nothing is saved.
        assertThat(existing.getFullName()).isEqualTo("Linked Name");
        assertThat(existing.getPendingDistributorId()).isNull();
        verify(investorRepository, never()).save(any());
    }
}
