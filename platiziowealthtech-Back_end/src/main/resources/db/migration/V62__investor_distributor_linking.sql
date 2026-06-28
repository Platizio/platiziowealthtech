-- V62: investor<->distributor linking foundation (investor.md M1, requirements R4/R5).
-- Moves the onboarding centre of gravity to the investor: distributor_id becomes
-- approval-gated (NULL until the investor approves the link), parked meanwhile in
-- pending_distributor_id, with a dedicated linking_status lifecycle that is separate
-- from investor_status (which keeps its transaction-readiness meaning).
-- All existing investors are already distributor-linked and live, so the column
-- default 'READY' backfills them correctly; no distributor_id is nulled retroactively.

-- R5: distributor_id must be NULL until the investor approves. The existing
-- fk_investors_distributor FK is unaffected by dropping NOT NULL.
alter table investors alter column distributor_id drop not null;

-- The distributor that initiated onboarding, parked until approval (not the live link).
alter table investors add column pending_distributor_id uuid;
alter table investors add constraint fk_investors_pending_distributor
    foreign key (pending_distributor_id) references distributors (id);

-- R3/R7 linking lifecycle, distinct from investor_status. Existing rows -> READY.
alter table investors add column linking_status varchar(32) not null default 'READY';

-- R4: PAN is the sole linking key; support pending-record lookup by PAN before a
-- distributor_id exists.
create index idx_investors_pan_pending
    on investors (pan) where linking_status = 'PENDING_INVESTOR_APPROVAL';

-- Fast path for "investors awaiting my approval" / distributor notifications.
create index idx_investors_pending_distributor on investors (pending_distributor_id);
