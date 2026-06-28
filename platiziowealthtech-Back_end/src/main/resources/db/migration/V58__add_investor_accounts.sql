-- Investor portal identity (SRS PLZ-SRS-INV-COMP-001 §13, FR-AUTH).
-- A self-authenticated investor login identity, SEPARATE from the
-- distributor-owned `investors` row. Passwordless: login is email/mobile OTP,
-- so there is NO password column. `investor_id` links to the distributor-created
-- `investors` row (by PAN), set only on explicit confirmation (never auto).
create table if not exists investor_accounts (
    id              uuid         primary key,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    full_name       varchar(255) not null,
    pan             varchar(10)  not null,
    email           varchar(320) not null,
    mobile_number   varchar(20)  not null,
    email_verified  boolean      not null default false,
    mobile_verified boolean      not null default false,
    status          varchar(24)  not null,   -- PENDING_ACTIVATION | ACTIVE | BLOCKED
    investor_id     uuid,
    activated_at    timestamptz
);

create unique index if not exists ux_investor_accounts_email on investor_accounts (lower(email));
create unique index if not exists ux_investor_accounts_pan   on investor_accounts (pan);
create index        if not exists idx_investor_accounts_investor on investor_accounts (investor_id);
