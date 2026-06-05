-- =============================================================================
-- Consolidated demo/seed data (squash of legacy V1/V5/V9/V10/V22/V23 seeds).
-- Values already reflect the later category (V8), returns-metadata (V9) and
-- password-hash (V12/V22) updates, so this single file produces the final state.
-- All inserts are idempotent so re-running against a populated DB is a no-op.
-- =============================================================================

-- Distributors ----------------------------------------------------------------
-- Demo login shown in LoginPage.tsx: alice@example.com / Platizio@2024
insert into distributors (
    id, created_at, updated_at, full_name, mobile_number, email, arn_number,
    nism_certificate_number, nism_expiry_date, arn_expiry_date, e_uin_number,
    status, kyc_status, bank_account_number, bank_ifsc, bank_account_holder_name,
    profile_completion_percent, internal_rm, role, password_hash
) values (
    'd9b2d63d-a233-4123-8478-3b169d988b48', now(), now(), 'Master Distributor',
    '9876543210', 'alice@example.com', 'ARN-123456', 'NISM-987', '2028-12-31',
    '2025-12-31', 'E-UIN-888', 'APPROVED', 'COMPLETED', '100020003000',
    'HDFC0000001', 'Alice', 100, false, 'MASTER_DISTRIBUTOR',
    '$2a$10$Sx5yk6Ww1cJ2OlRKx4TsGeubh4LN3MvNxjPlfiiCoqvSCpzR0Rh8.'
) on conflict (email) do nothing;

-- Local/demo login for quick testing: a@a.com / Ok@123456
insert into distributors (
    id, created_at, updated_at, full_name, mobile_number, email, arn_number,
    nism_certificate_number, nism_expiry_date, arn_expiry_date, e_uin_number,
    status, kyc_status, bank_account_number, bank_ifsc, bank_account_holder_name,
    profile_completion_percent, internal_rm, role, password_hash
) values (
    '4317cfd2-a41f-4320-a5dc-26835c7210ac', now(), now(), 'Test Distributor',
    '9000000001', 'a@a.com', 'ARN-TEST-001', 'NISM-TEST-001', '2028-12-31',
    '2028-12-31', 'E-UIN-TEST-001', 'APPROVED', 'COMPLETED', '100020003001',
    'HDFC0000001', 'Test Distributor', 100, false, 'MASTER_DISTRIBUTOR',
    '$2a$10$XQHHUBoV886OteiR8ZpmE.QQL8PCxVvAqPlnivkHSQ1LmuXZFhN5G'
) on conflict (email) do nothing;

-- Investor (household_id self-references the investor; relationship_type SELF) --
insert into investors (
    id, created_at, updated_at, distributor_id, full_name, mobile_number, email,
    pan, date_of_birth, address_line1, city, state, postal_code, investor_status,
    kyc_status, bank_verification_status, risk_profile, cybrilla_investor_id,
    relationship_type, household_id
) values (
    'b75fef2e-7440-42f0-9ef2-5b9db054e526', now(), now(),
    'd9b2d63d-a233-4123-8478-3b169d988b48', 'Bob Investor', '9988776655',
    'bob@example.com', 'ABCDE1234F', '1990-01-01', '123 Main St', 'Mumbai',
    'Maharashtra', '400001', 'ACTIVE', 'COMPLETED', 'VERIFIED', 'MODERATE',
    'CYB-INV-1', 'SELF', 'b75fef2e-7440-42f0-9ef2-5b9db054e526'
) on conflict (pan) do nothing;

-- Investor bank account -------------------------------------------------------
insert into investor_bank_accounts (
    id, created_at, updated_at, investor_id, account_holder_name, account_number,
    ifsc_code, bank_name, branch_name, verification_status
) values (
    'c34b1793-1b91-4df2-8c44-d8bc289b535d', now(), now(),
    'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'Bob', '555566667777',
    'ICIC0000001', 'ICICI Bank', 'Main Branch', 'VERIFIED'
) on conflict (id) do nothing;

-- Product schemes (final categories + returns metadata) -----------------------
insert into product_schemes (id, created_at, updated_at, scheme_name, amc_name, category, external_scheme_code, external_isin, product_type, active, metadata_json)
values
('a123fef2-7440-42f0-9ef2-5b9db054a123', now(), now(), 'Super Growth Fund', 'Super AMC', 'OTHER', 'MF-SG-100', 'INF123456789', 'MUTUAL_FUND', true, '{"returns":{"daily":0.8,"ytd":12.4,"1y":18.5,"5y":85.2}}'),
('a222fef2-7440-42f0-9ef2-5b9db054a222', now(), now(), 'Balanced Advantage Fund', 'Platizio AMC', 'MF', 'MF-BA-200', 'INF000000002', 'MUTUAL_FUND', true, null),
('a333fef2-7440-42f0-9ef2-5b9db054a333', now(), now(), 'Social Venture Fund', 'Impact AMC', 'SIF', 'SIF-SV-300', 'INF000000003', 'SIF', true, null)
on conflict (external_scheme_code) do nothing;

insert into product_schemes (id, created_at, updated_at, scheme_name, amc_name, category, external_scheme_code, external_isin, product_type, active, metadata_json)
values
(gen_random_uuid(), now(), now(), 'Balanced Mutual Fund', 'Platizio Assets', 'MF', 'MF-201', 'INF002', 'MUTUAL_FUND', true, '{"returns":{"daily":0.2,"ytd":8.1,"1y":12.4,"5y":55.8}}'),
(gen_random_uuid(), now(), now(), 'Social Impact Fund', 'Impact Capital', 'SIF', 'SIF-301', 'INF003', 'SIF', true, '{"returns":{"daily":-0.1,"ytd":5.4,"1y":8.2,"5y":32.1}}')
on conflict (external_scheme_code) do nothing;

-- Transaction orders ----------------------------------------------------------
insert into transaction_orders (id, created_at, updated_at, investor_id, distributor_id, product_scheme_id, transaction_type, order_status, amount, units, payment_mode)
values ('e012fe1c-7440-42f0-9ef2-5b9db054e012', now(), now(), 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'a123fef2-7440-42f0-9ef2-5b9db054a123', 'LUMPSUM_PURCHASE', 'COMPLETED', 10000.00, 100.0000, 'NET_BANKING')
on conflict (id) do nothing;

insert into transaction_orders (id, created_at, updated_at, investor_id, distributor_id, product_scheme_id, transaction_type, order_status, amount, units, payment_mode, mandate_mode, product_category)
values
('e111fe1c-7440-42f0-9ef2-5b9db054e111', now(), now(), 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'a222fef2-7440-42f0-9ef2-5b9db054a222', 'SIP', 'COMPLETED', 5000.00, 50.0000, 'NET_BANKING', 'E-Mandate', 'MF'),
('e222fe1c-7440-42f0-9ef2-5b9db054e222', now(), now(), 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'a333fef2-7440-42f0-9ef2-5b9db054a333', 'SIP', 'COMPLETED', 25000.00, 250.0000, 'NACH', 'Physical Mandate', 'SIF'),
('e333fe1c-7440-42f0-9ef2-5b9db054e333', now(), now(), 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'a222fef2-7440-42f0-9ef2-5b9db054a222', 'SIP', 'FAILED', 10000.00, 0, 'NET_BANKING', 'E-Mandate', 'MF'),
('e444fe1c-7440-42f0-9ef2-5b9db054e444', now(), now(), 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'a333fef2-7440-42f0-9ef2-5b9db054a333', 'SIP', 'DRAFT', 15000.00, 0, 'NET_BANKING', 'E-Mandate', 'SIF')
on conflict (id) do nothing;

-- Redemption record -----------------------------------------------------------
insert into redemption_records (id, created_at, updated_at, order_id, investor_id, redemption_status, units, amount)
values ('f123fe1c-7440-42f0-9ef2-5b9db0123456', now(), now(), 'e012fe1c-7440-42f0-9ef2-5b9db054e012', 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'PENDING', 50.0000, 5000.00)
on conflict (id) do nothing;

-- Notification ----------------------------------------------------------------
insert into notifications (id, created_at, updated_at, distributor_id, investor_id, type, title, message, read_flag)
values ('8fa1ae9b-0000-4b2a-8cfa-5b9d12341234', now(), now(), 'd9b2d63d-a233-4123-8478-3b169d988b48', 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'ORDER_STATUS', 'Order Completed', 'Your purchase order is successful.', false)
on conflict (id) do nothing;

-- Lead + interaction ----------------------------------------------------------
insert into investor_leads (id, created_at, updated_at, prospect_name, mobile_number, email, city, source, status, assigned_distributor_id)
values ('ca123eb4-0000-4123-85af-bbbbccccdddd', now(), now(), 'Charlie Prospect', '9999999999', 'charlie@example.com', 'Delhi', 'WEBSITE', 'NEW', 'd9b2d63d-a233-4123-8478-3b169d988b48')
on conflict (id) do nothing;

insert into lead_interactions (id, created_at, updated_at, lead_id, distributor_id, comment_text, interaction_type)
values ('11112222-3333-4444-5555-666677778888', now(), now(), 'ca123eb4-0000-4123-85af-bbbbccccdddd', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'Called customer but no answer.', 'CALL')
on conflict (id) do nothing;

-- Audit event -----------------------------------------------------------------
insert into audit_events (id, created_at, updated_at, entity_type, entity_id, action_type, actor_id, details_json)
values ('aaaaabbb-cccc-dddd-eeee-ffff00001111', now(), now(), 'DISTRIBUTOR', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'CREATED', 'd9b2d63d-a233-4123-8478-3b169d988b48', '{"reason": "signup"}')
on conflict (id) do nothing;
