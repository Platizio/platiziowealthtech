package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.ConsentRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    List<ConsentRecord> findBySubjectTypeAndSubjectIdOrderByAcceptedAtDesc(String subjectType, UUID subjectId);
}
