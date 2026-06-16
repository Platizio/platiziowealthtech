-- Re-run POA BAV with investor_identifier after payload fix (SellerApp links BAV to PAN for ONDC review).

UPDATE investor_bank_accounts
SET cybrilla_bank_verification_id         = NULL,
    cybrilla_bank_verification_status     = NULL,
    cybrilla_bank_verification_confidence = NULL,
    external_verification_response_json   = NULL,
    external_sync_pending                 = false,
    external_sync_message                 = NULL,
    updated_at                            = now()
WHERE investor_id = '9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03'::uuid
  AND cybrilla_bank_verification_id IS NOT NULL;

UPDATE investors
SET external_kyc_compliance_id = NULL,
    updated_at                 = now()
WHERE id = '9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03'::uuid;
