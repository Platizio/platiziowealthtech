-- Task 4 / DF-10: persisted Terms & Conditions acceptances.
-- One row per (subject, document, version) acceptance, with timestamp + IP/UA for
-- the audit trail. Distinct from the FATCA declaration captured in onboarding notes.
create table if not exists terms_acceptances (
    id           uuid primary key,
    created_at   timestamptz   not null,
    updated_at   timestamptz   not null,
    subject_type varchar(16)   not null,   -- INVESTOR | DISTRIBUTOR
    subject_id   uuid          not null,
    document_key varchar(64)   not null,   -- e.g. investor_tnc
    version      varchar(32)   not null,   -- e.g. v1.0
    accepted_at  timestamptz   not null,
    ip_address   varchar(64),
    user_agent   varchar(512)
);

create index if not exists idx_terms_subject
    on terms_acceptances (subject_type, subject_id, document_key);
