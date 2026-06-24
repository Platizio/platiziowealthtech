-- Immutable consent evidence (SRS §13 consent_records, FR-CNS-002).
-- Richer than terms_acceptances: stores the EXACT rendered consent text + its
-- SHA-256, the template version, and the actor's IP/user-agent. Append-only;
-- corrections create superseding rows (never update/delete).
create table if not exists consent_records (
    id               uuid         primary key,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    subject_type     varchar(16)  not null,   -- INVESTOR | DISTRIBUTOR
    subject_id       uuid         not null,
    consent_key      varchar(64)  not null,   -- e.g. investor_tnc, contact_ownership_email, onboarding_attestation
    template_version varchar(32)  not null,
    rendered_text    text         not null,
    content_sha256   varchar(64)  not null,
    ip_address       varchar(64),
    user_agent       varchar(512),
    accepted_at      timestamptz  not null
);

create index if not exists idx_consent_subject on consent_records (subject_type, subject_id, consent_key);
