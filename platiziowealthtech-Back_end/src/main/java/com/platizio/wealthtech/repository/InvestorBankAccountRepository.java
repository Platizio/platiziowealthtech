package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.InvestorBankAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorBankAccountRepository extends JpaRepository<InvestorBankAccount, UUID> {
    List<InvestorBankAccount> findByInvestorId(UUID investorId);
}