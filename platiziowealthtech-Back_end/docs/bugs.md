# Platizio Platform Bug Registry

> **Last audit:** 2026-06-15  
> **Supervisor:** `.cursor/agents/supervisor.md`  
> **Subagents used:** backend explore, frontend explore, cybrilla-boss, shell (Maven + smoke tests)

## Audit summary

| Check | Result |
|-------|--------|
| Backend health | `GET http://localhost:8081/actuator/health` → **UP** |
| Port 8081 | Listening (PID varies — ensure single JVM) |
| Maven tests | **261 run, 258 passed, 3 failed** |
| Frontend dev | `http://localhost:3000` (proxy → `:8081/api/v1`) |
| Investor-action | `http://localhost:8081/investor-actions/{token}` (not :3000) |
| Direct Cybrilla from browser | **None found** (architecture OK) |

### Failing unit tests

| Test | Issue |
|------|--------|
| `RequestBodyValidationTest.allControllerRequestBodiesUseValid` | Missing `@Valid` on `InvestorController.createEsign`, `OrderController.cancelSipOrder` |
| `RealCybrillaClientMetricsTest.postRequestsRecordCybrillaApiTimerWithOperationTag` | Mock URI missing `?pan=` query param |
| `RealCybrillaClientTest.createMfInvestmentAccountPostsProfileAndHoldingPattern` | Mock URI missing `?primary_investor=` query param |

---

## Priority matrix

| Severity | Count | Top items |
|----------|-------|-----------|
| **Critical** | 4 | FP tenant BAV, ONDC order failure, OMS admin sync, no purchase webhooks |
| **High** | 9 | Orphan orders, wrong HTTP status, demo advancer, onboarding draft, 502 UX |
| **Medium** | 14 | Resume steps, silent fetch failures, catalogue split, KYC postbacks |
| **Low** | 8 | Exception mapping, mock Earnings page, test drift |

---

## Critical

### BUG-001 — Critical — FP tenant BAV skipped → ONDC `investor_data_submission_error`

| Field | Value |
|-------|--------|
| **Area** | Cybrilla + Backend |
| **Status** | Open (Blocked: Cybrilla tenant config) |
| **URL** | `http://localhost:3000/distributor/ledger` → `http://localhost:8081/investor-actions/{token}` |
| **API** | `POST /api/v1/orders` |
| **Files** | `RealCybrillaClient.java` ~408–429, ~2391–2400; `InvestorService.java` ~1073–1144; `InvestorActionService.java` ~1124–1128 |
| **Description** | `POST /v2/bank_account_verifications` returns `"Tenant platizio is not configured"`. Client silently skips FP BAV. POA BAV may pass; ONDC purchase review fails with `investor_data_submission_error`. |
| **Reproduce** | Login → Ledger → Anita Verma (`9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03`), bank ending `1193`, amount ending `0` → open investor-action → FAILED |
| **Fix** | **Cybrilla:** enable FP BAV for sandbox tenant `platizio`. **Platizio:** fail fast (502) instead of silent skip; block order until FP BAV completes |

---

### BUG-002 — Critical — Local bank VERIFIED ≠ POA-settled BAV for ONDC

| Field | Value |
|-------|--------|
| **Area** | Backend |
| **Status** | Open |
| **URL** | Same as BUG-001 |
| **API** | `POST /api/v1/orders`; `InvestorKycService.ensurePoaReadinessForOrderPlacement` |
| **Files** | `InvestorService.java` ~1073–1076 |
| **Description** | Demo/seeded investors can show local `bank_verification_status=VERIFIED` without settled POA `pv_*`. ONDC review requires POA BAV, not local flags alone. |
| **Reproduce** | Use investor with local VERIFIED but stale/missing POA pre-verification → place order → review fails |
| **Fix** | Block order create until combined POA pre-verification settled; surface clear UI message |

---

### BUG-003 — Critical — Admin ProductMgmt syncs OMS catalogue (not POA-orderable)

| Field | Value |
|-------|--------|
| **Area** | Backend + Frontend |
| **Status** | Open |
| **URL** | `http://localhost:3000/admin/product-mgmt` vs `http://localhost:3000/distributor/ledger` |
| **API** | `GET /api/v1/products/schemes/page?syncFromCybrilla=true` (OMS) vs POA `mf_scheme_plans/cybrillapoa` |
| **Files** | `RealCybrillaClient.java` ~916–924; `ProductService.java`; `ProductMgmt.tsx` ~245–246; `orderableScheme.ts` |
| **Description** | Admin "Sync from Cybrilla" imports OMS `fund_schemes`. Ledger orders require POA catalogue. Admin sync can pollute DB with non-orderable schemes. |
| **Reproduce** | Admin sync MF schemes → distributor tries Invest Now on OMS-imported row → disabled or 400 |
| **Fix** | Route admin sync through POA catalogue or label OMS as "reference only" |

---

### BUG-004 — Critical — No webhooks for `mf_purchase` / `payment` events

| Field | Value |
|-------|--------|
| **Area** | Backend |
| **Status** | Open |
| **URL** | `http://localhost:3000/distributor/transactions`, `http://localhost:8081/investor-actions/{token}` |
| **API** | `POST /api/v1/cybrilla/webhooks` (KYC/pre_verification only) |
| **Files** | `CybrillaWebhookController.java` ~45–61 |
| **Description** | Production payment completion depends on polling/postback. No idempotent reconcile for purchase/payment terminal states. Local profile uses `DemoOrderAdvancer` instead. |
| **Reproduce** | Non-local deployment without advancer → orders stuck after payment redirect |
| **Fix** | Add webhook handlers → `OrderService` / `InvestorActionService` reconcile |

---

## High

### BUG-005 — High — Orphan `CREATED` orders on partial Cybrilla failure

| Field | Value |
|-------|--------|
| **Area** | Backend |
| **Status** | Open |
| **URL** | `http://localhost:3000/distributor/ledger`, `/distributor/transactions` |
| **API** | `POST /api/v1/orders`, `POST /api/v1/orders/bulk` |
| **Files** | `OrderService.java` ~229–313, ~474–477 |
| **Description** | Order saved locally as `CREATED` before FP submit. On `CybrillaApiException` / `IllegalStateException`, row stays `CREATED` with no investor-action URL. |
| **Reproduce** | Trigger FP profile/MFIA failure after local save → ghost order in Transactions |
| **Fix** | Mark `FAILED` or `RETRY_AVAILABLE` in post-save failure path |

---

### BUG-006 — High — FP failures mapped to HTTP 400 instead of 502

| Field | Value |
|-------|--------|
| **Area** | Backend |
| **Status** | Open |
| **URL** | `http://localhost:3000/distributor/ledger` |
| **API** | `POST /api/v1/orders`, redemptions |
| **Files** | `InvestorService.java` ~1376–1433; `GlobalExceptionHandler.java` ~78–92 |
| **Description** | `ensureMfInvestmentAccount` wraps `CybrillaApiException` as `IllegalStateException` → 400 instead of 502 `EXTERNAL_PLATFORM_ERROR`. |
| **Reproduce** | FP rejects profile PATCH during first order → API returns 400 with FP text |
| **Fix** | Rethrow `CybrillaApiException` / use `BAD_GATEWAY` |

---

### BUG-007 — High — `DemoOrderAdvancer` auto-completes real FP orders on `local` profile

| Field | Value |
|-------|--------|
| **Area** | Backend |
| **Status** | Open |
| **URL** | `http://localhost:8081/investor-actions/{token}` |
| **API** | N/A (scheduler) |
| **Files** | `DemoOrderAdvancer.java` ~18–106 |
| **Description** | Local profile advances all orders to SUCCESSFUL after ~15s without real payment. Conflicts with Cybrilla sandbox E2E. |
| **Reproduce** | Run `local` profile, place real FP order, wait → SUCCESSFUL without payment |
| **Fix** | Gate on demo stub `externalOrderId` or disable when sandbox simulation flags off |

---

### BUG-008 — High — Onboarding 409 on duplicate PAN (partially fixed)

| Field | Value |
|-------|--------|
| **Area** | Backend + Frontend |
| **Status** | Partially fixed |
| **URL** | `http://localhost:3000/distributor/investor-onboarding` (step 3 → 4) |
| **API** | `POST /api/v1/investors` |
| **Files** | `InvestorService.java` (resume onboarding draft); `InvestorOnboarding.tsx` (sessionStorage draft) |
| **Description** | POST on address step when PAN already exists. Backend now resumes DRAFT/ONBOARDING for same distributor. Still 409 for completed demo PANs (`AAAPA3751A`, `KRTPX3751K`). |
| **Reproduce** | Re-enter demo PAN with KYC COMPLETED → 409 |
| **Fix** | Use fresh sandbox PAN (`BBBPB3753B`) or resume from Investors list |

---

### BUG-009 — High — Onboarding draft lost on refresh (step/form not persisted)

| Field | Value |
|-------|--------|
| **Area** | Frontend |
| **Status** | Open |
| **URL** | `http://localhost:3000/distributor/investor-onboarding` |
| **API** | `POST /investors`, `GET /investors/{id}/onboarding/resume` |
| **Files** | `InvestorOnboarding.tsx` ~125–128, 349–379 |
| **Description** | sessionStorage saves investor ID only, not wizard step/KYC state/form fields. After refresh user lands on step 1 with empty fields. |
| **Reproduce** | Reach step 4 → hard refresh → step 1 empty |
| **Fix** | Persist step + form snapshot; auto-call resume API when draft ID found |

---

### BUG-010 — High — KYC Digilocker/eSign return loses resume context

| Field | Value |
|-------|--------|
| **Area** | Frontend |
| **Status** | Open |
| **URL** | `http://localhost:3000/distributor/investor-onboarding?investorId={id}&kycReturn=aadhaar` |
| **API** | `GET /investors/{id}`, identity-documents/esign refresh |
| **Files** | `App.tsx` ~239–263; `InvestorOnboarding.tsx` ~1575–1614; `kycFlow.ts` |
| **Description** | Postback URL has `investorId` in query but wrapper reads only navigation state. Cold load shows step 1 until async fetch completes. |
| **Reproduce** | Complete Digilocker redirect in new tab / cold load postback URL |
| **Fix** | Wrapper reads `searchParams.investorId`; show loading until postback handler completes |

---

### BUG-011 — High — Ledger missing 502/503 error handling

| Field | Value |
|-------|--------|
| **Area** | Frontend |
| **Status** | Open |
| **URL** | `http://localhost:3000/distributor/ledger` → Invest Now |
| **API** | `POST /api/v1/orders` |
| **Files** | `Ledger.tsx` ~1299–1317 (vs `InvestorTransaction.tsx` ~435–438) |
| **Description** | Generic errors on Cybrilla rate limits/outages. InvestorTransaction has proper 502/503 messaging; Ledger does not. |
| **Reproduce** | Place order when backend returns 502 |
| **Fix** | Mirror InvestorTransaction provider-error branch |

---

### BUG-012 — High — InvestorOnboarding KYC calls missing 502/503 handling

| Field | Value |
|-------|--------|
| **Area** | Frontend |
| **Status** | Open |
| **URL** | `http://localhost:3000/distributor/investor-onboarding` (steps 4–5) |
| **API** | `POST /investors/{id}/kyc-checks`, kyc-requests, bank-accounts |
| **Files** | `InvestorOnboarding.tsx` ~1178–1196, 1655–1659, 1757–1762 |
| **Description** | All Cybrilla-backed onboarding calls treat 502 as generic failure. |
| **Reproduce** | Run POA pre-verification with backend returning 502 |
| **Fix** | Shared helper: "Cybrilla temporarily unavailable — retry in a moment" |

---

### BUG-013 — High — Exception handler mislabels FP tenant errors as POA credential errors

| Field | Value |
|-------|--------|
| **Area** | Backend |
| **Status** | Open |
| **URL** | Any Cybrilla-backed flow |
| **API** | Various |
| **Files** | `GlobalExceptionHandler.java` (CybrillaApiException mapping) |
| **Description** | Message containing `"is not configured"` mapped to POA env var guidance — wrong for `"Tenant platizio is not configured"`. |
| **Reproduce** | Trigger FP BAV failure → misleading 502 message |
| **Fix** | Distinguish FP tenant config vs POA credential errors |

---

## Medium

### BUG-014 — Medium — Async lumpsum review poll swallows fetch failures

| **Area** | Backend |
| **Status** | Open |
| **URL** | `http://localhost:8081/investor-actions/{token}` |
| **API** | `POST /api/v1/orders` (async reconcile) |
| **Files** | `OrderService.java` ~382–398, ~325–347 |
| **Fix** | Set FAILED/PROCESSING with retry hint on transient FP errors |

---

### BUG-015 — Medium — Access denied thrown as `IllegalStateException` → HTTP 400

| **Area** | Backend |
| **Status** | Open |
| **URL** | N/A |
| **API** | `GET /api/v1/investors/by-distributor/{id}` |
| **Files** | `InvestorService.java` ~407; `DistributorService.java` |
| **Fix** | Use `AccessDeniedException` → 403 |

---

### BUG-016 — Medium — Investor-action maps `CybrillaUnavailableException` to 502 not 503

| **Area** | Backend |
| **Status** | Open |
| **URL** | `http://localhost:8081/investor-actions/{token}/confirm` |
| **API** | POST confirm |
| **Files** | `InvestorActionController.java` ~54–56 |
| **Fix** | Catch `CybrillaUnavailableException` first → 503 |

---

### BUG-017 — Medium — Hardcoded `localhost:3000` in failed-order HTML

| **Area** | Backend |
| **Status** | Open |
| **URL** | `http://localhost:8081/investor-actions/{token}` (FAILED state) |
| **Files** | `InvestorActionController.java` ~446 |
| **Fix** | Use configurable `app.frontend.origin` |

---

### BUG-018 — Medium — Corrupt BAV JSON skips POA refresh (false-negative)

| **Area** | Backend |
| **Status** | Open |
| **API** | `POST /api/v1/orders` pre-flight |
| **Files** | `InvestorService.java` ~1510–1520 |
| **Fix** | Treat JSON parse failure as missing `investor_identifier` |

---

### BUG-019 — Medium — FP KYC compliance check skipped on 404

| **Area** | Backend |
| **Status** | Open |
| **API** | Order placement |
| **Files** | `InvestorKycService.java` ~222–271 |
| **Fix** | Fail closed for ONDC unless sandbox flag allows skip |

---

### BUG-020 — Medium — Poisoned FP profile missing occupation (cannot PATCH)

| **Area** | Backend + Cybrilla |
| **Status** | Open (mitigated by migrations V49–V51) |
| **API** | `POST /api/v1/orders` |
| **Files** | `RealCybrillaClient.java` ~2488–2498 |
| **Fix** | Detect early on profile GET; auto-clear bad linkage |

---

### BUG-021 — Medium — Transaction-eligible search ignores `externalSyncPending`

| **Area** | Backend + Frontend |
| **Status** | Open |
| **URL** | `http://localhost:3000/distributor/ledger`, `/distributor/investor-transaction` |
| **API** | `GET /api/v1/investors/search/transaction-eligible` |
| **Files** | `InvestorService.java` ~389–393 |
| **Fix** | Exclude or flag investors with pending FP sync |

---

### BUG-022 — Medium — Resume step inference skips Consent/Personal steps

| **Area** | Frontend |
| **Status** | Open |
| **URL** | `http://localhost:3000/distributor/investors` → Continue onboarding |
| **API** | `GET /investors/{id}/onboarding/resume` |
| **Files** | `onboardingResume.ts` ~39–47; `Investors.tsx` ~776–807 |
| **Fix** | Prefer backend `nextStep`; validate required fields before step 4 |

---

### BUG-023 — Medium — Transactions list fetch fails silently

| **Area** | Frontend |
| **Status** | Open |
| **URL** | `http://localhost:3000/distributor/transactions` |
| **API** | `GET /api/v1/orders` |
| **Files** | `Transactions.tsx` ~214–285 |
| **Fix** | Add error state + retry banner |

---

### BUG-024 — Medium — Communications templates use non-functional placeholders

| **Area** | Frontend |
| **Status** | Open |
| **URL** | `http://localhost:3000/distributor/communications` |
| **Files** | `Communications.tsx` ~12–17 |
| **Fix** | Wire `[PAYMENT_LINK]` to `buildInvestorActionUrl` |

---

### BUG-025 — Medium — Investor-transaction breaks on direct navigation

| **Area** | Frontend |
| **Status** | Open |
| **URL** | `http://localhost:3000/distributor/investor-transaction` |
| **Files** | `App.tsx` ~266–271 |
| **Fix** | Accept `?investorId=` query param |

---

### BUG-026 — Medium — Demo `.local` emails skipped for FP folio defaults

| **Area** | Backend |
| **Status** | Open |
| **URL** | Onboarding/order for Priya/Rahul demo |
| **Files** | `RealCybrillaClient.java` (email TLD check) |
| **Fix** | Use real TLD emails in demo seed (`anita.demo@platizio.in`) |

---

### BUG-027 — Medium — Payment postback URL unreachable from FP in dev

| **Area** | Ops + Backend |
| **Status** | Open |
| **URL** | `http://localhost:8081/investor-actions/{token}/payment-complete` |
| **Fix** | Sandbox: use Simulate Payment; staging: public HTTPS/tunnel |

---

## Low

### BUG-028 — Low — `PersistenceException` handler maps all JPA errors to 409

| **Area** | Backend |
| **Files** | `GlobalExceptionHandler.java` ~210–221 |

---

### BUG-029 — Low — GlobalExceptionHandler gaps (ConstraintViolation, Multipart, etc.)

| **Area** | Backend |
| **Files** | `GlobalExceptionHandler.java` ~364–371 |

---

### BUG-030 — Low — Local webhook accepts unsigned payloads

| **Area** | Backend |
| **Files** | `CybrillaWebhookController.java` ~63–76 |

---

### BUG-031 — Low — `CybrillaDirectController` exposes raw FP/POA proxy to any authenticated user

| **Area** | Backend |
| **API** | `POST /api/v1/cybrilla/pre-verifications` |
| **Files** | `CybrillaDirectController.java` |

---

### BUG-032 — Low — Earnings page is entirely mock data

| **Area** | Frontend |
| **URL** | `http://localhost:3000/distributor/earnings` |
| **Files** | `Earnings.tsx` |

---

### BUG-033 — Low — Non-paged `/products/schemes` used in some views

| **Area** | Frontend |
| **URL** | Transactions, AumBreakdown, InvestorRedeem |
| **Files** | `platizioApi.ts`, `Transactions.tsx`, `InvestorRedeem.tsx` |

---

### BUG-034 — Low — Tax calculator hardcoded (TODO for backend endpoint)

| **Area** | Frontend |
| **URL** | `http://localhost:3000/distributor/calculators` |
| **Files** | `TaxCalculator.tsx:18` |

---

### BUG-035 — Low — Unit test drift (3 failing tests)

| **Area** | Test |
| **Files** | `RequestBodyValidationTest`, `RealCybrillaClientMetricsTest`, `RealCybrillaClientTest` |

---

## Verified OK (not bugs)

| Item | Notes |
|------|--------|
| Investor-action URLs | `buildInvestorActionUrl` → `:8081` correctly |
| No browser → Cybrilla REST | All flows via `/api/v1` |
| Ledger POA catalogue browse | Uses `mf_scheme_plans/cybrillapoa` (not OMS) |
| Dual token audiences | POA + FINPRIM_TENANT with 401 retry |
| Onboarding PAN resume (backend) | DRAFT/ONBOARDING same-distributor duplicate PAN resumes |

---

## Recommended fix order

1. **BUG-001** — Escalate to Cybrilla (tenant `platizio` FP BAV); Platizio: fail fast instead of skip
2. **BUG-005, BUG-006** — Order failure handling + correct HTTP status
3. **BUG-009, BUG-010** — Onboarding persistence + KYC postback
4. **BUG-011, BUG-012** — 502/503 UX on Ledger + Onboarding
5. **BUG-003** — Admin/POA catalogue alignment
6. **BUG-004** — Purchase/payment webhooks
7. **BUG-035** — Fix 3 failing unit tests

---

## Subagent references

| Agent | ID / path | Role in this audit |
|-------|-----------|-------------------|
| supervisor | `.cursor/agents/supervisor.md` | Orchestrator (this registry) |
| achilles | `.cursor/agents/achilles.md` | Implementation hand-off |
| cybrilla-boss | `.cursor/agents/cybrilla-boss.md` | Cybrilla integration facts |
| backend explore | [acdbf13e](acdbf13e-816d-4cd2-bb5a-aef4706c4ec3) | Backend API audit |
| frontend explore | [36e65e5f](36e65e5f-f669-4e36-8453-7e474932dc2e) | Frontend route audit |
| cybrilla audit | [74b2a3ea](74b2a3ea-adda-4584-a732-2f2c9dc0f79b) | Integration gaps |
| shell tests | [e8056da5](e8056da5-37b2-4cba-a44c-f6ac06795cb5) | Maven + smoke tests |

---

## Ops notes

- After backend Cybrilla fixes: **kill process on 8081 once**, then single `.\start-backend.ps1 -Restart`
- Login: `a@a.com` / `Ok@123456`
- Demo Anita: PAN `KRTPX3751K`, bank ending `1193`, payment amount ending `0`
- Sandbox PAN pattern: `XXXPX3751X` (ready) / `XXXPX3753X` (fresh KYC) — see `docs/cybrilla-kyc-test-matrix.csv`
