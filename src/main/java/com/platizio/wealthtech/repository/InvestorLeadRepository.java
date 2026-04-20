package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.InvestorLead;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorLeadRepository extends JpaRepository<InvestorLead, UUID> {
    List<InvestorLead> findByAssignedDistributorId(UUID distributorId);
}