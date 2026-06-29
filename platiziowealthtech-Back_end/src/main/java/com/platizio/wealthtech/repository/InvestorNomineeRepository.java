package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.InvestorNominee;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface InvestorNomineeRepository extends JpaRepository<InvestorNominee, UUID> {

    List<InvestorNominee> findByInvestorIdOrderByNomineeIndexAsc(UUID investorId);

    @Transactional
    void deleteByInvestorId(UUID investorId);
}
