package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.InvestorAccount;
import com.platizio.wealthtech.repository.InvestorAccountRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Shared ownership guard for self-service investor-portal actions.
 *
 * <p>The authenticated {@link InvestorAccount} (login identity) is bound to a
 * distributor-created {@code Investor} only by PAN, on explicit confirmation
 * ({@code account.investorId}). Any action a logged-in investor takes against a
 * specific investor profile must therefore prove that <em>their</em> account owns
 * that profile — otherwise a caller who merely knows a token / id could act on a
 * profile belonging to a DIFFERENT investor (IDOR).
 *
 * <p>This is the single reusable check: load the acting account and assert it is
 * confirmed-linked to the target {@code investorId}. It returns the loaded account
 * so callers can do further binding (e.g. a PAN match against the request).
 */
@Component
public class InvestorAccountOwnershipGuard {

    private final InvestorAccountRepository investorAccountRepository;

    public InvestorAccountOwnershipGuard(InvestorAccountRepository investorAccountRepository) {
        this.investorAccountRepository = investorAccountRepository;
    }

    /**
     * Asserts the investor account identified by {@code accountId} is confirmed-linked
     * to the distributor-created investor {@code investorId}.
     *
     * @param accountId  the authenticated self-service investor account id (login identity)
     * @param investorId the target distributor-created investor the action operates on
     * @return the loaded {@link InvestorAccount} (so callers can do further checks)
     * @throws EntityNotFoundException if no account exists for {@code accountId}
     * @throws AccessDeniedException   if the account is not linked to {@code investorId} (HTTP 403)
     */
    public InvestorAccount assertOwns(UUID accountId, UUID investorId) {
        InvestorAccount account = investorAccountRepository.findById(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Investor account not found: " + accountId));
        if (account.getInvestorId() == null || !account.getInvestorId().equals(investorId)) {
            throw new AccessDeniedException("You are not authorized to act on this investor.");
        }
        return account;
    }
}
