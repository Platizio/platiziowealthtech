package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.platizio.wealthtech.domain.InvestorNominee;
import com.platizio.wealthtech.dto.NomineeDto;
import com.platizio.wealthtech.repository.InvestorNomineeRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-Mockito coverage of InvestorService.replaceNominees (IRIS Phase 1):
 * &lt;=3 ok, &gt;3 throws, share-sum != 100 throws, null/empty clears, and the
 * delete-then-insert ordering. The nominee repository is injected via the optional
 * setter; the rest of the service collaborators are unused here.
 */
@ExtendWith(MockitoExtension.class)
class InvestorServiceNomineeTest {

    @Mock private InvestorNomineeRepository investorNomineeRepository;

    private InvestorService service;
    private final UUID investorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new InvestorService(null, null, null, null, null);
        service.setInvestorNomineeRepository(investorNomineeRepository);
    }

    private NomineeDto nominee(String name, BigDecimal share) {
        return new NomineeDto(
                null, name, null, "spouse", share, null, null, null, null,
                null, null, null, null, null, null, null, false);
    }

    @Test
    void replaceNominees_threeWithSum100_savesAllAfterDelete() {
        List<NomineeDto> nominees = List.of(
                nominee("A", new BigDecimal("40")),
                nominee("B", new BigDecimal("35")),
                nominee("C", new BigDecimal("25")));

        service.replaceNominees(investorId, nominees);

        // delete-then-insert: clear runs before any save.
        InOrder order = inOrder(investorNomineeRepository);
        order.verify(investorNomineeRepository).deleteByInvestorId(investorId);
        ArgumentCaptor<InvestorNominee> captor = ArgumentCaptor.forClass(InvestorNominee.class);
        order.verify(investorNomineeRepository, org.mockito.Mockito.times(3)).save(captor.capture());

        // Stable 0-based index assigned from list order; investor FK set on each row.
        assertThat(captor.getAllValues())
                .extracting(InvestorNominee::getNomineeIndex)
                .containsExactly(0, 1, 2);
        assertThat(captor.getAllValues())
                .allMatch(n -> investorId.equals(n.getInvestorId()));
        assertThat(captor.getAllValues())
                .extracting(InvestorNominee::getFullName)
                .containsExactly("A", "B", "C");
    }

    @Test
    void replaceNominees_moreThanThree_throwsAndDoesNotTouchRepo() {
        List<NomineeDto> nominees = List.of(
                nominee("A", new BigDecimal("25")),
                nominee("B", new BigDecimal("25")),
                nominee("C", new BigDecimal("25")),
                nominee("D", new BigDecimal("25")));

        assertThatThrownBy(() -> service.replaceNominees(investorId, nominees))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 nominees");
        verify(investorNomineeRepository, never()).deleteByInvestorId(any());
        verify(investorNomineeRepository, never()).save(any());
    }

    @Test
    void replaceNominees_shareSumNot100_throwsAndDoesNotTouchRepo() {
        List<NomineeDto> nominees = List.of(
                nominee("A", new BigDecimal("40")),
                nominee("B", new BigDecimal("40")));

        assertThatThrownBy(() -> service.replaceNominees(investorId, nominees))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to 100");
        verify(investorNomineeRepository, never()).deleteByInvestorId(any());
        verify(investorNomineeRepository, never()).save(any());
    }

    @Test
    void replaceNominees_nullList_clearsExistingNominees() {
        service.replaceNominees(investorId, null);

        verify(investorNomineeRepository).deleteByInvestorId(investorId);
        verify(investorNomineeRepository, never()).save(any());
    }

    @Test
    void replaceNominees_emptyList_clearsExistingNominees() {
        service.replaceNominees(investorId, List.of());

        verify(investorNomineeRepository).deleteByInvestorId(investorId);
        verify(investorNomineeRepository, never()).save(any());
    }

    @Test
    void replaceNominees_noRepository_isNoOp() {
        InvestorService noRepoService = new InvestorService(null, null, null, null, null);

        // Must not throw even with an invalid (>3) list — the null-guard returns first.
        noRepoService.replaceNominees(investorId, List.of(
                nominee("A", new BigDecimal("100"))));
    }
}
