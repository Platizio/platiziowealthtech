-- Transaction-approval (2FA) challenges (Phase-2 plan §"Data model", SRS FR-2FA).
-- A purchase / SIP / redemption is frozen into an immutable, hashed snapshot that
-- the investor must approve (email OTP + consent) before any Cybrilla/FP write
-- fires. No provider write is allowed unless a CONSUMED challenge for that exact
-- transaction exists AND its snapshot_sha256 still equals a freshly-recomputed
-- snapshot hash (TransactionApprovalService.assertConsumedFor). Append-only
-- evidence: status transitions only.
--
-- Status machine: PENDING -> CHALLENGE_SENT -> APPROVED -> CONSUMED;
-- PENDING|CHALLENGE_SENT -> EXPIRED|REJECTED|SUPERSEDED (terminal-fail).
-- CONSUMED authorizes exactly one provider action.
create table if not exists transaction_approval_challenges (
    id                        uuid         primary key,
    created_at                timestamptz  not null,
    updated_at                timestamptz  not null,
    transaction_id            uuid         not null,
    transaction_type          varchar(16)  not null,   -- PURCHASE | SIP | REDEMPTION
    investor_id               uuid         not null,
    investor_account_id       uuid         not null,
    status                    varchar(16)  not null,   -- PENDING|CHALLENGE_SENT|APPROVED|CONSUMED|EXPIRED|REJECTED|SUPERSEDED
    snapshot_json             text         not null,
    snapshot_sha256           varchar(64)  not null,
    consent_template_version  varchar(32)  not null,
    consent_rendered_text     text         not null,
    consent_record_id         uuid,                     -- set on approve; FK -> consent_records
    channel                   varchar(16)  not null,   -- EMAIL | MOBILE
    masked_destination        varchar(320),
    otp_purpose               varchar(32)  not null,
    expires_at                timestamptz,
    approved_at               timestamptz,
    consumed_at               timestamptz,
    superseded_by             uuid,
    ip_address                varchar(64),
    user_agent                varchar(512),
    session_id                varchar(128),
    correlation_id            varchar(128),
    delivery_attempts         integer      not null default 0,
    constraint fk_txn_approval_consent_record
        foreign key (consent_record_id) references consent_records (id)
);

create index if not exists idx_txn_approval_txn
    on transaction_approval_challenges (transaction_id);
create index if not exists idx_txn_approval_account
    on transaction_approval_challenges (investor_account_id);

-- At most one live (PENDING | CHALLENGE_SENT | APPROVED) challenge per transaction.
create unique index if not exists ux_txn_approval_live
    on transaction_approval_challenges (transaction_id)
    where status in ('PENDING', 'CHALLENGE_SENT', 'APPROVED');
