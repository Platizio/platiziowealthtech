package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.TermsAcceptance;
import com.platizio.wealthtech.repository.TermsAcceptanceRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TermsAcceptanceServiceTest {

    @Mock private TermsAcceptanceRepository repository;
    @Mock private AuditService auditService;

    private TermsAcceptanceService service;

    private final UUID investorId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TermsAcceptanceService(repository, auditService);
        lenient().when(repository.save(any(TermsAcceptance.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void recordPersistsAcceptanceWithVersionTimestampAndSubjectAndAudits() {
        TermsAcceptance saved = service.record(
                "INVESTOR", investorId, "investor_tnc", "v1.0", "203.0.113.7", "Mozilla/5.0", actorId);

        ArgumentCaptor<TermsAcceptance> captor = ArgumentCaptor.forClass(TermsAcceptance.class);
        verify(repository).save(captor.capture());
        TermsAcceptance row = captor.getValue();
        assertThat(row.getSubjectType()).isEqualTo("INVESTOR");
        assertThat(row.getSubjectId()).isEqualTo(investorId);
        assertThat(row.getDocumentKey()).isEqualTo("investor_tnc");
        assertThat(row.getVersion()).isEqualTo("v1.0");
        assertThat(row.getAcceptedAt()).isNotNull();
        assertThat(row.getIpAddress()).isEqualTo("203.0.113.7");
        assertThat(row.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved).isSameAs(row);
        verify(auditService).log(eq("INVESTOR"), eq(investorId), eq("TERMS_ACCEPTED"), eq(actorId), any());
    }

    @Test
    void recordTruncatesAnOverlongUserAgent() {
        String longUa = "x".repeat(900);

        service.record("INVESTOR", investorId, "investor_tnc", "v1.0", null, longUa, actorId);

        ArgumentCaptor<TermsAcceptance> captor = ArgumentCaptor.forClass(TermsAcceptance.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserAgent()).hasSize(512);
        assertThat(captor.getValue().getIpAddress()).isNull();
    }

    @Test
    void getLatestDelegatesToRepository() {
        TermsAcceptance existing = new TermsAcceptance();
        when(repository.findTopBySubjectTypeAndSubjectIdAndDocumentKeyOrderByAcceptedAtDesc(
                "INVESTOR", investorId, "investor_tnc")).thenReturn(Optional.of(existing));

        Optional<TermsAcceptance> latest = service.getLatest("INVESTOR", investorId, "investor_tnc");

        assertThat(latest).containsSame(existing);
    }
}
