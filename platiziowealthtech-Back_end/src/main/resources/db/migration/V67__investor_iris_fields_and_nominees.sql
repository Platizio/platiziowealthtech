-- IRIS investor-onboarding rich fields (Phase 1). Twelve self-declared KYC-material
-- scalars promoted out of the legacy onboarding_notes key=value smuggling into proper,
-- nullable columns so existing READY rows are unaffected. These join the canonical
-- onboarding freeze (InvestorController.onboardingSnapshotJson) and apply-back
-- (InvestorService.applyApprovedProfileFields) and are fed to Cybrilla kyc_form.
alter table investors
    add column if not exists holding_mode                 varchar(32),
    add column if not exists category                     varchar(16),
    add column if not exists gender                       varchar(16),
    add column if not exists country_of_birth             varchar(64),
    add column if not exists country_of_citizenship       varchar(64),
    add column if not exists tax_resident_other_country   boolean,
    add column if not exists annual_income                varchar(32),
    add column if not exists occupation                   varchar(48),
    add column if not exists source_of_wealth             varchar(48),
    add column if not exists pep                          boolean,
    add column if not exists relative_of_pep              boolean,
    add column if not exists display_nominees             boolean;

-- Investor nominees (up to 3). Modelled on investor_bank_accounts: an investor_id FK
-- column with NO JPA relationship; rows are delete-then-insert replaced as a set and
-- frozen into the onboarding snapshot in nominee_index order.
create table if not exists investor_nominees (
    id                uuid          primary key,
    created_at        timestamptz   not null,
    updated_at        timestamptz   not null,
    investor_id       uuid          not null,
    nominee_index     int           not null,
    full_name         varchar(160)  not null,
    date_of_birth     date,
    relationship      varchar(48),
    share_percent     numeric(5, 2),
    mobile_number     varchar(20),
    email             varchar(160),
    id_type           varchar(32),
    id_number         varchar(64),
    address_line1     varchar(160),
    address_line2     varchar(160),
    address_line3     varchar(160),
    city              varchar(80),
    state             varchar(80),
    postal_code       varchar(12),
    country           varchar(64),
    same_as_applicant boolean       not null default false
);

create index if not exists idx_investor_nominee_investor
    on investor_nominees (investor_id);

create unique index if not exists ux_investor_nominee_investor_index
    on investor_nominees (investor_id, nominee_index);
