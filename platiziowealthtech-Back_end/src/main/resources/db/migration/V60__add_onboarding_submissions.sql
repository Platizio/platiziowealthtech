-- Approval-gated onboarding revisions (SRS FR-ONB-001/002/003).
-- A distributor-assembled KYC payload is frozen into an immutable revision with a
-- SHA-256 content hash. It stays DRAFT_AWAITING_INVESTOR until the investor
-- attests THAT EXACT hash (-> ATTESTED). The distributor cannot finalize unless
-- the latest revision is ATTESTED and its hash still matches the rendered
-- payload. Any post-attestation edit supersedes the revision (invalidating the
-- attestation). Append-only history; revisions are never destructively updated
-- except for the status transitions above.
create table if not exists onboarding_submissions (
    id              uuid         primary key,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    investor_id     uuid         not null,
    revision_no     integer      not null,
    status          varchar(32)  not null,   -- DRAFT_AWAITING_INVESTOR | ATTESTED | SUPERSEDED
    payload_json    text         not null,
    content_sha256  varchar(64)  not null,
    submitted_by    uuid         not null,   -- distributor actor
    submitted_at    timestamptz  not null,
    attested_at     timestamptz,
    attested_by     uuid,                     -- investor account
    attestation_ip  varchar(64),
    attestation_ua  varchar(512)
);

create index if not exists idx_onboarding_sub_investor on onboarding_submissions (investor_id);
-- At most one non-superseded (live) revision per investor.
create unique index if not exists ux_onboarding_sub_live
    on onboarding_submissions (investor_id) where status <> 'SUPERSEDED';
