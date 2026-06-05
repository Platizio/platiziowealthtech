-- =============================================================================
-- Consolidated baseline schema (squash of legacy V1..V36).
-- Represents the FINAL cumulative state of every live table after all the
-- original incremental migrations were applied.
--
-- Legacy tables dropped by the original V16/V32 (kyc_records, documents,
-- bank_accounts, fund_schemes, investment_accounts, orders, payments,
-- api_logs, auth_refresh_tokens) are intentionally NOT recreated.
-- =============================================================================

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
    arn_expiry_date date,
    e_uin_number varchar(100),
    status varchar(50) not null,
    kyc_status varchar(50) not null,
    bank_account_number varchar(100),
    bank_ifsc varchar(50),
    bank_account_holder_name varchar(255),
    profile_completion_percent integer,
    internal_rm boolean,
    master_distributor_id uuid,
    role varchar(50) not null default 'MASTER_DISTRIBUTOR',
    password_hash varchar(255),
    constraint fk_distributors_master_distributor
        foreign key (master_distributor_id) references distributors (id)
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
    onboarding_notes varchar(1000),
    is_deleted boolean not null default false,
    deleted_at timestamp,
    anniversary_date date,
    goal_maturity_date date,
    relationship_type varchar(20) not null default 'SELF',
    household_id uuid not null,
    household_name varchar(255),
    guardian_investor_id uuid,
    guardian_pan varchar(10),
    external_kyc_check_id varchar(100),
    external_kyc_request_id varchar(100),
    external_kyc_status varchar(50),
    external_kyc_payload_json text,
    external_mf_investment_account_id varchar(100),
    kyc_readiness_status varchar(50),
    kyc_readiness_code varchar(100),
    kyc_readiness_reason varchar(500),
    pan_verification_status varchar(50),
    pan_verification_code varchar(100),
    pan_verification_reason varchar(500),
    pan_aadhaar_link_status varchar(50),
    pan_aadhaar_link_reason varchar(500),
    external_sync_pending boolean not null default false,
    external_sync_message varchar(1000),
    constraint uq_investor_email unique (email),
    constraint fk_investors_distributor
        foreign key (distributor_id) references distributors (id),
    constraint fk_investors_guardian_investor
        foreign key (guardian_investor_id) references investors (id),
    constraint chk_investors_relationship_type
        check (relationship_type in ('SELF', 'SPOUSE', 'MINOR', 'HUF')),
    constraint chk_investors_minor_guardian
        check (relationship_type <> 'MINOR'
               or guardian_pan is not null
               or guardian_investor_id is not null)
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
    cybrilla_bank_id varchar(100),
    cybrilla_bank_verification_id varchar(100),
    cybrilla_bank_verification_status varchar(50),
    cybrilla_bank_verification_confidence varchar(50),
    external_sync_pending boolean not null default false,
    external_sync_message varchar(1000),
    external_verification_request_json text,
    external_verification_response_json text
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
    metadata_json text,
    external_fetch_request_json text
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
    investor_action_url varchar(1000),
    product_category varchar(50),
    is_deleted boolean not null default false,
    deleted_at timestamp,
    sip_frequency varchar(20),
    sip_start_date date,
    sip_instalments integer,
    investor_action_token varchar(100)
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
    notes varchar(1000),
    is_deleted boolean not null default false,
    deleted_at timestamp
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

create table if not exists refresh_tokens (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    token_hash varchar(64) not null unique,
    distributor_id uuid not null,
    expires_at timestamptz not null,
    revoked boolean not null default false,
    revoked_at timestamptz,
    constraint fk_refresh_tokens_distributor
        foreign key (distributor_id) references distributors (id) on delete cascade
);

create table if not exists blocked_tokens (
    jti varchar(64) primary key,
    expires_at timestamptz not null
);

create table if not exists password_reset_tokens (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    token_hash varchar(64) not null unique,
    distributor_id uuid not null,
    expires_at timestamptz not null,
    used_at timestamptz,
    constraint fk_password_reset_tokens_distributor
        foreign key (distributor_id) references distributors (id)
);

create table if not exists life_event_reminders (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    distributor_id uuid not null,
    investor_id uuid not null,
    event_type varchar(50) not null,
    event_date date not null,
    reminder_date date not null,
    status varchar(50) not null,
    title varchar(250) not null,
    message varchar(1000) not null
);

create table if not exists investor_documents (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    investor_id uuid not null,
    distributor_id uuid not null,
    uploaded_by uuid not null,
    document_type varchar(50) not null,
    file_name varchar(255) not null,
    content_type varchar(100) not null,
    size_bytes bigint not null,
    content bytea not null,
    constraint fk_investor_documents_investor
        foreign key (investor_id) references investors (id) on delete cascade,
    constraint fk_investor_documents_distributor
        foreign key (distributor_id) references distributors (id),
    constraint fk_investor_documents_uploaded_by
        foreign key (uploaded_by) references distributors (id),
    constraint chk_investor_documents_document_type
        check (document_type in ('KYC', 'PAN', 'ADDRESS', 'SIGNATURE')),
    constraint chk_investor_documents_size
        check (size_bytes > 0 and size_bytes <= 5242880),
    constraint uq_investor_documents_investor_type
        unique (investor_id, document_type)
);

create table if not exists external_api_snapshots (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    provider varchar(40) not null,
    operation varchar(120) not null,
    method varchar(10) not null,
    path varchar(300) not null,
    request_json text,
    response_json text,
    status_code integer,
    success boolean not null,
    actor_id uuid,
    entity_type varchar(50),
    entity_id uuid
);
