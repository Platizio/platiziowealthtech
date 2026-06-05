-- =============================================================================
-- Cybrilla POA KYC Forms ("modify" workflow) local mirror.
-- One row per kyc_form object created against an investor. Stores a snapshot of
-- the outbound request and the latest inbound kyc_form response so the
-- Digilocker + eSign journey survives provider/network failures.
-- =============================================================================

create table if not exists investor_kyc_forms (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    investor_id uuid not null,
    distributor_id uuid not null,
    external_kyc_form_id varchar(100),
    type varchar(30),
    status varchar(50),
    reason varchar(1000),
    pan varchar(20),
    name varchar(255),
    date_of_birth date,
    proof_fetch_url varchar(1000),
    proof_status varchar(50),
    esign_url varchar(1000),
    esign_status varchar(50),
    signature_provided boolean not null default false,
    fields_needed_json text,
    proof_callback_url varchar(1000),
    esign_callback_url varchar(1000),
    expires_at timestamptz,
    last_synced_at timestamptz,
    external_request_json text,
    external_response_json text,
    constraint fk_investor_kyc_forms_investor
        foreign key (investor_id) references investors (id)
);

create index if not exists idx_investor_kyc_forms_investor_id
    on investor_kyc_forms (investor_id);

create unique index if not exists ux_investor_kyc_forms_external_id
    on investor_kyc_forms (external_kyc_form_id)
    where external_kyc_form_id is not null;

create index if not exists idx_investor_kyc_forms_status
    on investor_kyc_forms (status);
