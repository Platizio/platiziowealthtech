-- V63: investor link requests (investor.md M1, R3) — the token-addressed email
-- approval link the distributor sends after entering Step-1 basic identity. Modelled
-- on onboarding_submissions: a frozen, hashed review payload with one live request per
-- investor. Only the SHA-256 of the opaque link token is stored, never the raw token.
create table if not exists investor_link_requests (
    id                   uuid         primary key,
    created_at           timestamptz  not null,
    updated_at           timestamptz  not null,
    investor_id          uuid         not null,
    pan                  varchar(20)  not null,   -- R4 linking key (denormalised for lookup)
    distributor_id       uuid         not null,   -- the requesting distributor
    token_hash           varchar(64)  not null,   -- SHA-256 of the opaque email-link token
    status               varchar(24)  not null,   -- PENDING|APPROVED|REJECTED|EXPIRED|SUPERSEDED
    review_payload_json  text         not null,   -- the Step-1 identity the investor reviews
    review_sha256        varchar(64)  not null,
    sent_to_email        varchar(320) not null,
    expires_at           timestamptz  not null,
    approved_at          timestamptz,
    rejected_at          timestamptz,
    approval_ip          varchar(64),
    approval_ua          varchar(512),
    consent_record_id    uuid,                     -- evidence of approval; FK -> consent_records
    constraint fk_link_request_investor
        foreign key (investor_id) references investors (id),
    constraint fk_link_request_distributor
        foreign key (distributor_id) references distributors (id),
    constraint fk_link_request_consent
        foreign key (consent_record_id) references consent_records (id)
);

create unique index if not exists ux_link_request_token
    on investor_link_requests (token_hash);
create index if not exists idx_link_request_investor
    on investor_link_requests (investor_id);

-- One live (PENDING) link request per investor; a re-send supersedes the prior one.
create unique index if not exists ux_link_request_live
    on investor_link_requests (investor_id) where status = 'PENDING';
