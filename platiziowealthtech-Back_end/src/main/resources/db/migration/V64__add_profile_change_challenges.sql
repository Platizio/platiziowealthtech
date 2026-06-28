-- V64: profile-change approval challenges (investor.md M1, R9/R10). Mirrors the
-- transaction_approval_challenges pattern (freeze -> hash -> OTP+consent -> atomic
-- assertApprovedAndConsume before the edit is applied), but for investor profile data,
-- so the transaction live-uniqueness index stays clean. One live change per investor.
create table if not exists profile_change_challenges (
    id                        uuid         primary key,
    created_at                timestamptz  not null,
    updated_at                timestamptz  not null,
    investor_id               uuid         not null,
    investor_account_id       uuid,
    change_type               varchar(24)  not null,   -- INITIAL_DISTRIBUTOR_FILL | PROFILE_EDIT
    status                    varchar(16)  not null,   -- PENDING|CHALLENGE_SENT|APPROVED|CONSUMED|SUPERSEDED|EXPIRED|REJECTED
    snapshot_json             text         not null,
    snapshot_sha256           varchar(64)  not null,
    consent_template_version  varchar(32),
    consent_rendered_text     text,
    consent_record_id         uuid,
    channel                   varchar(16),
    masked_destination        varchar(320),
    otp_purpose               varchar(32),
    expires_at                timestamptz,
    approved_at               timestamptz,
    consumed_at               timestamptz,
    superseded_by             uuid,
    ip_address                varchar(64),
    user_agent                varchar(512),
    session_id                varchar(128),
    correlation_id            varchar(128),
    delivery_attempts         integer      not null default 0,
    constraint fk_profile_change_investor
        foreign key (investor_id) references investors (id),
    constraint fk_profile_change_account
        foreign key (investor_account_id) references investor_accounts (id),
    constraint fk_profile_change_consent
        foreign key (consent_record_id) references consent_records (id)
);

create index if not exists idx_profile_change_investor
    on profile_change_challenges (investor_id);
create index if not exists idx_profile_change_account
    on profile_change_challenges (investor_account_id);

-- At most one live (PENDING | CHALLENGE_SENT | APPROVED) change per investor.
create unique index if not exists ux_profile_change_live
    on profile_change_challenges (investor_id)
    where status in ('PENDING', 'CHALLENGE_SENT', 'APPROVED');
