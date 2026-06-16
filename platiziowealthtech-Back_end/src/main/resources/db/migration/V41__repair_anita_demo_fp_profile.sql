-- Anita Verma demo investor: reset stale Finprim profile linked to poisoned invp_3605…
-- (missing occupation on POST, .local email). Fresh PAN triggers clean POST /v2/investor_profiles.

UPDATE investors
SET email                   = 'anita.demo@platizio.in',
    pan                     = 'ANVPA3751A',
    onboarding_notes        = 'gender=female;occupation=service;income=upto_1lakh',
    cybrilla_investor_id    = NULL,
    external_mf_investment_account_id = NULL,
    external_sync_pending   = false,
    external_sync_message   = NULL,
    updated_at              = now()
WHERE id = '9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03'::uuid
  AND (
        pan = 'CCCPC3751C'
        OR email LIKE '%@platizio.local'
        OR cybrilla_investor_id = 'invp_3605d8a4b9b049ccb3c623f5e23ddb59'
      );

UPDATE investor_bank_accounts
SET cybrilla_bank_id                     = NULL,
    fp_bank_account_old_id               = NULL,
    cybrilla_bank_verification_id        = NULL,
    cybrilla_bank_verification_status    = NULL,
    cybrilla_bank_verification_confidence = NULL,
    external_sync_pending                = false,
    external_sync_message                = NULL,
    updated_at                           = now()
WHERE investor_id = '9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03'::uuid
  AND (
        cybrilla_bank_id = 'bac_00cdf2b50dfb403da81e7c3c0b7d417f'
        OR cybrilla_bank_id IS NOT NULL
      )
  AND EXISTS (
        SELECT 1
        FROM investors i
        WHERE i.id = investor_bank_accounts.investor_id
          AND i.cybrilla_investor_id IS NULL
      );
