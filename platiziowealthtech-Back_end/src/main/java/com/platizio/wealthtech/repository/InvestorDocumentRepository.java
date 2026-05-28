package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.InvestorDocument;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorDocumentRepository extends JpaRepository<InvestorDocument, UUID> {

    Optional<InvestorDocument> findByInvestorIdAndDocumentType(UUID investorId, String documentType);
}
