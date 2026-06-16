alter table investor_bank_accounts
    add column if not exists account_type varchar(50) not null default 'savings';
