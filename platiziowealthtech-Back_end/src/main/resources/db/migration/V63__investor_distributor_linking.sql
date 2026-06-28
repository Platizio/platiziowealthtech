-- Investor↔distributor linking backbone (investor.md §3/§4, R4/R5).
-- PAN (investors.pan UNIQUE) is the sole investor↔distributor identifier (R4).
-- distributor_id becomes NULLABLE — it is linked only when the investor APPROVES
-- the distributor's onboarding request (R5); until then the initiating distributor is
-- held in pending_distributor_id. linking_status drives the approval choreography and is
-- kept separate from investor_status (which retains its transaction-readiness meaning).
alter table investors alter column distributor_id drop not null;

alter table investors add column pending_distributor_id uuid references distributors (id);

alter table investors add column linking_status varchar(32) not null default 'READY';

-- Existing investors are already linked + onboarded → 'READY' (the column default).
create index if not exists idx_investors_pending_distributor
    on investors (pending_distributor_id) where pending_distributor_id is not null;

create index if not exists idx_investors_linking_status
    on investors (linking_status);
