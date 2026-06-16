-- HHHPX3751H re-links invp_82ab… which still fails ONDC review with investor_data_submission_error.
-- Use a never-used sandbox KYC-ready PAN and reset all FP/POA/MFIA linkage for a clean POST.

UPDATE investors
SET pan                              = 'KRTPX3751K',
    email                            = 'anita.demo@platizio.in',
    onboarding_notes                 = 'gender=female;occupation=service;income=upto_1lakh',
    cybrilla_investor_id             = NULL,
    external_mf_investment_account_id = NULL,
    external_kyc_check_id            = NULL,
    external_kyc_request_id          = NULL,
    external_kyc_compliance_id       = NULL,
    external_kyc_status              = NULL,
    external_kyc_payload_json        = NULL,
    kyc_readiness_status             = NULL,
    pan_verification_status          = NULL,
    external_sync_pending            = false,
    external_sync_message            = NULL,
    updated_at                       = now()
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
