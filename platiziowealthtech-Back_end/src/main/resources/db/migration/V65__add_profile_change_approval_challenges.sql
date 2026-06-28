-- Profile-change approval (2FA) challenges (Person-A R9/R10).
-- When a distributor fills or edits an investor's profile, the pending profile is
-- frozen into an immutable, hashed snapshot that the investor must approve (email
-- OTP + consent) before the change is applied. Mirrors transaction_approval_challenges
-- (V59): no profile write may fire unless an APPROVED challenge for that exact
-- investor exists AND its profile_change_sha256 still equals a freshly-recomputed
-- hash (ProfileChangeApprovalService.assertApprovedAndConsume, which atomically flips
-- APPROVED -> CONSUMED). Append-only evidence: status transitions only.
--
-- Status machine: PENDING -> CHALLENGE_SENT -> APPROVED -> CONSUMED;
-- PENDING|CHALLENGE_SENT|APPROVED -> EXPIRED|REJECTED|SUPERSEDED (terminal-fail).
-- CONSUMED authorizes exactly one profile-apply.
create table if not exists profile_change_approval_challenges (
    id                        uuid         primary key,
    created_at                timestamptz  not null,
    updated_at                timestamptz  not null,
    investor_id               uuid         not null,
    pending_profile_json      text         not null,
    profile_change_sha256     varchar(64)  not null,
    consent_template_version  varchar(32)  not null,
    consent_rendered_text     text         not null,
    consent_record_id         uuid,                     -- set on approve; FK -> consent_records
    status                    varchar(24)  not null,    -- PENDING|CHALLENGE_SENT|APPROVED|CONSUMED|EXPIRED|REJECTED|SUPERSEDED
    channel                   varchar(16)  not null,    -- EMAIL | MOBILE
    masked_destination        varchar(320),
    otp_purpose               varchar(32)  not null,
    expires_at                timestamptz,
    approved_at               timestamptz,
    consumed_at               timestamptz,
    ip_address                varchar(64),
    user_agent                varchar(512),
    session_id                varchar(128),
    delivery_attempts         integer      not null default 0,
    constraint fk_profile_approval_investor
        foreign key (investor_id) references investors (id),
    constraint fk_profile_approval_consent_record
        foreign key (consent_record_id) references consent_records (id)
);

create index if not exists idx_profile_approval_investor
    on profile_change_approval_challenges (investor_id);

-- At most one live (PENDING | CHALLENGE_SENT | APPROVED) challenge per investor.
create unique index if not exists ux_profile_approval_live
    on profile_change_approval_challenges (investor_id)
    where status in ('PENDING', 'CHALLENGE_SENT', 'APPROVED');
