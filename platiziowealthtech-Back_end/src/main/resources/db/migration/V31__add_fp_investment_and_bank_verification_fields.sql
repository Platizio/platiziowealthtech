ALTER TABLE investors
    ADD COLUMN IF NOT EXISTS external_mf_investment_account_id VARCHAR(100);

ALTER TABLE investor_bank_accounts
    ADD COLUMN IF NOT EXISTS cybrilla_bank_verification_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS cybrilla_bank_verification_status VARCHAR(50),
    ADD COLUMN IF NOT EXISTS cybrilla_bank_verification_confidence VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_investors_external_mf_investment_account_id
    ON investors (external_mf_investment_account_id);

CREATE INDEX IF NOT EXISTS idx_investor_bank_accounts_cybrilla_bank_verification_id
    ON investor_bank_accounts (cybrilla_bank_verification_id);
