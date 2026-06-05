# POA Pre-Verification API

Source: https://poa.cybrilla.com/docs/additional-apis/pre-verifications

## Purpose

Use pre-verification before order placement to evaluate:

- investor readiness to invest
- bank accounts
- PAN, name, and date-of-birth matching

The API is asynchronous. Create a record, persist its `pv_...` ID, then fetch until the top-level `status` reaches `completed`. Webhook events are emitted for accepted and completed transitions.

## Endpoints

- Create: `POST /poa/pre_verifications`
- Fetch: `GET /poa/pre_verifications/:id`
- Auth: `Authorization: Bearer <poa-token>`

## Request Shapes

Readiness:

```json
{
  "investor_identifier": "PAN"
}
```

PAN validation:

```json
{
  "pan": { "value": "PAN" },
  "name": { "value": "Investor Name" },
  "date_of_birth": { "value": "YYYY-MM-DD" }
}
```

Bank verification:

```json
{
  "pan": { "value": "PAN" },
  "name": { "value": "Investor Name" },
  "bank_accounts": [
    {
      "value": {
        "account_number": "ACCOUNT_NUMBER",
        "ifsc_code": "IFSC",
        "account_type": "savings"
      }
    }
  ]
}
```

Supported bank account types are case-sensitive: `savings`, `current`, `nre_savings`, and `nro_savings`.

## Response Interpretation

Top-level `status`:

- `accepted`: processing has started
- `completed`: inspect the nested result objects

Readiness result:

- `verified`: investor may invest
- `failed` + `kyc_unavailable`: start a fresh KYC application
- `failed` + `kyc_incomplete`: guide the investor through KYC completion or update
- `failed` + `upstream_error`: retry
- `failed` + `unknown`: retain as a non-compliant unresolved case

PAN, name, and date-of-birth results:

- `verified`: field matched
- PAN failures: `invalid`, `aadhaar_not_linked`, `upstream_error`
- name failures: `mismatch`, `upstream_error`
- date-of-birth failures: `mismatch`, `upstream_error`

Bank account results:

- `verified`: account may be used for transactions
- `failed` + `bank_verification_failed`: retry or collect another account
- `failed` + `low_confidence`: collect corrected details or another account
- `failed` + `uncertain`: eligible for a new manually approved attempt
- `failed` + `bank_account_proof_required`: upload proof and create a new attempt
- null nested status and code: still processing

## Manual Verification

Attempt normal digital verification first. Add `verify_manually_if_required: true` only when the result indicates manual follow-up. For NRI `nre_savings` and `nro_savings` accounts, provide the uploaded POA file ID as `bank_account_proof`; the official page states that NRI account verification is currently manual.

## Implementation Notes

- Program against nested `code`, not free-text `reason`.
- Keep a polling fallback even when consuming `pre_verification.accepted` and `pre_verification.completed`.
- For account verification, persist the `pv_...` record ID separately from the FP `bac_...` bank account ID.
