ALTER TABLE investors
    ADD COLUMN IF NOT EXISTS external_identity_document_id varchar(100),
    ADD COLUMN IF NOT EXISTS aadhaar_fetch_status varchar(50),
    ADD COLUMN IF NOT EXISTS aadhaar_fetch_reason varchar(500);
