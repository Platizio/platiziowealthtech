package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.ConsentRecord;
import com.platizio.wealthtech.repository.ConsentRecordRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsentRecordServiceTest {

    @Mock private ConsentRecordRepository repository;
    private ConsentRecordService service;
    private final UUID subjectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConsentRecordService(repository);
        lenient().when(repository.save(any(ConsentRecord.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void recordPersistsExactTextHashVersionAndContext() {
        String text = "I confirm this email address belongs to me.";

        ConsentRecord saved = service.record(
                "INVESTOR", subjectId, "contact_ownership_email", "v1.0", text, "203.0.113.7", "Mozilla/5.0");

        ArgumentCaptor<ConsentRecord> captor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(repository).save(captor.capture());
        ConsentRecord row = captor.getValue();
        assertThat(row.getSubjectType()).isEqualTo("INVESTOR");
        assertThat(row.getSubjectId()).isEqualTo(subjectId);
        assertThat(row.getConsentKey()).isEqualTo("contact_ownership_email");
        assertThat(row.getTemplateVersion()).isEqualTo("v1.0");
        assertThat(row.getRenderedText()).isEqualTo(text);
        assertThat(row.getContentSha256()).matches("^[0-9a-f]{64}$");
        assertThat(row.getContentSha256()).isEqualTo(ConsentRecordService.sha256(text));
        assertThat(row.getAcceptedAt()).isNotNull();
        assertThat(saved).isSameAs(row);
    }

    @Test
    void hashIsStableForSameTextAndDiffersForDifferentText() {
        assertThat(ConsentRecordService.sha256("abc")).isEqualTo(ConsentRecordService.sha256("abc"));
        assertThat(ConsentRecordService.sha256("abc")).isNotEqualTo(ConsentRecordService.sha256("abcd"));
    }

    @Test
    void recordTruncatesOverlongUserAgent() {
        service.record("INVESTOR", subjectId, "investor_tnc", "v1.0", "T&C text", null, "x".repeat(900));

        ArgumentCaptor<ConsentRecord> captor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserAgent()).hasSize(512);
        assertThat(captor.getValue().getIpAddress()).isNull();
    }
}
