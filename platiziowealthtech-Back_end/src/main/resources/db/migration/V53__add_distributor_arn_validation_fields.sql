-- Know-Your-Distributor (ARN/KYD) validation outcome captured at signup from the configured
-- ARN-validation provider. Distinct from distributors.status (account approval lifecycle).
ALTER TABLE distributors
    ADD COLUMN IF NOT EXISTS arn_validation_status varchar(50),
    ADD COLUMN IF NOT EXISTS arn_validated_at timestamptz,
    ADD COLUMN IF NOT EXISTS arn_holder_name varchar(255),
    ADD COLUMN IF NOT EXISTS firm_name varchar(255),
    ADD COLUMN IF NOT EXISTS kyd_status varchar(50),
    ADD COLUMN IF NOT EXISTS arn_validation_source varchar(100);
