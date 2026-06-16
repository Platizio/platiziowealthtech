alter table investor_bank_accounts
    add column if not exists fp_bank_account_old_id integer;

alter table transaction_orders
    add column if not exists external_mandate_id integer,
    add column if not exists mandate_status varchar(50),
    add column if not exists external_payment_id integer;
