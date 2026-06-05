# Platizio Backend Integration Map

Use this map to enter the backend quickly. Re-read the live files before editing because they may contain uncommitted work.

## Adapter Boundary

- Interface: `src/main/java/com/platizio/wealthtech/integration/CybrillaClient.java`
- Real adapter: `src/main/java/com/platizio/wealthtech/integration/RealCybrillaClient.java`
- Mock adapter: `src/main/java/com/platizio/wealthtech/integration/MockCybrillaClient.java`
- Auth service: `src/main/java/com/platizio/wealthtech/integration/auth/ExternalBearerTokenService.java`
- Config: `src/main/resources/application.yml`

## Current API Split

`RealCybrillaClient` currently uses:

- FP tenant base URL for `/v2/...` resources with `getFinprimTenantAccessToken()` and optional `x-tenant-id`
- POA base URL for `/poa/pre_verifications` with `getCybrillaPreVerificationAccessToken()`
- separate one-time `401` invalidation and retry paths for both audiences

## Existing Workflows

Investor profile:

- `POST /v2/investor_profiles`
- create address, email, and phone child resources
- create `POST /v2/mf_investment_accounts` before ordering

KYC:

- create and fetch `/poa/pre_verifications` (this is the "KYC check"; `createKycCheck` posts an `investor_identifier: PAN` readiness payload)
- create, fetch, update, list, and sandbox-simulate `/v2/kyc_requests`
- create, fetch, and list `/v2/identity_documents`
- reconcile webhook and scheduled polling paths in `InvestorKycService`

Already-KYC (no re-KYC) architecture:

- The richest "is this PAN already KYC compliant?" API is the FP **KYC Check** (`POST /api/kyc/check`, `GET /api/kyc/{id}`, `PUT /api/kyc/{id}/refetch`; FP tenant surface, `x-tenant-id` + Bearer). Body `{"pan"}` for status; add `date_of_birth` to also fetch `entity_details` (requires SEBI RIA/AMC licence — AMFI ARN holders get status only).
  - Response `status:true` → KYC Validated → `KycStatus.COMPLETED` (skip re-KYC). `constraints[]` (e.g. `investment_limit`) means compliant-with-limits.
  - `status:false` + `reason`/`action` (interpret `action`, never free-text `reason`): `create` (`unavailable`/`rejected`) → fresh KYC; `modify` (`incomplete`/`legacy`/`onhold`, i.e. KYC Registered/Verified not Validated per SEBI Apr-2024) → one-time modification; `none` (`underprocess`) → wait/poll; `disallowed` (`deactivated`) → hard block.
  - Backend: `CybrillaClient.createKycComplianceCheck/fetchKycComplianceCheck/refetchKycComplianceCheck`; `InvestorKycService.runKycComplianceCheck(fetchData)`; endpoint `POST /api/v1/investors/{id}/kyc-compliance-check?fetchData=`. Persisted on Investor: `externalKycComplianceId`, `kycComplianceStatus`, `kycComplianceReason`, `kycComplianceAction`, `kycConstraintsJson` (Flyway `V5`).
  - `InvestorExternalKycResponse.kyc.state` is a doc-aligned `KycState` enum (VERIFIED / VERIFIED_WITH_CONSTRAINTS / NEEDS_MODIFICATION / UNDER_PROCESS / FRESH_KYC_REQUIRED / BLOCKED / IN_PROGRESS / UNKNOWN) + `message` for the customer indicator.
- A lighter alternative is the POA pre-verification readiness check (`POST /poa/pre_verifications` with `{"investor_identifier":"PAN"}`); used by `applyInvestorKyc` and also does PAN/name/DOB validation.
- Unique response that means KYC was already done elsewhere (another distributor/AMC/KRA): `readiness.status = verified` → mapped to `KycStatus.COMPLETED`; the flow does NOT create a `/v2/kyc_requests` application.
- `readiness.status = failed` + `readiness.code = kyc_unavailable` → no existing KYC → start a fresh `/v2/kyc_requests` application (only branch that triggers full KYC).
- `POST /api/v1/investors/{id}/kyc/apply` (`applyInvestorKyc`) runs the readiness check after details are submitted and only starts fresh KYC when `kyc_unavailable`; verified PANs skip re-KYC with a single readiness call.
- `InvestorKycService.createKycRequest` is guarded by `isAlreadyKycCompliant` (readiness verified or `KycStatus.COMPLETED`) and short-circuits without calling Cybrilla, auditing `KYC_REQUEST_SKIPPED_ALREADY_VERIFIED`.
- `InvestorExternalKycResponse.kyc` (`KycDecision`) exposes `alreadyKycCompliant`, `freshKycRequired`, `reKycSkipped`, and `message` so the frontend skips the KYC step without parsing raw provider JSON.

Bank:

- create `/v2/bank_accounts`
- create POA `/poa/pre_verifications`
- fetch `pv_...` records through POA and legacy `bav_...` records through FP
- reconcile in `InvestorService` and `InvestorBankVerificationSyncScheduler`

Funds and orders:

- fetch FP mutual fund catalogue: `GET /api/oms/fund_schemes` (paginated, bounded pages, rate-limit retry)
- fetch FP SIF catalogue for the POA gateway: `GET /v2/sif_scheme_plans/cybrillapoa?expand=sif_scheme,sif_fund` (same pagination pattern; 404 means SIF not enabled for the tenant)
- optional POA-tradeable MF subset export uses `GET /v2/mf_scheme_plans/cybrillapoa?expand=mf_scheme,mf_fund` (see `exports/cybrilla-poa-fund-schemes-*.json`)
- `ProductService.refreshFromCybrilla` replaces local MF + SIF rows imported from Finprim (not manual seed SIF)
- catalogue sync throttle: default 25 min between full imports unless `forceCatalogueRefresh=true` (POST `/schemes/sync` always forces; GET `syncFromCybrilla` does not)
- `RealCybrillaClient.fetchProductSchemes` uses one scoped Finprim bearer per batch; SIF endpoint skipped for JVM lifetime after first 404
- OAuth tokens: `ExternalBearerTokenService` — file + memory cache, 120s refresh buffer; INFO logs only on real refresh/restart, not every HTTP call
- prevent catalogue deactivation after a partial fetch
- create `/v2/mf_purchases`, `/v2/mf_purchase_plans`, and `/v2/mf_redemptions`
- use idempotency keys for mutating order operations

## Main Services

- Investor onboarding and bank reconciliation: `src/main/java/com/platizio/wealthtech/service/InvestorService.java`
- KYC state mapping and webhook reconciliation: `src/main/java/com/platizio/wealthtech/service/InvestorKycService.java`
- Fund catalogue sync: `src/main/java/com/platizio/wealthtech/service/ProductService.java`
- Orders: `src/main/java/com/platizio/wealthtech/service/OrderService.java`
- KYC polling: `src/main/java/com/platizio/wealthtech/service/InvestorKycStatusSyncScheduler.java`
- Bank polling: `src/main/java/com/platizio/wealthtech/service/InvestorBankVerificationSyncScheduler.java`
- Webhook entry point: `src/main/java/com/platizio/wealthtech/controller/CybrillaWebhookController.java`

## Review Hotspots

- Verify provider webhook authentication and idempotency persistence before production use.
- Preserve user changes: this repository often has active uncommitted integration work.
- Check whether a task requires FP tenant REST, POA additional APIs, or direct ONDC callbacks before adding methods.
- Confirm whether provider docs require a different path for updating resources. Some FP docs list update routes differently across older and newer pages.
- Keep external sync failures recoverable: save local onboarding work, persist pending state, and expose a retry or polling path.
