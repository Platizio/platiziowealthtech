package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvestorBankAccountRepository extends JpaRepository<InvestorBankAccount, UUID> {
    List<InvestorBankAccount> findByInvestorId(UUID investorId);

    Optional<InvestorBankAccount> findByCybrillaBankVerificationId(String cybrillaBankVerificationId);

    @Query("""
            select b
            from InvestorBankAccount b
            where b.verificationStatus in :statuses
              and (
                    b.cybrillaBankVerificationId is not null
                 or b.externalSyncPending = true
              )
            order by b.updatedAt asc
            """)
    List<InvestorBankAccount> findBankVerificationSyncCandidates(
            @Param("statuses") List<BankVerificationStatus> statuses,
            Pageable pageable
    );
}
