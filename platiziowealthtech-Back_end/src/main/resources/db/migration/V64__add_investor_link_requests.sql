-- Investor email-approval link requests (investor.md §4 V62, R3/R5).
-- When a distributor sends a Step-1 basic-identity onboarding request "to the investor",
-- a token-addressed link request is minted. The investor reviews the entered details
-- from an emailed link and APPROVES — only then is investors.distributor_id linked to
-- pending_distributor_id (R5). Modelled on onboarding_submissions: token-addressed and
-- at most one live (PENDING) request per investor.
create table if not exists investor_link_requests (
    id                       uuid         primary key,
    created_at               timestamptz  not null,
    updated_at               timestamptz  not null,
    investor_id              uuid         not null references investors (id),
    pan                      varchar(10)  not null,
    pending_distributor_id   uuid         not null references distributors (id),
    token                    varchar(64)  not null,
    status                   varchar(24)  not null,   -- PENDING | APPROVED | REJECTED | EXPIRED | SUPERSEDED
    onboarding_submission_id uuid,
    expires_at               timestamptz  not null,
    approved_at              timestamptz
);

create unique index if not exists ux_investor_link_request_token
    on investor_link_requests (token);

create index if not exists idx_investor_link_request_investor
    on investor_link_requests (investor_id);

-- At most one live (PENDING) link request per investor.
create unique index if not exists ux_investor_link_request_live
    on investor_link_requests (investor_id) where status = 'PENDING';
