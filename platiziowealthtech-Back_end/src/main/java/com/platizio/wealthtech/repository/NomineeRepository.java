package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.Nominee;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NomineeRepository extends JpaRepository<Nominee, UUID> {

    List<Nominee> findByInvestorId(UUID investorId);
}
