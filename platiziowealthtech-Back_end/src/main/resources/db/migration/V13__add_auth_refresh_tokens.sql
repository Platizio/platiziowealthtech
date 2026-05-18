create table if not exists auth_refresh_tokens (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    token uuid not null unique,
    distributor_id uuid not null,
    expires_at timestamptz not null,
    constraint fk_auth_refresh_tokens_distributor
        foreign key (distributor_id) references distributors (id) on delete cascade
);

create index if not exists ix_auth_refresh_tokens_distributor_id
    on auth_refresh_tokens (distributor_id);

create index if not exists ix_auth_refresh_tokens_expires_at
    on auth_refresh_tokens (expires_at);
