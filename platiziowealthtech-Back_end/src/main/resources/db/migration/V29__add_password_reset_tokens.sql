create table if not exists password_reset_tokens (
    id uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    token_hash varchar(64) not null unique,
    distributor_id uuid not null,
    expires_at timestamptz not null,
    used_at timestamptz,
    constraint fk_password_reset_tokens_distributor
        foreign key (distributor_id) references distributors (id)
);

create index if not exists ix_password_reset_tokens_distributor_id
    on password_reset_tokens (distributor_id);

create index if not exists ix_password_reset_tokens_expires_at
    on password_reset_tokens (expires_at);
