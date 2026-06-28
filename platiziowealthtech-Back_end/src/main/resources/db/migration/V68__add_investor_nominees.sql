-- REQUIREMENT #4 — Nomination capability + nominee addition (investor-only).
-- Each nominee is a row in investor_nominees with an allocation percentage;
-- allocations across an investor's nominees sum to 100 (enforced in the service).
-- The "I choose not to nominate" opt-out is NOT a nominee row — it is a flag on
-- the investors table (nomination_opted_out) recorded alongside a consent record.
create table if not exists investor_nominees (
    id                     uuid         primary key,
    created_at             timestamptz  not null default now(),
    updated_at             timestamptz  not null default now(),
    investor_id            uuid         not null,
    full_name              varchar(255) not null,
    relationship           varchar(64)  not null,
    date_of_birth          date,
    allocation_percentage  integer,
    address_line           varchar(512),
    guardian_name          varchar(255)
);

create index if not exists idx_investor_nominees_investor on investor_nominees (investor_id);

-- Opt-out lives on the investor, not a nominee row.
alter table investors
    add column if not exists nomination_opted_out boolean not null default false;
