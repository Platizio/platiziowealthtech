-- Nomination opt-out flag (REQUIREMENT #4): an investor may explicitly decline to nominate.
-- Backs Investor.nominationOptedOut (NOT NULL, default false) and NomineeService.optOut, which
-- flips this flag and records a consent. The flag lived on Sumit's branch but its column was
-- never migrated (the branch ran under Hibernate auto-DDL), so it is forward-added here.
-- default false backfills existing rows; the entity carries the same default.
alter table investors
    add column if not exists nomination_opted_out boolean not null default false;
