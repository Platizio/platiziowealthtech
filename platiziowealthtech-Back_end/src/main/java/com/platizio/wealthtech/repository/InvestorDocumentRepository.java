package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.InvestorDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorDocumentRepository extends JpaRepository<InvestorDocument, UUID> {

    Optional<InvestorDocument> findByInvestorIdAndDocumentType(UUID investorId, String documentType);

    List<InvestorDocument> findAllByInvestorIdOrderByDocumentTypeAsc(UUID investorId);

    Optional<InvestorDocument> findByInvestorIdAndNomineeIdAndDocumentType(
            UUID investorId, UUID nomineeId, String documentType);

    List<InvestorDocument> findAllByInvestorIdAndDocumentType(UUID investorId, String documentType);
}
