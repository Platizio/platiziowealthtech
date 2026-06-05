-- Email one-time passcodes for login / signup verification.
-- Only the SHA-256 hash of the code is stored (never plaintext).
create table if not exists email_otps (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    email varchar(320) not null,
    purpose varchar(20) not null,
    code_hash varchar(64) not null,
    expires_at timestamptz not null,
    attempts integer not null default 0,
    consumed_at timestamptz
);

create index if not exists idx_email_otps_email_purpose
    on email_otps (email, purpose);

create index if not exists idx_email_otps_expires_at
    on email_otps (expires_at);
