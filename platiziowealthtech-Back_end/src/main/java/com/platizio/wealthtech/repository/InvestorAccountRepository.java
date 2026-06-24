package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.InvestorAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorAccountRepository extends JpaRepository<InvestorAccount, UUID> {

    Optional<InvestorAccount> findByEmailIgnoreCase(String email);

    Optional<InvestorAccount> findByPan(String pan);

    /**
     * Resolves the self-service investor account linked (by PAN, on confirmation)
     * to a distributor-created investor profile. Phase-2: the distributor
     * request-investor-approval flow maps {@code order.investorId} → the account
     * that the approval challenge binds to ({@code investor_account_id}).
     */
    Optional<InvestorAccount> findByInvestorId(UUID investorId);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByPan(String pan);
}
