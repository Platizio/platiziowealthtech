package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorDocument;
import com.platizio.wealthtech.domain.InvestorNominee;
import com.platizio.wealthtech.dto.InvestorDocumentUploadResponse;
import com.platizio.wealthtech.dto.UploadedInvestorDocumentResponse;
import com.platizio.wealthtech.repository.InvestorDocumentRepository;
import com.platizio.wealthtech.repository.InvestorNomineeRepository;
import com.platizio.wealthtech.repository.InvestorRepository;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class InvestorDocumentService {

    private static final long MAX_DOCUMENT_SIZE_BYTES = 5L * 1024L * 1024L;
    /** Investor-level document types the DISTRIBUTOR upload path may write. */
    private static final Set<String> ALLOWED_DOCUMENT_TYPES = Set.of("KYC", "PAN", "ADDRESS", "SIGNATURE");
    /** Nominee identity document, uploaded by the investor from their own flow. */
    public static final String DOCUMENT_TYPE_NOMINEE_ID = "NOMINEE_ID";
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png");

    private final InvestorRepository investorRepository;
    private final InvestorDocumentRepository investorDocumentRepository;
    private final InvestorNomineeRepository investorNomineeRepository;
    private final DistributorService distributorService;
    private final AuditService auditService;

    public InvestorDocumentService(
            InvestorRepository investorRepository,
            InvestorDocumentRepository investorDocumentRepository,
            InvestorNomineeRepository investorNomineeRepository,
            DistributorService distributorService,
            AuditService auditService
    ) {
        this.investorRepository = investorRepository;
        this.investorDocumentRepository = investorDocumentRepository;
        this.investorNomineeRepository = investorNomineeRepository;
        this.distributorService = distributorService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<UploadedInvestorDocumentResponse> listDocuments(UUID investorId, UUID actorId) {
        Investor investor = investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
        assertCanModifyInvestor(investor, actorId);
        return investorDocumentRepository.findAllByInvestorIdOrderByDocumentTypeAsc(investorId).stream()
                .map(UploadedInvestorDocumentResponse::from)
                .toList();
    }

    @Transactional
    public InvestorDocumentUploadResponse uploadDocument(
            UUID investorId,
            String rawDocumentType,
            MultipartFile file,
            UUID actorId
    ) {
        Investor investor = investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
        assertCanModifyInvestor(investor, actorId);

        String documentType = normalizeDocumentType(rawDocumentType);
        validateFile(file);

        InvestorDocument document = investorDocumentRepository
                .findByInvestorIdAndDocumentType(investorId, documentType)
                .orElseGet(InvestorDocument::new);
        document.setInvestorId(investorId);
        document.setDistributorId(investor.getDistributorId());
        document.setUploadedBy(actorId);
        document.setDocumentType(documentType);
        document.setFileName(cleanFileName(file.getOriginalFilename()));
        document.setContentType(normalizeContentType(file.getContentType()));
        document.setSizeBytes(file.getSize());
        document.setContent(readBytes(file));

        InvestorDocument savedDocument = investorDocumentRepository.save(document);
        auditService.log(
                "INVESTOR_DOCUMENT",
                savedDocument.getId(),
                "UPLOADED",
                actorId,
                "{\"investorId\":\"" + investorId + "\",\"documentType\":\"" + documentType + "\",\"sizeBytes\":" + file.getSize() + "}"
        );

        return new InvestorDocumentUploadResponse(
                investor,
                UploadedInvestorDocumentResponse.from(savedDocument)
        );
    }

    /**
     * Uploads (or replaces) the identity document for one nominee, acting AS THE
     * INVESTOR: the caller (investor portal controller) resolved {@code investorId}
     * from the authenticated session, so the session itself proves ownership and no
     * distributor-ownership check is performed (mirrors the {@code *AsInvestor}
     * pattern in NomineeService / contact verification). The nominee must belong to
     * the investor.
     */
    @Transactional
    public UploadedInvestorDocumentResponse uploadNomineeDocumentAsInvestor(
            UUID investorId,
            UUID nomineeId,
            MultipartFile file,
            UUID investorAccountId
    ) {
        Investor investor = investorRepository.findById(investorId)
                .orElseThrow(() -> new EntityNotFoundException("Investor not found"));
        InvestorNominee nominee = investorNomineeRepository.findById(nomineeId)
                .filter(n -> investorId.equals(n.getInvestorId()))
                .orElseThrow(() -> new EntityNotFoundException("Nominee not found"));
        validateFile(file);

        InvestorDocument document = investorDocumentRepository
                .findByInvestorIdAndNomineeIdAndDocumentType(investorId, nomineeId, DOCUMENT_TYPE_NOMINEE_ID)
                .orElseGet(InvestorDocument::new);
        document.setInvestorId(investorId);
        document.setNomineeId(nominee.getId());
        document.setDistributorId(investor.getDistributorId());
        document.setUploadedBy(investorAccountId);
        document.setDocumentType(DOCUMENT_TYPE_NOMINEE_ID);
        document.setFileName(cleanFileName(file.getOriginalFilename()));
        document.setContentType(normalizeContentType(file.getContentType()));
        document.setSizeBytes(file.getSize());
        document.setContent(readBytes(file));

        InvestorDocument savedDocument = investorDocumentRepository.save(document);
        auditService.log(
                "INVESTOR_DOCUMENT",
                savedDocument.getId(),
                "UPLOADED",
                investorAccountId,
                "{\"investorId\":\"" + investorId + "\",\"nomineeId\":\"" + nomineeId
                        + "\",\"documentType\":\"" + DOCUMENT_TYPE_NOMINEE_ID + "\",\"sizeBytes\":" + file.getSize() + "}"
        );
        return UploadedInvestorDocumentResponse.from(savedDocument);
    }

    /** Ids of this investor's nominees that already have an identity document on file. */
    @Transactional(readOnly = true)
    public Set<UUID> nomineeIdsWithDocuments(UUID investorId) {
        return investorDocumentRepository
                .findAllByInvestorIdAndDocumentType(investorId, DOCUMENT_TYPE_NOMINEE_ID).stream()
                .map(InvestorDocument::getNomineeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private void assertCanModifyInvestor(Investor investor, UUID actorId) {
        if (actorId == null) {
            throw new AccessDeniedException("Authenticated distributor principal is required");
        }

        Distributor requester = distributorService.getDistributor(actorId);
        if (requester.getRole() == DistributorRole.ADMIN || actorId.equals(investor.getDistributorId())) {
            return;
        }

        if (requester.getRole() == DistributorRole.MASTER_DISTRIBUTOR) {
            Distributor investorDistributor = distributorService.getDistributor(investor.getDistributorId());
            if (actorId.equals(investorDistributor.getMasterDistributorId())) {
                return;
            }
        }

        throw new AccessDeniedException("Cannot upload documents for another distributor's investor");
    }

    private String normalizeDocumentType(String rawDocumentType) {
        if (rawDocumentType == null || rawDocumentType.isBlank()) {
            throw new IllegalArgumentException("documentType is required");
        }
        String documentType = rawDocumentType.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!ALLOWED_DOCUMENT_TYPES.contains(documentType)) {
            throw new IllegalArgumentException("Unsupported documentType: " + rawDocumentType);
        }
        return documentType;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        if (file.getSize() > MAX_DOCUMENT_SIZE_BYTES) {
            throw new IllegalArgumentException("File size must be 5 MB or less");
        }

        String contentType = normalizeContentType(file.getContentType());
        String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
        String normalizedExtension = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(contentType) && !ALLOWED_EXTENSIONS.contains(normalizedExtension)) {
            throw new IllegalArgumentException("Only PDF, JPG, and PNG files are allowed");
        }
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }

    private String cleanFileName(String originalFilename) {
        String fileName = StringUtils.getFilename(originalFilename);
        if (fileName == null || fileName.isBlank()) {
            return "document";
        }
        return fileName.replace("\u0000", "");
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read uploaded file");
        }
    }
}
