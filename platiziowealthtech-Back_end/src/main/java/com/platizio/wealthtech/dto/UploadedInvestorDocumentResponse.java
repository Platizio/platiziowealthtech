package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.InvestorDocument;
import java.time.OffsetDateTime;
import java.util.UUID;

public record UploadedInvestorDocumentResponse(
        UUID id,
        UUID investorId,
        String documentType,
        String fileName,
        String contentType,
        long sizeBytes,
        OffsetDateTime uploadedAt
) {

    public static UploadedInvestorDocumentResponse from(InvestorDocument document) {
        Long sizeBytes = document.getSizeBytes();
        return new UploadedInvestorDocumentResponse(
                document.getId(),
                document.getInvestorId(),
                document.getDocumentType(),
                document.getFileName(),
                document.getContentType(),
                sizeBytes == null ? 0L : sizeBytes,
                document.getUpdatedAt()
        );
    }
}
