# Platizio Wealthtech — Platform Bug Report

> Produced by the `supervisor` subagent ([.cursor/agents/supervisor.md](.cursor/agents/supervisor.md)) coordinating three workers: `backend-auditor`, `frontend-auditor`, `integration-auditor`.
> Backend: `http://localhost:8081/api/v1` · Frontend: `http://localhost:3000` · Investor-action pages: `{BACKEND_ORIGIN}/investor-actions/{token}` (served by Spring Boot on 8081, **not** Vite 3000).
>
> **Verification legend:** ✅ verified by supervisor against code/tests · ☑️ reported by a worker with precise `file:line` (high confidence, not independently re-run) · 🔎 suspected (needs runtime repro).
> **Severity:** Critical / High / Medium / Low / UX-gap (backend correct, UI handles it poorly).
> Paths: backend paths are repo-relative; frontend paths are under `platiziowealthtech-Front_End_sample/`.

---

## Executive summary

- **261 backend tests ran → 3 failures** (build is RED). Frontend **`tsc --noEmit` → 1 syntax error** that breaks the production build and the `/distributor/transactions` route.
- **2 broken-access-control (IDOR) defects** let any authenticated distributor read other distributors' investors and orders — the highest-priority items.
- Your reported **409 on investor onboarding is mostly expected backend behavior** (duplicate PAN/email guard) made worse by two real frontend defects (re-POST after refresh, and a fresh-POST when the identity fingerprint changes).
- The integration-auditor's "secrets committed" alarm was **downgraded** — `.env` is gitignored, untracked, and never committed (supervisor verified).

### Fix these first
1. **BUG-002 / BUG-003** — close the two IDOR holes (security).
2. **BUG-004** — fix the `Transactions.tsx` syntax error (build is broken).
3. **BUG-005** — make the backend test build green (3 failing tests).

---

## Summary table

| ID | Sev | Area | Title | Verif |
|----|-----|------|-------|-------|
| BUG-001 | UX-gap (High impact) | FE+BE | 409 on investor onboarding (duplicate PAN/email; re-POST after refresh; fresh-POST on fingerprint change) | ✅ |
| BUG-002 | High | Backend | `GET /investors/by-postal-code/{code}` has no auth scoping → cross-distributor PII leak | ✅ |
| BUG-003 | High | Backend | Order read + redemption endpoints have no ownership check (IDOR) | ✅ |
| BUG-004 | High | Frontend | `Transactions.tsx` JSX syntax error breaks `vite build` and `/distributor/transactions` | ✅ |
| BUG-005 | High | Backend | Test build RED: 261 run, 3 failing (BUG-006 + BUG-007) | ✅ |
| BUG-006 | Low | Backend | `@Valid` missing on `createEsign` & `cancelSipOrder` request bodies | ✅ |
| BUG-007 | Medium | Integration | FP POST URLs carry unexpected query params (`?pan=`, `?primary_investor=`) | ✅ |
| BUG-008 | High | Integration | Order-ready PATCH still sends `occupation` (violates "never PATCH occupation") | ☑️ |
| BUG-009 | High | Integration | No webhook handlers for `mf_purchase` / `payment` / `mandate` events | ☑️ |
| BUG-010 | Medium | Backend | Generic transaction rollbacks masked as `409` with misleading "Investor sync" text | ✅ |
| BUG-011 | Medium | Backend | Lumpsum order amount never validated (null/0/negative accepted) | ☑️ |
| BUG-012 | Medium | Integration | `ensureMfInvestmentAccount` doesn't skip profile PATCH when `invp_`+`mfia_` already linked | ☑️ |
| BUG-013 | Medium | Integration | Catalogue import seeds non-orderable OMS `fund_schemes` rows | ☑️ |
| BUG-014 | Medium | Integration | `.env.example` missing required vars (`JWT_SECRET`, `DB_PASSWORD`, `PAYMENT_POSTBACK_URL`) | ☑️ |
| BUG-015 | Medium | Integration | Webhook secret check non-constant-time + no body signature | ☑️ |
| BUG-016 | Medium | Frontend | PII (email, PAN, DOB, full auth payload) logged to console in prod builds | ☑️ |
| BUG-017 | UX-gap | Frontend | Admins always land on `/distributor/dashboard` after login | ☑️ |
| BUG-018 | Low | Frontend | List fetches coerce non-OK responses to `[]` (can't tell "empty" from "failed") | ☑️ |
| BUG-019 | Low | Frontend | `restoreSession` rejection can keep a stale user "authenticated" on backend outage, no banner | 🔎 |
| BUG-020 | Low | Integration | `external_api_snapshots` store PAN/bank PII unredacted | ☑️ |
| BUG-021 | Low | Integration | `createRedemption` holds a DB transaction across the slow FP pre-flight | ☑️ |
| BUG-022 | Low | Backend | Sandbox demo-data fixups committed as Flyway migrations V41–V51 | ☑️ |

---

## BUG-001 — 409 Conflict on investor onboarding (your reported issue)

| | |
|--|--|
| **Severity** | UX-gap with High user impact (backend guard is correct; the flow dead-ends, and two FE defects cause needless re-POSTs) |
| **URL** | `http://localhost:3000/distributor/investor-onboarding` |
| **API** | `POST /api/v1/investors` → **409 CONFLICT** |
| **Locations** | FE POST `InvestorOnboarding.tsx:1064`; FE final-submit POST `InvestorOnboarding.tsx:1958-1992`; FE reuse decision `InvestorOnboarding.tsx:1925`; FE fetch `src/config/api.ts:154`; BE entry `InvestorController.java:88`; BE logic `InvestorService.java:432`; resume gate `InvestorService.java:1731`; 409 mapping `GlobalExceptionHandler.java:170-175` |

**What happens.** The backend rejects a duplicate. `createInvestor` (`InvestorService.java:432`) looks up the PAN; if a row exists and `canResumeOnboardingDraft` is true it resumes (200), otherwise it throws `DuplicateResourceException("An investor with this PAN already exists")` → 409. A new PAN with an already-used **email** also 409s (`InvestorService.java:459-463`, and on the resume path `assertEmailAvailableForInvestor` at `:1742`).

`canResumeOnboardingDraft` (`InvestorService.java:1731`) returns **false** (→ 409) when the existing investor is under a **different distributor**, has **KYC COMPLETED**, or is **past `ONBOARDING` status**.

**Two real frontend defects amplify it:**
1. The created investor is only kept in React state (`draftInvestor`). The page PUTs to `/investors/{id}` when that state exists (`:1025`) and POSTs only when it's null (`:1064`). After a **refresh / re-navigation** the state is lost, so it **re-POSTs the same PAN** → 409.
2. `shouldReuseDraft = draftInvestor?.id && (isResumeMode || draftIdentityFingerprint === identityFingerprint)` (`:1925`). If the distributor **edits an identity field after POA pre-verification** (fingerprint changes) and isn't in resume mode, final submit takes the **fresh-POST branch** (`:1958`) and creates a *second* investor with the same PAN → 409, orphaning the first draft.

**409 handling is also keyword-dependent** (`:1086-1092`, `:1982-1989`): it only highlights the PAN field when the message contains `"pan"`. An email/mobile conflict shows the raw message with no field highlight and no recovery action. The 409 branch is copy-pasted in three places and has drifted.

**Suggested fix.**
- On a 409 from `POST /investors`, look the investor up (`GET /api/v1/investors/search?query={pan}`) and offer **"Open / resume existing investor"** instead of dead-ending. Persist `draftInvestor.id` (already partly done via PAN-keyed `sessionStorage` at `:349-379`) and **always PUT-update the existing draft when `draftInvestor?.id` exists**, even on fingerprint change — reuse `ensureInvestorDraftForKyc` (`:1018-1056`) instead of the fresh-POST branch.
- Centralize the three 409 handlers into one that maps the conflicting field (pan/email/mobile) and shows a recovery CTA.
- (Optional BE) include the existing investor `id` in the 409 body so the FE can deep-link; today the body is only `ApiErrorResponse` with no id.
- **Immediate unblock (sandbox):** use a fresh simulator PAN (`CCCPC3753C` / `DDDPD3753D`) or open the existing investor from `/distributor/investors`.

**Found by:** supervisor (verified) + frontend-auditor (#2, #3).

---

## BUG-002 — `GET /investors/by-postal-code/{postalCode}` leaks investors across distributors ✅

| | |
|--|--|
| **Severity** | High (broken access control / PII) |
| **API** | `GET /api/v1/investors/by-postal-code/{postalCode}` |
| **Location** | `InvestorController.java:568-571`; service `InvestorService.findByPostalCode` |

**What happens.** This endpoint takes **no `Authentication`, no `@PreAuthorize`, and no `actorId`** — unlike every other `InvestorController` method, which threads `actorId(auth)` into ownership checks. Any authenticated distributor can pass any postal code and receive full `Investor` entities (PAN, email, mobile, DOB, address, KYC status) belonging to **other distributors'** clients. The query also has no `is_deleted = false` filter.

**Suggested fix.** Add `Authentication auth`, pass `actorId(auth)`, and scope results with `listVisibleToDistributor`/role logic — or restrict to `@PreAuthorize("hasRole('ADMIN')")` if it's an admin tool. Add the soft-delete filter. No test covers this endpoint today.

**Found by:** backend-auditor; **supervisor verified** the controller signature directly.

---

## BUG-003 — Order read & redemption endpoints have no ownership check (IDOR) ✅

| | |
|--|--|
| **Severity** | High (broken access control) |
| **API** | `GET /api/v1/orders/{orderId}`, `GET /orders/by-investor/{investorId}`, `GET /orders/by-distributor/{distributorId}`, `GET /orders/{orderId}/redemptions`, `POST /orders/{orderId}/sync-provider-status`, `POST /orders/{orderId}/redemption` |
| **Location** | `OrderController.java:98-121` (read paths), `:134-137` (`createRedemption`) |

**What happens.** `getOrder` (`:99`), `listByInvestor` (`:109`), `listByDistributor` (`:114`), `listRedemptions` (`:119`) and `syncProviderStatus` (`:104`) take **no `Authentication` parameter at all** — any logged-in distributor can read any order, any investor's order history, and **any other distributor's entire order list**. `createRedemption` (`:135`) passes `actorId` but (per backend-auditor) only uses it for the audit log, not an ownership gate — so a distributor may be able to **submit a redemption against another distributor's order**. Contrast with `cancelSipOrder`/`deleteOrder`, which correctly check `actorId.equals(order.getDistributorId())`, and `listOrders` (`GET /orders`), which scopes non-admins via a Specification.

**Suggested fix.** Thread the authenticated principal (id + role) into each of these service methods and reject when the resource owner isn't the requester (admins bypass), mirroring `cancelSipOrder`. `createRedemption` must enforce ownership **before** calling Cybrilla. Add authorization tests — `OrderControllerTest` currently covers only create paths.

**Found by:** backend-auditor; **supervisor verified** the controller signatures directly.

---

## BUG-004 — `Transactions.tsx` JSX syntax error breaks the build and the route ✅

| | |
|--|--|
| **Severity** | High (production build fails; route is broken) |
| **URL** | `http://localhost:3000/distributor/transactions` |
| **Location** | `src/views/Transactions.tsx:106` (missing `}`) |

**What happens.** `npx tsc --noEmit` reports `Transactions.tsx(107,9): error TS1005: '}' expected.` The `{steps.map((step, index) => { … })}` block opened at `:66` is missing its closing `}`: line `:106` is `})` and line `:107` is `</>`, but the JSX expression container needs `})}` before the fragment closes. This fails `vite build` (any TS error fails the build) and breaks the lazily-loaded `/distributor/transactions` route at runtime.

**Suggested fix.** Change `Transactions.tsx:106` from `      })` to `      })}`. Then re-run `tsc --noEmit` (a parse error suppresses later semantic checks, so re-check for additional errors after fixing).

**Found by:** supervisor (ran the typecheck the frontend-auditor's sandbox blocked).

---

## BUG-005 — Backend test build is RED (3 failing tests) ✅

**Command:** `.\mvnw.cmd -B test` → **Tests run: 261, Failures: 3, Errors: 0, Skipped: 0 → BUILD FAILURE.**

The 3 failures are tracked as BUG-006 and BUG-007 below. All other 258 tests pass. (Note: the harness blocked the worker subagents from running Maven; the supervisor ran it.)

### BUG-006 — `@Valid` missing on two controller request bodies ✅
- **Severity:** Low (validation not enforced on those bodies)
- **Test:** `RequestBodyValidationTest.allControllerRequestBodiesUseValid:37` → `Expecting empty but was: ["InvestorController.createEsign", "OrderController.cancelSipOrder"]`
- **Location:** `InvestorController.java:469-476` (`createEsign`, `@RequestBody(required=false) EsignStartRequest`), `OrderController.java:139-146` (`cancelSipOrder`, `@RequestBody(required=false) SipCancelRequest`)
- **Fix:** add `@Valid` to both `@RequestBody` params so their bean-validation constraints are enforced (and the architecture test goes green).

### BUG-007 — FP POST URLs carry unexpected query params ✅
- **Severity:** Medium (build red + possible real provider-call regression)
- **Tests:** `RealCybrillaClientMetricsTest:46` — POST `/v2/investor_profiles` expected without query but was `…?pan=ABCDE1234F`; `RealCybrillaClientTest.createMfInvestmentAccountPostsProfileAndHoldingPattern:665` — POST `/v2/mf_investment_accounts` expected without query but was `…?primary_investor=profile-1`.
- **What happens.** Recent code appends `?pan=` / `?primary_investor=` to these **POST** request URIs. Either the change is wrong (FP POST endpoints don't expect those query params and may ignore/reject them) or the tests are stale and must be updated. Until resolved the build stays red.
- **Fix:** confirm against FP docs whether these POSTs should carry query params. If not, build the URI without them; if yes, update the two tests. (`integration-auditor` should advise on the correct FP contract — see also its order-ready PATCH note in BUG-008.)

**Found by:** supervisor (ran the suite).

---

## BUG-008 — Order-ready PATCH still sends `occupation` ☑️

| | |
|--|--|
| **Severity** | High (recurring `occupation is already set and cannot be modified`) |
| **API** | order path → FP `PATCH /v2/investor_profiles` |
| **Location** | `RealCybrillaClient.java:2479-2481` (`investorProfileOrderReadyPayload`); retry at `:2522-2541`; hard-fail guard `:2488` |

The order-ready payload adds `occupation` whenever FP's GET omits it — but FP commonly hides `occupation` on GET while still storing it, so this re-adds an immutable field and FP rejects it. The documented rule (`fp-profile-patch-rules.md`) is that the order-ready PATCH must **never** contain `occupation` (set only on POST create, `:2445`). A catch-and-retry (`:2522-2541`) currently absorbs the 400, so orders still succeed — but at the cost of an extra failed round-trip per order, and `assertExistingProfileSupportsOrderSubmission` (`:2488`) can hard-fail a profile whose occupation is set-but-hidden. **Fix:** drop the `occupation` branch from `investorProfileOrderReadyPayload`; keep the retry as defense-in-depth.

**Found by:** integration-auditor.

---

## BUG-009 — No webhook handlers for `mf_purchase` / `payment` / `mandate` ☑️

| | |
|--|--|
| **Severity** | High (order/payment/mandate state reconciled only by polling + postback) |
| **API** | `POST /api/v1/cybrilla/webhooks` ← FP `mf_purchase.*` / `payment.*` / `mandate.*` |
| **Location** | `CybrillaWebhookController.java:45-61` (routes only KYC-form / pre-verification(bank) / KYC; everything else → `ignored_unsupported_event`) |

Order, payment, and mandate webhooks fall through and are dropped; only polling/postback reconcile those states (matches the open item in `pending-work.md`). The KYC and bank webhooks that *do* exist are idempotent (they re-fetch authoritative provider state), so the fix should mirror that pattern. **Fix:** add `isMfPurchaseEvent`/`isPaymentEvent`/`isMandateEvent` routing that re-fetches the entity by id and applies authoritative state idempotently.

**Found by:** integration-auditor.

---

## BUG-010 — Transaction rollbacks masked as misleading `409` ✅

| | |
|--|--|
| **Severity** | Medium (wrong status + irrelevant message on any endpoint) |
| **Location** | `GlobalExceptionHandler.java:195-208` (`handleTransactionFailure`), same wording at `:210-222` (`handlePersistence`) |

Any `UnexpectedRollbackException`/`TransactionSystemException` from **any** endpoint (order save, lead update, constraint failure at flush) is converted to HTTP 409 with the body *"Investor sync could not complete because one or more Finprim profiles failed to save…"* — hiding the real cause and returning a semantically wrong status with an investor-sync-specific message on unrelated endpoints. **Fix:** inspect the cause chain for a known constraint (as `handleDataIntegrity` does) and only emit the investor-sync text when it actually applies; otherwise a generic 400/500.

**Found by:** backend-auditor; **supervisor verified** the handler.

---

## BUG-011 — Lumpsum order amount never validated ☑️

| | |
|--|--|
| **Severity** | Medium |
| **API** | `POST /api/v1/orders`, `POST /api/v1/orders/bulk` |
| **Location** | `dto/OrderCreateRequest.java:14` (no constraint on `amount`); `OrderService.java:672-692` (amount checked only in the SIP branch) |

For `LUMPSUM_PURCHASE`, `amount` has no bean-validation and `validateSipRequest` returns early for non-SIP types, so `amount=null/0/negative` is persisted (`transaction_orders.amount` is nullable) and pushed toward the provider. **Fix:** require a positive `amount` for lumpsum before persistence (service guard and/or class-level DTO validation).

**Found by:** backend-auditor.

---

## BUG-012 — `ensureMfInvestmentAccount` doesn't skip prep when already linked ☑️

| | |
|--|--|
| **Severity** | Medium (extra FP PATCH round-trips on every order/redemption; feeds BUG-008) |
| **Location** | `InvestorService.java:1344-1346, 1382-1389, 1425-1428` |

`mfAccountLinkedAtEntry` is computed at entry but only used for a log line; the method then unconditionally calls `ensureInvestorProfileOrderReady` + `ensureMfInvestmentAccountOrderReady` on every purchase and redemption. The documented rule is to **return early without PATCH** when `invp_`+`mfia_` are already linked and bank is verified. **Fix:** short-circuit when `mfAccountLinkedAtEntry` is true, logging `skipped_already_linked`.

**Found by:** integration-auditor.

---

## BUG-013 — Catalogue import seeds non-orderable OMS rows ☑️

| | |
|--|--|
| **Severity** | Medium (latent / data hygiene — live orders are still guarded) |
| **API** | `POST /api/v1/products/schemes/sync` → FP `GET /api/oms/fund_schemes` (should be `/v2/mf_scheme_plans/cybrillapoa`) |
| **Location** | `RealCybrillaClient.java:916-924` (`fetchProductSchemes`); `ProductService.java:202` (`refreshFromCybrilla`) |

The import path persists OMS MF rows that can never be ordered (correctly rejected at order time by `ProductSchemeOrderSupport.requirePoaOrderable`, `:60-64`), and does not import POA-orderable plans. Live Ledger browse already uses the POA endpoint, so end users normally see orderable rows — this is latent. **Fix:** source MF rows from `mf_scheme_plans/cybrillapoa`, or exclude OMS rows from the orderable catalogue and label them reference-only.

**Found by:** integration-auditor.

---

## BUG-014 — `.env.example` missing required vars ☑️

| | |
|--|--|
| **Severity** | Medium (a fresh setup from `.env.example` cannot boot) |
| **Location** | `.env.example` vs `src/main/resources/application.yml` |

Missing: `JWT_SECRET` (`application.yml:144`, no default — app won't start), `DB_PASSWORD` (`:11`, no default), `PAYMENT_POSTBACK_URL` (`:149`, used by `InvestorActionService`). `CYBRILLA_WEBHOOK_SECRET` is present but empty (required outside the `local` profile — see BUG-015). Several default-valued vars are also undocumented. **Fix:** add the three required vars (plus the documented-default ones) to `.env.example`.

**Found by:** integration-auditor.

---

## BUG-015 — Webhook secret check is weak ☑️

| | |
|--|--|
| **Severity** | Medium |
| **API** | `POST /api/v1/cybrilla/webhooks` (`permitAll` in `SecurityConfig.java:67`) |
| **Location** | `CybrillaWebhookController.java:63-76` |

`webhookSecret.equals(receivedSecret)` is timing-variable; the mechanism is a static shared secret in a header, not an HMAC signature over the body, so it can't detect tampering/replay; and it's skipped entirely when unset on the `local` profile. Partially mitigated because handlers re-fetch authoritative state. **Fix:** use `MessageDigest.isEqual` (constant-time); if FP provides an HMAC header, verify it over the raw body with replay protection.

**Found by:** integration-auditor.

---

## BUG-016 — PII logged to console in production builds ☑️

| | |
|--|--|
| **Severity** | Medium (Vite does not strip `console.*`) |
| **URL** | `/login`, `/distributor/investor-onboarding` |
| **Location** | `LoginPage.tsx:131-134, 157-161, 178-182, 200, 296`; `InvestorOnboarding.tsx:1006-1012, 1063, 1154-1161, 1915` |

Login/OTP flows log the user email and the **full auth response**; onboarding logs investor PAN, name, DOB, mobile. **Fix:** gate PII logs behind `import.meta.env.DEV`, or add `esbuild: { drop: ['console'] }` for production builds; never log the raw `/auth/login` payload.

**Found by:** frontend-auditor.

---

## BUG-017 — Admins always land on the distributor dashboard ☑️ (UX-gap)

`App.tsx:122-133` (`handleLoginSuccess`) sends every user to `/distributor/dashboard` with no role branch; admins must manually type `/admin/overview`. Not a security hole (admin routes are guarded by `canAccessAdmin`, `App.tsx:203`). **Fix:** branch on role — admins → `/admin/overview`. **Found by:** frontend-auditor.

## BUG-018 — List fetches hide load failures as empty lists ☑️

`Communications.tsx:46-47` (`r.ok ? r.json() : []`), `AdminOverview.tsx:358-362`, `DistributorMgmt.tsx:182-183` coerce a 401/403/500 to `[]`, so the UI can't distinguish "no data" from "load failed." **Fix:** track per-request failure and render an error state (reuse `EmptyState.tsx`; `InvestorRedeem`/`Portfolio` do this correctly). URLs: `/distributor/communications`, `/admin/overview`, `/admin/distributor-mgmt`. **Found by:** frontend-auditor.

## BUG-019 — Stale "authenticated" state on backend outage 🔎

`authSlice.ts:160-167` + `App.tsx:84-97`: if `/auth/me` throws during a warm reload (backend down), the thunk rejects but a prior user object keeps `status='authenticated'`, so the app renders against a down backend with no "backend unreachable" banner and mixed per-view errors. **Fix:** on restore rejection, show a non-blocking degraded-mode banner. **Found by:** frontend-auditor (suspected).

## BUG-020 — Provider snapshots store PII unredacted ☑️

`ExternalApiSnapshotService.java:42-43, 87-97` serializes full FP/POA request/response JSON (PAN, bank account numbers) into `external_api_snapshots`. **No bearer token or `client_secret` is captured** (not a credential leak), but PII is plaintext. **Fix:** mask `pan`, `account_number`, Aadhaar before persisting. **Found by:** integration-auditor.

## BUG-021 — `createRedemption` holds a DB transaction across the FP pre-flight ☑️

`OrderService.java:506-530`: `@Transactional` (`:506`) wraps `ensureMfInvestmentAccount` (`:515`), a 30–90s provider round-trip — whereas `createOrder` is deliberately non-transactional for exactly this reason (`:224-228`). **Fix:** move the provider pre-flight outside the transaction, mirroring `createOrder`. **Found by:** integration-auditor.

## BUG-022 — Sandbox demo-data fixups committed as Flyway migrations ☑️

`src/main/resources/db/migration/V41…V51` hardcode one sandbox investor's PAN/email/bank and reset FP linkage. They're idempotent and scoped to a fixed UUID (no-op on a clean DB, not destructive) but pollute the schema-migration history. **Fix:** move sandbox fixups into a dev-only seeder (`DemoDataSeeder`/`DataInitializer`) or a `local`-profiled callback; keep `db/migration` for schema only. **Found by:** backend-auditor.

---

## Verification performed

| Check | Result |
|-------|--------|
| Backend `.\mvnw.cmd -B test` | ✅ ran — **261 tests, 3 failures** (BUG-006, BUG-007), 0 errors |
| Frontend `tsc --noEmit` | ✅ ran — **1 syntax error** (BUG-004) |
| 409 onboarding path | ✅ traced end-to-end across controller→service→resume-gate→handler + FE POST/PUT/409 handlers |
| BUG-002 / BUG-003 (IDOR) | ✅ confirmed missing `Authentication`/scoping directly in `InvestorController.java:568`, `OrderController.java:98-137` |
| `.env` secret exposure (integration F5) | ✅ **downgraded** — `git ls-files .env` empty, no history, gitignored; secrets are local-only |

## Expected behavior / not-a-bug appendix

- A 409 on a **genuinely duplicate, fully-onboarded** PAN/email is **correct** (BUG-001 is about the FE dead-ending + needless re-POSTs, not the guard).
- **`.env` secrets are not a committed-secret incident** — the file is gitignored, untracked, and absent from history (supervisor verified). Rotate the keys only if that local `.env` was shared outside the team. Keep `.env.example` with empty values.
- Investor-action/payment pages failing to load on `localhost:3000` is **expected** — they're served by Spring Boot on **8081**.
- Verified-correct and intentionally excluded: two-audience token caches + single-401-retry, prefixed/validated provider IDs, frontend-only-calls-backend (no provider calls/secrets in React), investor-action links using `BACKEND_ORIGIN`, HTTP verb/path parity between FE calls and BE controllers, single-flight auth refresh, double-submit guards on order/redeem/onboarding, KYC/bank webhook idempotency.

---

## 2026-06-15 — Re-audit & consolidation (post-WIP)

> Re-audited against the **current working tree** (~9,654 uncommitted line changes since the original report), by 4 parallel audit agents + a full `mvnw -B test` run.
> **Build is now GREEN: 261 tests, 0 failures, 0 errors** (after fixing a new build-breaker, BUG-023).
> The React frontend (`platiziowealthtech-Front_End_sample/`) is **NOT present in this repo** → all frontend bugs are document-only / out-of-scope to fix here.
> This section merges `docs/bugs.md` into this (canonical) registry. Original IDs above are preserved.

### Status reconciliation — original BUG-001…022

| ID | Status now | Evidence (current code) |
|----|-----------|--------------------------|
| BUG-001 | OPEN (backend half) | FE defects out of scope. Backend: 409 body still lacks existing investor id — `InvestorService.java:455`, `ApiErrorResponse`, `GlobalExceptionHandler.java:170-175`. Resume gate verified correct `InvestorService.java:1766-1775`. |
| BUG-002 | ✅ FIXED (WIP) | `InvestorController.java:574-578` threads auth; `InvestorService.findByPostalCode` scopes by role/distributor; soft-delete filtered. |
| BUG-003 | ✅ FIXED (WIP) | Order read/redemption endpoints take `Authentication`; `OrderService.assertOrderOwnership` `:249-255`; `createRedemption(UUID,JwtAuthPrincipal)` checks before Cybrilla `:602-606`. |
| BUG-004 | ⛔ FRONTEND (out of scope) | No FE in repo. |
| BUG-005 | ✅ RESOLVED | Original 3 failing tests gone; new build-breaker found+fixed → BUG-023. |
| BUG-006 | ✅ FIXED (WIP) | `@Valid` on `createEsign` `InvestorController.java:472` + `cancelSipOrder` `OrderController.java:142`. |
| BUG-007 | ✅ NOT-A-BUG | `?pan=`/`?primary_investor=` only on GET list calls (correct FP usage); POST creates send bare path+body `RealCybrillaClient.java:142,335`. |
| BUG-008 | ✅ FIXED (WIP) | Order-ready PATCH omits `occupation` `RealCybrillaClient.java:2450-2484` (comment `:2479`). |
| BUG-009 | OPEN | No mf_purchase/payment/mandate webhooks — `CybrillaWebhookController.java:45-61`, `InvestorKycService.java:755-773`. |
| BUG-010 | OPEN | Rollback/persistence → misleading investor-sync 409 — `GlobalExceptionHandler.java:195-222`. |
| BUG-011 | OPEN (HIGH) | Lumpsum `amount` unvalidated — `OrderCreateRequest.java:14`; `OrderService.validateSipRequest` early-returns for non-SIP `:750-751`. |
| BUG-012 | OPEN | `ensureMfInvestmentAccount` never short-circuits when linked — `InvestorService.java:1382-1428`. |
| BUG-013 | OPEN | Catalogue seeds OMS `fund_schemes` not POA — `RealCybrillaClient.java:916-924`. |
| BUG-014 | OPEN | `.env.example` missing `JWT_SECRET`/`DB_PASSWORD`/`PAYMENT_POSTBACK_URL`. |
| BUG-015 | OPEN | Webhook secret `String.equals` non-constant-time, no body signature, skipped on local — `CybrillaWebhookController.java:65-76`. |
| BUG-016..019 | ⛔ FRONTEND (out of scope) | No FE in repo. |
| BUG-020 | OPEN | `ExternalApiSnapshotService.java:42-43,87-97` stores PAN/bank PII unredacted. |
| BUG-021 | OPEN | `createRedemption` holds `@Transactional` across FP pre-flight — `OrderService.java:571-607`. |
| BUG-022 | OPEN | Demo-data fixups as Flyway migrations V41–V51. |

### New bugs found in re-audit (verified against current code)

| ID | Sev | Area | Title | Status |
|----|-----|------|-------|--------|
| BUG-023 | High | Test/Build | `AuditActorControllerTest.orderAuditEndpointsUseAuthenticatedActor` NPE (stale stub after BUG-003 ownership change) | ✅ FIXED (this session) |
| BUG-024 | High | Backend | FP `CybrillaApiException` wrapped as `IllegalStateException` → HTTP 400 instead of 502 (`InvestorService.java:1379,1433`) | OPEN |
| BUG-025 | Med | Backend | Orphan `CREATED` orders: plain `CybrillaApiException` after local save leaves row, null action URL (`OrderService.java:294,327,346-360`) | OPEN |
| BUG-026 | Med | Backend | `InvestorActionController` maps `CybrillaUnavailableException` → 502 not 503 (`:55,89,103,119,133`) | OPEN |
| BUG-027 | Med | Backend | Hardcoded `http://localhost:3000` in FAILED-order HTML (`InvestorActionController.java:446`) | OPEN |
| BUG-028 | High | Security | `CybrillaDirectController` raw FP/POA proxy reachable by any authenticated user (`:19-62`, `SecurityConfig.java:73`) | OPEN |
| BUG-029 | Med | Backend+External | FP tenant BAV silently skipped ("Tenant platizio is not configured") instead of fail-fast (`RealCybrillaClient.java:419-430,2391-2401`) | OPEN |
| BUG-030 | Med | Ops/Backend | `DemoOrderAdvancer` auto-completes real FP orders on `local` profile | OPEN |
| BUG-031 | Med | Backend | Soft-deleted PAN/email duplicate → raw `DataIntegrityViolation` 409; archived draft can't resume (`InvestorService.java:440,460`) | OPEN |
| BUG-032 | Low | Backend | `createInvestor` trusts `distributorId` from payload, not principal (`InvestorController.java:88`) | OPEN |
| BUG-033 | Low | Backend | `mobileNumber` only `@NotBlank` (no pattern/length) (`InvestorCreateRequest.java:15`) | OPEN |
| BUG-034 | Low | Backend | `InvestorActionController.paymentComplete` lacks `IllegalStateException` catch (`:88-90`) | OPEN |
| BUG-035 | Low | Backend | `deleteOrder` calls no-principal `getOrder` (live FP sync) before actor check (`OrderController.java:153-160`) | OPEN |
| BUG-036 | Low | Backend | Public `createRedemption(UUID,UUID)` still callable without ownership check (`OrderService.java:572`) | OPEN |
| BUG-037 | Low | Backend | BAV poll returns pending on `InterruptedException` → false hard-failure (`RealCybrillaClient.java:456-471`) | OPEN |
| BUG-038 | Low | Backend | `CybrillaDirectService.fetchPreVerification` (POA `pv_`) routed to FP KYC-check surface (`:44-46`) | ✅ NOT-A-BUG — `fetchKycCheck` already hits `/poa/pre_verifications/:id` (POA token); only the method name is misleading |

### Carried from `docs/bugs.md` (prior audit; not re-verified post-WIP)

| ID | Sev | Area | Title |
|----|-----|------|-------|
| BUG-039 | Med | Backend | Local `bank_verification_status=VERIFIED` accepted without settled POA BAV |
| BUG-040 | Low | Backend | Exception handler may mislabel FP tenant-config error as POA credential error |
| BUG-041 | Low | Backend | Corrupt BAV JSON → POA refresh skipped (false-negative) |
| BUG-042 | Med | Backend | FP KYC compliance check skipped on 404 (fails open) |
| BUG-043 | Low | Data | `.local` demo emails skipped for FP folio defaults |
| BUG-044 | Low | Ops | Payment postback URL unreachable from FP in dev |
| BUG-045 | Low | Backend | Transaction-eligible search ignores `externalSyncPending` |
| BUG-046 | Low | Backend | Async lumpsum review poll swallows transient FP fetch failures |

> **Frontend-only (document-only; React app not in this repo):** root BUG-004/016/017/018/019; docs/bugs.md BUG-009/010/011/012/022/023/024/025/032/033/034.

---

## 2026-06-15 — Fix session outcome (FINAL STATUS — supersedes OPEN markers above)

Backend functionality fixed this session, each verified by `mvnw -B test`. Final suite: **281 tests, 0 failures, 0 errors (GREEN)**. All changes uncommitted (per user).

**✅ Fixed & verified (functionality):** BUG-023 (build-breaker), BUG-001, BUG-009 (mf_purchase/payment), BUG-010, BUG-011, BUG-012, BUG-013, BUG-014, BUG-021, BUG-024, BUG-025, BUG-026, BUG-027, BUG-031, BUG-034, BUG-037.
**✅ Verified NOT-a-bug:** BUG-007, BUG-038.
**✅ Already fixed by WIP:** BUG-002, BUG-003, BUG-005, BUG-006, BUG-008.
**⏸ Deferred — security/auth (per user, do after functionality):** BUG-015, BUG-020, BUG-028, BUG-032, BUG-035, BUG-036.
**◻ Not done (functionality backlog):** BUG-029 (FP tenant fail-fast — needs Cybrilla tenant enablement), BUG-030 (DemoOrderAdvancer gating), BUG-022 (demo-data migrations → seeder), BUG-033 (mobile `@Pattern`), and **BUG-047** (new, below).
**◻ Carried, not re-verified:** BUG-039–046. **⛔ Frontend (out of scope, no React in repo):** BUG-004/016/017/018/019 (+ docs FE bugs).

### New follow-up logged

| ID | Sev | Area | Title | Status |
|----|-----|------|-------|--------|
| BUG-047 | Low | Backend | `mandate.*` webhooks acknowledged but not reconciled — FP mandate state is keyed by an `int` mandate id (`fetchMandate(int)`), not the order `externalOrderId`, so it needs a mandate-id→SIP/order reconcile path (BUG-009 handles `mf_purchase`/`payment`; `mandate.*` returns `acknowledged_no_reconcile`) | OPEN |
