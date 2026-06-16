-- Cybrilla sandbox BAV pass requires account numbers ending in 1193 (not 9193).
UPDATE investor_bank_accounts
SET account_number = '98123451193',
    verification_status = 'VERIFIED',
    cybrilla_bank_id = NULL,
    fp_bank_account_old_id = NULL,
    cybrilla_bank_verification_id = NULL,
    cybrilla_bank_verification_status = NULL,
    cybrilla_bank_verification_confidence = NULL,
    external_sync_pending = false,
    external_sync_message = NULL,
    updated_at = now()
WHERE investor_id = '9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03'::uuid
  AND account_number = '98123459193';
