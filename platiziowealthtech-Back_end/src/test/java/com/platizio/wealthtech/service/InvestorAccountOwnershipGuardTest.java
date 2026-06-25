package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.repository.InvestorAccountRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class InvestorAccountOwnershipGuardTest {

    @Mock private InvestorAccountRepository investorAccountRepository;
    @InjectMocks private InvestorAccountOwnershipGuard guard;

    private final UUID accountId = UUID.randomUUID();
    private final UUID investorId = UUID.randomUUID();

    private InvestorAccount account(UUID linkedInvestorId) {
        InvestorAccount acc = new InvestorAccount();
        acc.setFullName("Asha Rao");
        acc.setPan("ABCDE1234F");
        acc.setEmail("asha@example.com");
        acc.setMobileNumber("9999999999");
        acc.setInvestorId(linkedInvestorId);
        return acc;
    }

    @Test
    void assertOwnsReturnsAccountWhenInvestorIdMatches() {
        InvestorAccount acc = account(investorId);
        when(investorAccountRepository.findById(accountId)).thenReturn(Optional.of(acc));

        InvestorAccount result = guard.assertOwns(accountId, investorId);

        assertThat(result).isSameAs(acc);
    }

    @Test
    void assertOwnsThrowsForbiddenWhenInvestorIdDiffers() {
        InvestorAccount acc = account(UUID.randomUUID());
        when(investorAccountRepository.findById(accountId)).thenReturn(Optional.of(acc));

        assertThatThrownBy(() -> guard.assertOwns(accountId, investorId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("You are not authorized to act on this investor.");
    }

    @Test
    void assertOwnsThrowsForbiddenWhenAccountNotYetLinked() {
        InvestorAccount acc = account(null);
        when(investorAccountRepository.findById(accountId)).thenReturn(Optional.of(acc));

        assertThatThrownBy(() -> guard.assertOwns(accountId, investorId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("You are not authorized to act on this investor.");
    }

    @Test
    void assertOwnsThrowsNotFoundWhenAccountMissing() {
        when(investorAccountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.assertOwns(accountId, investorId))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
