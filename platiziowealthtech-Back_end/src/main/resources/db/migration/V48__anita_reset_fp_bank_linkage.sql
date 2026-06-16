-- Anita: clear stale FP bank/BAV linkage so order prep re-captures bac_* for 98123451193 and fresh POA pv_*.
UPDATE investor_bank_accounts
SET cybrilla_bank_id = NULL,
    fp_bank_account_old_id = NULL,
    cybrilla_bank_verification_id = NULL,
    cybrilla_bank_verification_status = NULL,
    cybrilla_bank_verification_confidence = NULL,
    external_verification_request_json = NULL,
    external_verification_response_json = NULL,
    external_sync_pending = false,
    external_sync_message = NULL,
    verification_status = 'VERIFIED',
    updated_at = now()
WHERE investor_id = '9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03'::uuid;

UPDATE investors
SET external_sync_pending = false,
    external_sync_message = NULL,
    updated_at = now()
WHERE id = '9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03'::uuid;
