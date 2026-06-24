package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.TermsAcceptance;
import com.platizio.wealthtech.repository.TermsAcceptanceRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and reads Terms &amp; Conditions acceptances (Task 4 / DF-10). Each
 * {@link #record} appends a row (re-acceptance of a new version is allowed and
 * stored) and emits a {@code TERMS_ACCEPTED} audit event. Subject is generic
 * (INVESTOR | DISTRIBUTOR); ownership is enforced by the caller.
 */
@Service
public class TermsAcceptanceService {

    public static final String SUBJECT_INVESTOR = "INVESTOR";

    private final TermsAcceptanceRepository repository;
    private final AuditService auditService;

    public TermsAcceptanceService(TermsAcceptanceRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public TermsAcceptance record(
            String subjectType, UUID subjectId, String documentKey, String version,
            String ipAddress, String userAgent, UUID actorId) {
        TermsAcceptance acceptance = new TermsAcceptance();
        acceptance.setSubjectType(subjectType);
        acceptance.setSubjectId(subjectId);
        acceptance.setDocumentKey(documentKey);
        acceptance.setVersion(version);
        acceptance.setAcceptedAt(OffsetDateTime.now());
        acceptance.setIpAddress(truncate(ipAddress, 64));
        acceptance.setUserAgent(truncate(userAgent, 512));
        TermsAcceptance saved = repository.save(acceptance);
        auditService.log(subjectType, subjectId, "TERMS_ACCEPTED", actorId,
                "{\"document_key\":\"" + documentKey + "\",\"version\":\"" + version + "\"}");
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<TermsAcceptance> getLatest(String subjectType, UUID subjectId, String documentKey) {
        return repository.findTopBySubjectTypeAndSubjectIdAndDocumentKeyOrderByAcceptedAtDesc(
                subjectType, subjectId, documentKey);
    }

    @Transactional(readOnly = true)
    public List<TermsAcceptance> list(String subjectType, UUID subjectId) {
        return repository.findBySubjectTypeAndSubjectIdOrderByAcceptedAtDesc(subjectType, subjectId);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
