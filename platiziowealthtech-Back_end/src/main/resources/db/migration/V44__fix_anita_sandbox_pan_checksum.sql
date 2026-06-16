-- AVRMPX3751V fails FP PAN validation (4th character must be P for sandbox + checksum).
-- Use AVRPX3751A (valid XXXPX3751X) and reset FP linkage for a clean profile POST.
UPDATE investors
SET pan                     = 'AVRPX3751A',
    email                   = 'anita.demo@platizio.in',
    onboarding_notes        = 'gender=female;occupation=service;income=upto_1lakh',
    cybrilla_investor_id    = NULL,
    external_mf_investment_account_id = NULL,
    external_sync_pending   = false,
    external_sync_message   = NULL,
    updated_at              = now()
WHERE id = '9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03'::uuid;

UPDATE investor_bank_accounts
SET verification_status              = 'VERIFIED',
    cybrilla_bank_id                 = NULL,
    fp_bank_account_old_id           = NULL,
    cybrilla_bank_verification_id    = NULL,
    cybrilla_bank_verification_status = NULL,
    cybrilla_bank_verification_confidence = NULL,
    external_sync_pending            = false,
    external_sync_message            = NULL,
    updated_at                       = now()
WHERE investor_id = '9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03'::uuid;
