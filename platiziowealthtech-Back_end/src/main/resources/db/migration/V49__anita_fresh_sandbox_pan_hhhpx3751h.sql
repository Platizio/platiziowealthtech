-- Anita: BBBPB3751B already has a poisoned FP profile (occupation missing, immutable).
-- Use fresh sandbox KYC-ready PAN HHHPX3751H and reset all FP/POA linkage for clean POST.

UPDATE investors
SET pan                     = 'HHHPX3751H',
    email                   = 'anita.demo@platizio.in',
    onboarding_notes        = 'gender=female;occupation=service;income=upto_1lakh',
    cybrilla_investor_id    = NULL,
    external_mf_investment_account_id = NULL,
    external_kyc_check_id   = NULL,
    external_kyc_request_id = NULL,
    external_kyc_compliance_id = NULL,
    external_sync_pending   = false,
    external_sync_message   = NULL,
    updated_at              = now()
WHERE id = '9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03'::uuid;

UPDATE investor_bank_accounts
SET cybrilla_bank_id                      = NULL,
    fp_bank_account_old_id                = NULL,
    cybrilla_bank_verification_id         = NULL,
    cybrilla_bank_verification_status     = NULL,
    cybrilla_bank_verification_confidence = NULL,
    external_sync_pending                 = false,
    external_sync_message                 = NULL,
    external_verification_response_json   = NULL,
    verification_status                   = 'VERIFIED',
    account_number                        = '98123451193',
    updated_at                            = now()
WHERE investor_id = '9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03'::uuid;
