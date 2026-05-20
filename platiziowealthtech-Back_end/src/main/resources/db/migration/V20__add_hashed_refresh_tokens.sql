create table if not exists refresh_tokens (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    token_hash varchar(64) not null unique,
    distributor_id uuid not null,
    expires_at timestamptz not null,
    revoked boolean not null default false,
    revoked_at timestamptz,
    constraint fk_refresh_tokens_distributor
        foreign key (distributor_id) references distributors (id) on delete cascade
);

create index if not exists ix_refresh_tokens_distributor_id
    on refresh_tokens (distributor_id);

create index if not exists ix_refresh_tokens_expires_at
    on refresh_tokens (expires_at);

create index if not exists ix_refresh_tokens_revoked
    on refresh_tokens (revoked);
