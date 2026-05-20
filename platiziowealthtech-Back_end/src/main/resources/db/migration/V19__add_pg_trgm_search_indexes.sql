CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_investor_full_name_trgm
    ON investors USING GIN (full_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_investor_email_trgm
    ON investors USING GIN (email gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_investor_mobile_number_trgm
    ON investors USING GIN (mobile_number gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_investor_pan_trgm
    ON investors USING GIN (pan gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_distributor_full_name_trgm
    ON distributors USING GIN (full_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_distributor_email_trgm
    ON distributors USING GIN (email gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_distributor_mobile_number_trgm
    ON distributors USING GIN (mobile_number gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_distributor_arn_number_trgm
    ON distributors USING GIN (arn_number gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_distributor_e_uin_number_trgm
    ON distributors USING GIN (e_uin_number gin_trgm_ops);
