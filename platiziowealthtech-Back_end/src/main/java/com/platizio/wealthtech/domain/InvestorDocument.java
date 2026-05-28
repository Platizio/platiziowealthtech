package com.platizio.wealthtech.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

@Entity
@Table(
        name = "investor_documents",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_investor_documents_investor_type",
                columnNames = {"investor_id", "document_type"}
        )
)
public class InvestorDocument extends BaseEntity {

    @Column(nullable = false)
    private UUID investorId;

    @Column(nullable = false)
    private UUID distributorId;

    @Column(nullable = false)
    private UUID uploadedBy;

    @Column(nullable = false, length = 50)
    private String documentType;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private Long sizeBytes;

    @JsonIgnore
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    public UUID getInvestorId() {
        return investorId;
    }

    public void setInvestorId(UUID investorId) {
        this.investorId = investorId;
    }

    public UUID getDistributorId() {
        return distributorId;
    }

    public void setDistributorId(UUID distributorId) {
        this.distributorId = distributorId;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(UUID uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public String getDocumentType() {
        return documentType;
    }

    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }
}
