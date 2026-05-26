CREATE TABLE IF NOT EXISTS investor_documents (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    investor_id UUID NOT NULL,
    distributor_id UUID NOT NULL,
    uploaded_by UUID NOT NULL,
    document_type VARCHAR(50) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content BYTEA NOT NULL,
    CONSTRAINT fk_investor_documents_investor
        FOREIGN KEY (investor_id) REFERENCES investors(id) ON DELETE CASCADE,
    CONSTRAINT fk_investor_documents_distributor
        FOREIGN KEY (distributor_id) REFERENCES distributors(id),
    CONSTRAINT fk_investor_documents_uploaded_by
        FOREIGN KEY (uploaded_by) REFERENCES distributors(id),
    CONSTRAINT chk_investor_documents_document_type
        CHECK (document_type IN ('KYC', 'PAN', 'ADDRESS', 'SIGNATURE')),
    CONSTRAINT chk_investor_documents_size
        CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    CONSTRAINT uq_investor_documents_investor_type
        UNIQUE (investor_id, document_type)
);

CREATE INDEX IF NOT EXISTS idx_investor_documents_investor_id
    ON investor_documents (investor_id);

CREATE INDEX IF NOT EXISTS idx_investor_documents_distributor_id
    ON investor_documents (distributor_id);
