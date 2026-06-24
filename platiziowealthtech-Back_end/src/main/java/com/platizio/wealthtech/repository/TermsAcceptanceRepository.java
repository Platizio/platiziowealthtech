package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.TermsAcceptance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TermsAcceptanceRepository extends JpaRepository<TermsAcceptance, UUID> {

    Optional<TermsAcceptance> findTopBySubjectTypeAndSubjectIdAndDocumentKeyOrderByAcceptedAtDesc(
            String subjectType, UUID subjectId, String documentKey);

    List<TermsAcceptance> findBySubjectTypeAndSubjectIdOrderByAcceptedAtDesc(
            String subjectType, UUID subjectId);
}
