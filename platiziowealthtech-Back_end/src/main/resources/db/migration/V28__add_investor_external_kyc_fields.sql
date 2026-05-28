ALTER TABLE investors ADD COLUMN IF NOT EXISTS external_kyc_check_id VARCHAR(100);
ALTER TABLE investors ADD COLUMN IF NOT EXISTS external_kyc_request_id VARCHAR(100);
ALTER TABLE investors ADD COLUMN IF NOT EXISTS external_kyc_status VARCHAR(50);
ALTER TABLE investors ADD COLUMN IF NOT EXISTS external_kyc_payload_json TEXT;

CREATE INDEX IF NOT EXISTS idx_investors_external_kyc_check_id
    ON investors (external_kyc_check_id);

CREATE INDEX IF NOT EXISTS idx_investors_external_kyc_request_id
    ON investors (external_kyc_request_id);
