create table if not exists blocked_tokens (
    jti varchar(64) primary key,
    expires_at timestamptz not null
);

create index if not exists ix_blocked_tokens_expires_at
    on blocked_tokens (expires_at);
