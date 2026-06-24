package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.ConsentRecord;
import com.platizio.wealthtech.repository.ConsentRecordRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records immutable consent evidence (SRS FR-CNS-002): the exact rendered consent
 * text + its SHA-256 + version + actor context. Append-only — every acceptance is
 * a new row, never an update.
 */
@Service
public class ConsentRecordService {

    public static final String SUBJECT_INVESTOR = "INVESTOR";

    private final ConsentRecordRepository repository;

    public ConsentRecordService(ConsentRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ConsentRecord record(
            String subjectType, UUID subjectId, String consentKey, String templateVersion,
            String renderedText, String ipAddress, String userAgent) {
        ConsentRecord record = new ConsentRecord();
        record.setSubjectType(subjectType);
        record.setSubjectId(subjectId);
        record.setConsentKey(consentKey);
        record.setTemplateVersion(templateVersion);
        record.setRenderedText(renderedText);
        record.setContentSha256(sha256(renderedText));
        record.setIpAddress(truncate(ipAddress, 64));
        record.setUserAgent(truncate(userAgent, 512));
        record.setAcceptedAt(OffsetDateTime.now());
        return repository.save(record);
    }

    @Transactional(readOnly = true)
    public List<ConsentRecord> list(String subjectType, UUID subjectId) {
        return repository.findBySubjectTypeAndSubjectIdOrderByAcceptedAtDesc(subjectType, subjectId);
    }

    /** Lowercase hex SHA-256 of the rendered text (used to prove what was shown). */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
