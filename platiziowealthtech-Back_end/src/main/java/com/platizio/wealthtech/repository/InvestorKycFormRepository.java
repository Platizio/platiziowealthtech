package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.InvestorKycForm;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorKycFormRepository extends JpaRepository<InvestorKycForm, UUID> {

    Optional<InvestorKycForm> findFirstByInvestorIdOrderByCreatedAtDesc(UUID investorId);

    Optional<InvestorKycForm> findByExternalKycFormId(String externalKycFormId);

    Optional<InvestorKycForm> findFirstByInvestorIdAndExternalKycFormId(UUID investorId, String externalKycFormId);

    Optional<InvestorKycForm> findFirstByInvestorIdAndStatusInOrderByCreatedAtDesc(UUID investorId, List<String> statuses);

    List<InvestorKycForm> findByStatusIn(List<String> statuses);
}
