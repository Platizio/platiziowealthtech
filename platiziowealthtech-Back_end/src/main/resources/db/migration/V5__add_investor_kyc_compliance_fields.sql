-- =============================================================================
-- FP KYC Check (/api/kyc/check) compliance result columns.
-- Stores the authoritative KRA compliance signal for an investor's PAN so the
-- app can skip re-KYC when already compliant and show the correct customer
-- indicator (verified / verified-with-limits / needs-modification /
-- under-process / fresh-KYC-required / blocked).
-- =============================================================================

alter table investors
    add column if not exists external_kyc_compliance_id varchar(100),
    add column if not exists kyc_compliance_status boolean,
    add column if not exists kyc_compliance_reason varchar(50),
    add column if not exists kyc_compliance_action varchar(50),
    add column if not exists kyc_constraints_json text;
