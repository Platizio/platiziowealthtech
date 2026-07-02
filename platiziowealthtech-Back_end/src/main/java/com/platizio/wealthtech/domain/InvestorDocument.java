package com.platizio.wealthtech.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Uniqueness is enforced by two PARTIAL unique indexes (V73), which JPA cannot
 * express: investor-level docs (nominee_id null) are one-per-(investor, type),
 * and nominee docs are one-per-(investor, nominee, type).
 */
@Entity
@Table(name = "investor_documents")
public class InvestorDocument extends BaseEntity {

    @Column(nullable = false)
    private UUID investorId;

    /** Nullable since V73: unlinked investors (pending distributor) may upload nominee docs. */
    private UUID distributorId;

    /** A distributor id OR an investor account id (nominee docs); no FK since V73. */
    @Column(nullable = false)
    private UUID uploadedBy;

    /** Set only for nominee identity documents; null for investor-level documents. */
    private UUID nomineeId;

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

    public UUID getNomineeId() {
        return nomineeId;
    }

    public void setNomineeId(UUID nomineeId) {
        this.nomineeId = nomineeId;
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
