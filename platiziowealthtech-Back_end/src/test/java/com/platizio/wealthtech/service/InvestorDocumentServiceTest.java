package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorDocument;
import com.platizio.wealthtech.dto.InvestorDocumentUploadResponse;
import com.platizio.wealthtech.repository.InvestorDocumentRepository;
import com.platizio.wealthtech.repository.InvestorNomineeRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

class InvestorDocumentServiceTest {

    @Test
    void uploadDocumentStoresFileAndReturnsInvestorMetadata() throws Exception {
        UUID investorId = UUID.randomUUID();
        UUID distributorId = UUID.randomUUID();
        Investor investor = investor(investorId, distributorId);
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorDocumentRepository documentRepository = mock(InvestorDocumentRepository.class);
        InvestorNomineeRepository nomineeRepository = mock(InvestorNomineeRepository.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(distributorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));
        when(documentRepository.findByInvestorIdAndDocumentType(investorId, "PAN")).thenReturn(Optional.empty());
        when(documentRepository.save(any(InvestorDocument.class))).thenAnswer(invocation -> {
            InvestorDocument document = invocation.getArgument(0);
            ReflectionTestUtils.setField(document, "id", UUID.randomUUID());
            return document;
        });

        InvestorDocumentService service = new InvestorDocumentService(
                investorRepository,
                documentRepository,
                nomineeRepository,
                distributorService,
                auditService
        );

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pan-card.pdf",
                "application/pdf",
                "test-pdf".getBytes()
        );
        InvestorDocumentUploadResponse response = service.uploadDocument(investorId, "pan", file, distributorId);

        ArgumentCaptor<InvestorDocument> documentCaptor = ArgumentCaptor.forClass(InvestorDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        InvestorDocument saved = documentCaptor.getValue();
        assertThat(saved.getInvestorId()).isEqualTo(investorId);
        assertThat(saved.getDistributorId()).isEqualTo(distributorId);
        assertThat(saved.getUploadedBy()).isEqualTo(distributorId);
        assertThat(saved.getDocumentType()).isEqualTo("PAN");
        assertThat(saved.getFileName()).isEqualTo("pan-card.pdf");
        assertThat(saved.getContentType()).isEqualTo("application/pdf");
        assertThat(saved.getSizeBytes()).isEqualTo(file.getSize());
        assertThat(saved.getContent()).isEqualTo(file.getBytes());
        assertThat(response.investor()).isSameAs(investor);
        assertThat(response.document().documentType()).isEqualTo("PAN");
        assertThat(auditService.entityType).isEqualTo("INVESTOR_DOCUMENT");
        assertThat(auditService.actionType).isEqualTo("UPLOADED");
        assertThat(auditService.actorId).isEqualTo(distributorId);
        assertThat(auditService.detailsJson).contains("\"documentType\":\"PAN\"");
    }

    @Test
    void uploadDocumentRejectsAnotherDistributorInvestor() {
        UUID investorId = UUID.randomUUID();
        UUID investorDistributorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorDocumentRepository documentRepository = mock(InvestorDocumentRepository.class);
        InvestorNomineeRepository nomineeRepository = mock(InvestorNomineeRepository.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(actorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor(investorId, investorDistributorId)));

        InvestorDocumentService service = new InvestorDocumentService(
                investorRepository,
                documentRepository,
                nomineeRepository,
                distributorService,
                auditService
        );

        MockMultipartFile file = new MockMultipartFile("file", "kyc.pdf", "application/pdf", "test".getBytes());

        assertThatThrownBy(() -> service.uploadDocument(investorId, "KYC", file, actorId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Cannot upload documents for another distributor's investor");
        verify(documentRepository, never()).save(any());
    }

    @Test
    void listDocumentsReturnsMetadataWithoutBinaryContent() {
        UUID investorId = UUID.randomUUID();
        UUID distributorId = UUID.randomUUID();
        Investor investor = investor(investorId, distributorId);
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorDocumentRepository documentRepository = mock(InvestorDocumentRepository.class);
        InvestorNomineeRepository nomineeRepository = mock(InvestorNomineeRepository.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(distributorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor));

        InvestorDocument pan = new InvestorDocument();
        ReflectionTestUtils.setField(pan, "id", UUID.randomUUID());
        pan.setInvestorId(investorId);
        pan.setDistributorId(distributorId);
        pan.setUploadedBy(distributorId);
        pan.setDocumentType("PAN");
        pan.setFileName("pan-card.pdf");
        pan.setContentType("application/pdf");
        pan.setSizeBytes(1200L);
        pan.setContent("pdf-bytes".getBytes());
        when(documentRepository.findAllByInvestorIdOrderByDocumentTypeAsc(investorId)).thenReturn(List.of(pan));

        InvestorDocumentService service = new InvestorDocumentService(
                investorRepository,
                documentRepository,
                nomineeRepository,
                distributorService,
                auditService
        );

        var documents = service.listDocuments(investorId, distributorId);

        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().documentType()).isEqualTo("PAN");
        assertThat(documents.getFirst().fileName()).isEqualTo("pan-card.pdf");
    }

    @Test
    void uploadDocumentRejectsUnsupportedFileType() {
        UUID investorId = UUID.randomUUID();
        UUID distributorId = UUID.randomUUID();
        InvestorRepository investorRepository = mock(InvestorRepository.class);
        InvestorDocumentRepository documentRepository = mock(InvestorDocumentRepository.class);
        InvestorNomineeRepository nomineeRepository = mock(InvestorNomineeRepository.class);
        RecordingDistributorService distributorService = new RecordingDistributorService();
        RecordingAuditService auditService = new RecordingAuditService();
        distributorService.put(distributorId, DistributorRole.SUB_DISTRIBUTOR);
        when(investorRepository.findById(investorId)).thenReturn(Optional.of(investor(investorId, distributorId)));

        InvestorDocumentService service = new InvestorDocumentService(
                investorRepository,
                documentRepository,
                nomineeRepository,
                distributorService,
                auditService
        );

        MockMultipartFile file = new MockMultipartFile("file", "malware.exe", "application/octet-stream", "test".getBytes());

        assertThatThrownBy(() -> service.uploadDocument(investorId, "KYC", file, distributorId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only PDF, JPG, and PNG files are allowed");
        verify(documentRepository, never()).save(any());
    }

    private Investor investor(UUID investorId, UUID distributorId) {
        Investor investor = new Investor();
        ReflectionTestUtils.setField(investor, "id", investorId);
        investor.setDistributorId(distributorId);
        investor.setFullName("Jane Investor");
        investor.setMobileNumber("9876543210");
        investor.setEmail("jane@example.com");
        investor.setPan("ABCDE1234F");
        investor.setHouseholdId(investorId);
        return investor;
    }

    private Distributor distributor(DistributorRole role) {
        Distributor distributor = new Distributor();
        distributor.setRole(role);
        return distributor;
    }

    private static class RecordingDistributorService extends DistributorService {
        private final Map<UUID, Distributor> distributors = new HashMap<>();

        RecordingDistributorService() {
            super(null, null, null);
        }

        void put(UUID distributorId, DistributorRole role) {
            distributors.put(distributorId, distributor(role));
        }

        @Override
        public Distributor getDistributor(UUID distributorId) {
            return Optional.ofNullable(distributors.get(distributorId))
                    .orElseThrow(() -> new AssertionError("Unexpected distributor lookup: " + distributorId));
        }

        private static Distributor distributor(DistributorRole role) {
            Distributor distributor = new Distributor();
            distributor.setRole(role);
            return distributor;
        }
    }

    private static class RecordingAuditService extends AuditService {
        private String entityType;
        private String actionType;
        private UUID actorId;
        private String detailsJson;

        RecordingAuditService() {
            super(null);
        }

        @Override
        public void log(String entityType, UUID entityId, String actionType, UUID actorId, String detailsJson) {
            this.entityType = entityType;
            this.actionType = actionType;
            this.actorId = actorId;
            this.detailsJson = detailsJson;
        }
    }
}
