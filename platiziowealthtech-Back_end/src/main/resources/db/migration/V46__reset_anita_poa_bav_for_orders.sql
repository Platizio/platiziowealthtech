-- Re-run POA BAV for Anita after fixing sandbox fast-path (BAV required for ONDC review).
UPDATE investor_bank_accounts
SET cybrilla_bank_verification_id = NULL,
    cybrilla_bank_verification_status = NULL,
    cybrilla_bank_verification_confidence = NULL,
    verification_status = 'VERIFIED',
    external_sync_pending = false,
    external_sync_message = NULL,
    updated_at = now()
WHERE investor_id = '9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03'::uuid
  AND account_number = '98123451193';
