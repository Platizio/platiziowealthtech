-- Local/demo login used for quick frontend testing.
--   email: a@a.com
--   password: Ok@123456
INSERT INTO distributors (
    id,
    created_at,
    updated_at,
    full_name,
    mobile_number,
    email,
    arn_number,
    arn_expiry_date,
    nism_certificate_number,
    nism_expiry_date,
    e_uin_number,
    status,
    kyc_status,
    bank_account_number,
    bank_ifsc,
    bank_account_holder_name,
    profile_completion_percent,
    internal_rm,
    role,
    password_hash
)
VALUES (
    '4317cfd2-a41f-4320-a5dc-26835c7210ac',
    now(),
    now(),
    'Test Distributor',
    '9000000001',
    'a@a.com',
    'ARN-TEST-001',
    '2028-12-31',
    'NISM-TEST-001',
    '2028-12-31',
    'E-UIN-TEST-001',
    'APPROVED',
    'COMPLETED',
    '100020003001',
    'HDFC0000001',
    'Test Distributor',
    100,
    false,
    'MASTER_DISTRIBUTOR',
    '$2a$10$XQHHUBoV886OteiR8ZpmE.QQL8PCxVvAqPlnivkHSQ1LmuXZFhN5G'
)
ON CONFLICT (email) DO UPDATE
SET updated_at = now(),
    status = 'APPROVED',
    kyc_status = 'COMPLETED',
    role = 'MASTER_DISTRIBUTOR',
    password_hash = EXCLUDED.password_hash;
