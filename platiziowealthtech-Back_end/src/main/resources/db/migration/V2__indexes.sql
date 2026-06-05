-- =============================================================================
-- Consolidated indexes & search extensions (squash of legacy V1..V36).
-- =============================================================================

create extension if not exists pg_trgm;

-- distributors ----------------------------------------------------------------
create unique index if not exists ux_distributors_e_uin_number
    on distributors (e_uin_number)
    where e_uin_number is not null;
create index if not exists ix_distributors_master_distributor_id
    on distributors (master_distributor_id);
create index if not exists idx_distributor_full_name_trgm
    on distributors using gin (full_name gin_trgm_ops);
create index if not exists idx_distributor_email_trgm
    on distributors using gin (email gin_trgm_ops);
create index if not exists idx_distributor_mobile_number_trgm
    on distributors using gin (mobile_number gin_trgm_ops);
create index if not exists idx_distributor_arn_number_trgm
    on distributors using gin (arn_number gin_trgm_ops);
create index if not exists idx_distributor_e_uin_number_trgm
    on distributors using gin (e_uin_number gin_trgm_ops);

-- investors -------------------------------------------------------------------
create index if not exists idx_investors_distributor_id
    on investors (distributor_id);
create index if not exists idx_investors_kyc_status
    on investors (kyc_status);
create index if not exists idx_investors_dashboard_status
    on investors (distributor_id, kyc_status, bank_verification_status, created_at desc)
    where is_deleted = false;
create index if not exists idx_investors_life_event_dates
    on investors (distributor_id, date_of_birth, anniversary_date, goal_maturity_date)
    where is_deleted = false
      and (date_of_birth is not null
           or anniversary_date is not null
           or goal_maturity_date is not null);
create index if not exists idx_investors_household_id
    on investors (household_id);
create index if not exists idx_investors_guardian_investor_id
    on investors (guardian_investor_id);
create index if not exists idx_investors_distributor_household
    on investors (distributor_id, household_id);
create index if not exists idx_investors_external_kyc_check_id
    on investors (external_kyc_check_id);
create index if not exists idx_investors_external_kyc_request_id
    on investors (external_kyc_request_id);
create index if not exists idx_investors_external_kyc_status
    on investors (external_kyc_status);
create index if not exists idx_investors_kyc_status_external_ids
    on investors (kyc_status, external_kyc_check_id, external_kyc_request_id);
create index if not exists idx_investors_external_mf_investment_account_id
    on investors (external_mf_investment_account_id);
create index if not exists idx_investors_external_sync_pending
    on investors (external_sync_pending)
    where external_sync_pending = true;
create index if not exists idx_investor_full_name_trgm
    on investors using gin (full_name gin_trgm_ops);
create index if not exists idx_investor_email_trgm
    on investors using gin (email gin_trgm_ops);
create index if not exists idx_investor_mobile_number_trgm
    on investors using gin (mobile_number gin_trgm_ops);
create index if not exists idx_investor_pan_trgm
    on investors using gin (pan gin_trgm_ops);

-- investor_bank_accounts ------------------------------------------------------
create index if not exists idx_investor_bank_accounts_cybrilla_bank_verification_id
    on investor_bank_accounts (cybrilla_bank_verification_id);
create index if not exists idx_investor_bank_accounts_external_sync_pending
    on investor_bank_accounts (external_sync_pending)
    where external_sync_pending = true;
create index if not exists idx_investor_bank_accounts_verification_sync
    on investor_bank_accounts (verification_status, cybrilla_bank_verification_id, updated_at);

-- transaction_orders ----------------------------------------------------------
create index if not exists idx_tx_orders_investor_id
    on transaction_orders (investor_id);
create index if not exists idx_tx_orders_distributor_type_created
    on transaction_orders (distributor_id, transaction_type, created_at);
create index if not exists idx_tx_orders_dashboard_failed
    on transaction_orders (distributor_id, order_status, created_at desc)
    where is_deleted = false;
create unique index if not exists uq_transaction_orders_investor_action_token
    on transaction_orders (investor_action_token)
    where investor_action_token is not null;

-- investor_leads --------------------------------------------------------------
create index if not exists idx_investor_leads_assigned_distributor_id
    on investor_leads (assigned_distributor_id);

-- notifications ---------------------------------------------------------------
create index if not exists idx_notifications_distributor_created
    on notifications (distributor_id, created_at desc);

-- audit_events ----------------------------------------------------------------
create index if not exists idx_audit_events_actor_id
    on audit_events (actor_id);

-- refresh_tokens --------------------------------------------------------------
create index if not exists ix_refresh_tokens_distributor_id
    on refresh_tokens (distributor_id);
create index if not exists ix_refresh_tokens_expires_at
    on refresh_tokens (expires_at);
create index if not exists ix_refresh_tokens_revoked
    on refresh_tokens (revoked);

-- blocked_tokens --------------------------------------------------------------
create index if not exists ix_blocked_tokens_expires_at
    on blocked_tokens (expires_at);

-- password_reset_tokens -------------------------------------------------------
create index if not exists ix_password_reset_tokens_distributor_id
    on password_reset_tokens (distributor_id);
create index if not exists ix_password_reset_tokens_expires_at
    on password_reset_tokens (expires_at);

-- life_event_reminders --------------------------------------------------------
create unique index if not exists ux_life_event_reminders_investor_event
    on life_event_reminders (investor_id, event_type, event_date);
create index if not exists idx_life_event_reminders_dashboard
    on life_event_reminders (distributor_id, status, event_date);

-- investor_documents ----------------------------------------------------------
create index if not exists idx_investor_documents_investor_id
    on investor_documents (investor_id);
create index if not exists idx_investor_documents_distributor_id
    on investor_documents (distributor_id);

-- external_api_snapshots ------------------------------------------------------
create index if not exists idx_external_api_snapshots_created_at
    on external_api_snapshots (created_at desc);
create index if not exists idx_external_api_snapshots_provider_operation
    on external_api_snapshots (provider, operation);
