ALTER TABLE investors
    ADD COLUMN IF NOT EXISTS external_esign_id varchar(100),
    ADD COLUMN IF NOT EXISTS esign_status varchar(50),
    ADD COLUMN IF NOT EXISTS aadhaar_proofs_attached boolean NOT NULL DEFAULT false;
