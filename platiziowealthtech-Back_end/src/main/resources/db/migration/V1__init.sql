-- Triggering clean and rebuild
create table if not exists distributors (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    full_name varchar(255) not null,
    mobile_number varchar(50) not null unique,
    email varchar(255) not null unique,
    arn_number varchar(100) not null unique,
    nism_certificate_number varchar(100) not null,
    nism_expiry_date date,
    e_uin_number varchar(100),
    status varchar(50) not null,
    kyc_status varchar(50) not null,
    bank_account_number varchar(100),
    bank_ifsc varchar(50),
    bank_account_holder_name varchar(255),
    profile_completion_percent integer,
    internal_rm boolean
);

create table if not exists investors (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    distributor_id uuid not null,
    full_name varchar(255) not null,
    mobile_number varchar(50) not null,
    email varchar(255) not null,
    pan varchar(20) not null unique,
    date_of_birth date,
    address_line1 varchar(255),
    address_line2 varchar(255),
    city varchar(100),
    state varchar(100),
    postal_code varchar(20),
    investor_status varchar(50) not null,
    kyc_status varchar(50) not null,
    bank_verification_status varchar(50) not null,
    risk_profile varchar(50) not null,
    cybrilla_investor_id varchar(100),
    onboarding_notes varchar(1000)
);

create table if not exists investor_bank_accounts (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    investor_id uuid not null,
    account_holder_name varchar(255) not null,
    account_number varchar(100) not null,
    ifsc_code varchar(50) not null,
    bank_name varchar(255),
    branch_name varchar(255),
    verification_status varchar(50) not null,
    cybrilla_bank_id varchar(100)
);

create table if not exists product_schemes (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    scheme_name varchar(255) not null,
    amc_name varchar(255) not null,
    category varchar(255) not null,
    external_scheme_code varchar(255) not null unique,
    external_isin varchar(100),
    product_type varchar(100),
    active boolean,
    metadata_json text
);

create table if not exists transaction_orders (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    investor_id uuid not null,
    distributor_id uuid not null,
    product_scheme_id uuid not null,
    transaction_type varchar(50) not null,
    order_status varchar(50) not null,
    amount numeric(18,2),
    units numeric(18,4),
    payment_mode varchar(50),
    mandate_mode varchar(50),
    external_order_id varchar(100),
    failure_reason varchar(1000),
    investor_action_url varchar(1000)
);

create table if not exists redemption_records (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    order_id uuid not null,
    investor_id uuid not null,
    redemption_status varchar(50) not null,
    units numeric(18,4),
    amount numeric(18,2),
    external_redemption_id varchar(100),
    bank_credit_reference varchar(100),
    failure_reason varchar(1000)
);

create table if not exists notifications (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    distributor_id uuid not null,
    investor_id uuid,
    type varchar(100) not null,
    title varchar(250) not null,
    message varchar(2000) not null,
    read_flag boolean not null
);

create table if not exists investor_leads (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    prospect_name varchar(255) not null,
    mobile_number varchar(50) not null,
    email varchar(255),
    city varchar(100),
    state_name varchar(100),
    source varchar(50) not null,
    status varchar(50) not null,
    assigned_distributor_id uuid,
    converted_investor_id uuid,
    notes varchar(1000)
);

create table if not exists lead_interactions (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    lead_id uuid not null,
    distributor_id uuid not null,
    comment_text varchar(1000) not null,
    interaction_type varchar(100)
);

create table if not exists audit_events (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    entity_type varchar(100) not null,
    entity_id uuid not null,
    action_type varchar(100) not null,
    actor_id uuid not null,
    details_json text
);

CREATE TABLE if not exists kyc_records (
    id BIGSERIAL PRIMARY KEY,
    investor_id UUID REFERENCES investors(id),
    external_kyc_check_id VARCHAR(100),
    external_kyc_request_id VARCHAR(100),
    status VARCHAR(30),
    remarks TEXT,
    raw_response_json JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE if not exists documents (
    id BIGSERIAL PRIMARY KEY,
    investor_id UUID REFERENCES investors(id),
    document_type VARCHAR(50),
    external_file_id VARCHAR(100),
    file_name VARCHAR(255),
    file_url TEXT,
    status VARCHAR(30),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE if not exists bank_accounts (
    id BIGSERIAL PRIMARY KEY,
    investor_id UUID REFERENCES investors(id),
    external_bank_account_id VARCHAR(100),
    account_holder_name VARCHAR(150),
    account_number VARCHAR(50),
    ifsc_code VARCHAR(20),
    bank_name VARCHAR(100),
    account_type VARCHAR(30),
    is_primary BOOLEAN DEFAULT FALSE,
    verification_status VARCHAR(30),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE if not exists fund_schemes (
    id BIGSERIAL PRIMARY KEY,
    external_scheme_id VARCHAR(100),
    isin VARCHAR(30) UNIQUE,
    scheme_name VARCHAR(255),
    amc_name VARCHAR(150),
    plan_type VARCHAR(30),
    option_type VARCHAR(30),
    purchase_allowed BOOLEAN DEFAULT TRUE,
    redemption_allowed BOOLEAN DEFAULT TRUE,
    sip_allowed BOOLEAN DEFAULT FALSE,
    min_amount NUMERIC(18,2),
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE if not exists investment_accounts (
    id BIGSERIAL PRIMARY KEY,
    investor_id UUID REFERENCES investors(id),
    external_mf_investment_account_id VARCHAR(100),
    folio_number VARCHAR(50),
    status VARCHAR(30),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE if not exists orders (
    id BIGSERIAL PRIMARY KEY,
    investor_id UUID REFERENCES investors(id),
    distributor_id UUID REFERENCES distributors(id),
    investment_account_id BIGINT REFERENCES investment_accounts(id),
    scheme_id BIGINT REFERENCES fund_schemes(id),
    order_type VARCHAR(30),
    external_order_id VARCHAR(100),
    amount NUMERIC(18,2),
    units NUMERIC(18,4),
    status VARCHAR(30),
    payment_status VARCHAR(30),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE if not exists payments (
    id BIGSERIAL PRIMARY KEY,
    investor_id UUID REFERENCES investors(id),
    order_id BIGINT REFERENCES orders(id),
    external_payment_id VARCHAR(100),
    external_mandate_id VARCHAR(100),
    payment_mode VARCHAR(30),
    amount NUMERIC(18,2),
    status VARCHAR(30),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE if not exists api_logs (
    id BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(50),
    entity_id UUID,
    api_name VARCHAR(100),
    request_data JSONB,
    response_data JSONB,
    status VARCHAR(30),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- DUMMY DATA INSERTS --

INSERT INTO distributors (id, created_at, updated_at, full_name, mobile_number, email, arn_number, nism_certificate_number, nism_expiry_date, e_uin_number, status, kyc_status, bank_account_number, bank_ifsc, bank_account_holder_name, profile_completion_percent, internal_rm) 
VALUES ('d9b2d63d-a233-4123-8478-3b169d988b48', now(), now(), 'Master Distributor', '9876543210', 'alice@example.com', 'ARN-123456', 'NISM-987', '2028-12-31', 'E-UIN-888', 'APPROVED', 'COMPLETED', '100020003000', 'HDFC0000001', 'Alice', 100, false);

INSERT INTO investors (id, created_at, updated_at, distributor_id, full_name, mobile_number, email, pan, date_of_birth, address_line1, city, state, postal_code, investor_status, kyc_status, bank_verification_status, risk_profile, cybrilla_investor_id)
VALUES ('b75fef2e-7440-42f0-9ef2-5b9db054e526', now(), now(), 'd9b2d63d-a233-4123-8478-3b169d988b48', 'Bob Investor', '9988776655', 'bob@example.com', 'ABCDE1234F', '1990-01-01', '123 Main St', 'Mumbai', 'Maharashtra', '400001', 'ACTIVE', 'COMPLETED', 'VERIFIED', 'MODERATE', 'CYB-INV-1');

INSERT INTO investor_bank_accounts (id, created_at, updated_at, investor_id, account_holder_name, account_number, ifsc_code, bank_name, branch_name, verification_status)
VALUES ('c34b1793-1b91-4df2-8c44-d8bc289b535d', now(), now(), 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'Bob', '555566667777', 'ICIC0000001', 'ICICI Bank', 'Main Branch', 'VERIFIED');

INSERT INTO product_schemes (id, created_at, updated_at, scheme_name, amc_name, category, external_scheme_code, external_isin, product_type, active)
VALUES ('a123fef2-7440-42f0-9ef2-5b9db054a123', now(), now(), 'Super Growth Fund', 'Super AMC', 'EQUITY', 'MF-SG-100', 'INF123456789', 'MUTUAL_FUND', true);

INSERT INTO transaction_orders (id, created_at, updated_at, investor_id, distributor_id, product_scheme_id, transaction_type, order_status, amount, units, payment_mode)
VALUES ('e012fe1c-7440-42f0-9ef2-5b9db054e012', now(), now(), 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'a123fef2-7440-42f0-9ef2-5b9db054a123', 'LUMPSUM_PURCHASE', 'COMPLETED', 10000.00, 100.0000, 'NET_BANKING');

INSERT INTO redemption_records (id, created_at, updated_at, order_id, investor_id, redemption_status, units, amount)
VALUES ('f123fe1c-7440-42f0-9ef2-5b9db0123456', now(), now(), 'e012fe1c-7440-42f0-9ef2-5b9db054e012', 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'PENDING', 50.0000, 5000.00);

INSERT INTO notifications (id, created_at, updated_at, distributor_id, investor_id, type, title, message, read_flag)
VALUES ('8fa1ae9b-0000-4b2a-8cfa-5b9d12341234', now(), now(), 'd9b2d63d-a233-4123-8478-3b169d988b48', 'b75fef2e-7440-42f0-9ef2-5b9db054e526', 'ORDER_STATUS', 'Order Completed', 'Your purchase order is successful.', false);

INSERT INTO investor_leads (id, created_at, updated_at, prospect_name, mobile_number, email, city, source, status, assigned_distributor_id)
VALUES ('ca123eb4-0000-4123-85af-bbbbccccdddd', now(), now(), 'Charlie Prospect', '9999999999', 'charlie@example.com', 'Delhi', 'WEBSITE', 'NEW', 'd9b2d63d-a233-4123-8478-3b169d988b48');

INSERT INTO lead_interactions (id, created_at, updated_at, lead_id, distributor_id, comment_text, interaction_type)
VALUES ('11112222-3333-4444-5555-666677778888', now(), now(), 'ca123eb4-0000-4123-85af-bbbbccccdddd', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'Called customer but no answer.', 'CALL');

INSERT INTO audit_events (id, created_at, updated_at, entity_type, entity_id, action_type, actor_id, details_json)
VALUES ('aaaaabbb-cccc-dddd-eeee-ffff00001111', now(), now(), 'DISTRIBUTOR', 'd9b2d63d-a233-4123-8478-3b169d988b48', 'CREATED', 'd9b2d63d-a233-4123-8478-3b169d988b48', '{"reason": "signup"}');

-- PRINT DUMMY DATA (Note: Flyway may not print result sets to the Spring Boot console natively) --
SELECT * FROM distributors;
SELECT * FROM investors;
SELECT * FROM investor_bank_accounts;
SELECT * FROM product_schemes;
SELECT * FROM transaction_orders;
SELECT * FROM redemption_records;
SELECT * FROM notifications;
SELECT * FROM investor_leads;
SELECT * FROM lead_interactions;
SELECT * FROM audit_events;
SELECT * FROM kyc_records;
SELECT * FROM documents;
SELECT * FROM bank_accounts;
SELECT * FROM fund_schemes;
SELECT * FROM investment_accounts;
SELECT * FROM orders;
SELECT * FROM payments;
SELECT * FROM api_logs;
