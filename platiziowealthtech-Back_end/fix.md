# Fix Plan & Implementation — Platizio Wealthtech Bugs

> Active **backend** fix plan, reconciled against the current working tree on 2026-06-15.
> Pairs with `bugs.md` (registry) and `history.md` (audit trail).
> Scope decision (user): **fix functionality first; defer security/auth.** Frontend not in repo → FE bugs out-of-scope. Changes left **uncommitted**.
> Build baseline: `mvnw -B test` → **270 tests, 0 failures, 0 errors (GREEN)**.

## Status legend
`[ ]` Not started · `[~]` In progress · `[x]` Fixed (awaiting verify) · `[✓]` Verified (build/test green) · `[—]` Deferred / out of scope

---

## Already resolved by current WIP (verified this session)
- `[✓]` BUG-002 — postal-code IDOR (auth scoping added)
- `[✓]` BUG-003 — order read/redemption IDOR (ownership checks added)
- `[✓]` BUG-006 — `@Valid` on `createEsign` + `cancelSipOrder`
- `[✓]` BUG-007 — FP POST query-param concern (not-a-bug; only on GET)
- `[✓]` BUG-008 — order-ready PATCH no longer sends `occupation`
- `[✓]` BUG-005 — original 3 failing tests resolved

## P0 — Build (DONE)
- `[✓]` **BUG-023** — `AuditActorControllerTest` NPE build-breaker. Stub now overrides `createRedemption(UUID, JwtAuthPrincipal)`. Verified 261→270/0/0.

## ✅ Wave 1 — Functionality fixes (DONE, verified `mvnw test` 270/0/0)
- `[✓]` **BUG-001** (409 body) — `DuplicateResourceException` carries existing id + `conflictField`; `GlobalExceptionHandler` returns a `ConflictErrorResponse` (409 with `resourceId`) when present; shared `ApiErrorResponse` untouched. Files: `common/DuplicateResourceException.java`, `controller/GlobalExceptionHandler.java`, `service/InvestorService.java`.
- `[✓]` **BUG-011** (lumpsum amount) — `OrderService.validateLumpsumRequest` rejects `LUMPSUM_PURCHASE` with null/≤0 amount before persistence (covers bulk via `createOrder`). File: `service/OrderService.java`.
- `[✓]` **BUG-024** (FP→502) — `ensureMfInvestmentAccount` rethrows `CybrillaApiException` (→502; `CybrillaUnavailableException`→503) instead of wrapping as `IllegalStateException` (→400). File: `service/InvestorService.java`.
- `[✓]` **BUG-025** (orphan orders) — `createOrder` catches `CybrillaApiException` post-save, marks order `FAILED` + failureReason, nulls action URL, rethrows. File: `service/OrderService.java`.
- `[✓]` **BUG-026 / BUG-027** — all 5 `InvestorActionController` handlers catch `CybrillaUnavailableException`→503 before `CybrillaApiException`→502; FAILED-order HTML uses `app.frontend.origin` (default `localhost:3000`). File: `controller/InvestorActionController.java`.
- `[✓]` **BUG-034** (`paymentComplete`) — added `IllegalStateException`→400 catch matching sibling handlers. File: `controller/InvestorActionController.java`.

## P2 — Functionality fixes remaining (next)
### BUG-010 — Rollback/persistence masked as misleading 409
- **File:** `controller/GlobalExceptionHandler.java` (`handleTransactionFailure` / `handlePersistence`)
- **Fix:** handlers return `ResponseEntity`; investor-sync 409 only when the cause chain contains `finprim`/`restore from cybrilla` (or a known constraint); generic rollback/persistence → neutral 500.
- **Status:** `[✓]` Verified — `mvnw test` 277/0/0.
### BUG-031 — Soft-deleted PAN/email duplicate → clean resumable 409
- **File:** `service/InvestorService.java` (dup pre-check), repository
- **Fix:** added `findAnyIdByPanIncludingDeleted` / `...EmailIncludingDeleted` native lookups; `createInvestor` throws a friendly `DuplicateResourceException` (carrying the archived id) when a soft-deleted row collides.
- **Status:** `[✓]` Verified — `mvnw test` 277/0/0.
### BUG-037 — BAV poll returns pending on `InterruptedException` (false hard-failure)
- **File:** `integration/RealCybrillaClient.java` (`pollFpBankAccountVerificationUntilSettled`)
- **Fix:** on interrupt, restore the interrupt flag and throw `CybrillaUnavailableException` (retryable, →503) instead of returning a pending node.
- **Status:** `[✓]` Verified — `mvnw test` 277/0/0.
### BUG-033 — `mobileNumber` weak validation
- **File:** `dto/InvestorCreateRequest.java`
- **Fix:** add a 10-digit `@Pattern` (verify demo/seed numbers match first to avoid breaking onboarding). Low priority.
- **Status:** `[ ]`

## P3 — Integration / architecture (functional; guided by cybrilla-boss + achilles docs)
### BUG-012 — `ensureMfInvestmentAccount` early-return when already linked
- **Rule (fp-profile-patch-rules.md):** skip the order-ready PATCH entirely when `invp_`+`mfia_` are already linked at entry; log `skipped_already_linked`.
- **Fix:** guarded the two order-ready PATCH calls behind `if (!mfAccountLinkedAtEntry)`; preserved the bank-verification sync; logs `status='skipped_already_linked'`.
- **Status:** `[✓]` Verified — `mvnw test` 278/0/0.
### BUG-021 — `createRedemption` holds DB txn across FP pre-flight
- **File:** `service/OrderService.java` — removed `@Transactional` from `createRedemption(UUID,UUID)` (single save; no atomicity need), mirroring `createOrder`; FP pre-flight no longer pins a DB connection.
- **Status:** `[✓]` Verified — `mvnw test` 281/0/0.
### BUG-038 — POA `pre_verification` (`pv_`) routed to FP `kyc_check` surface
- **Rule (references/pre-verifications.md):** POA `pv_` → `GET /poa/pre_verifications/:id` (POA token), distinct from FP `kyc_check`.
- **File:** `service/CybrillaDirectService.java:44-46`
- **Status:** `[—]` NOT-A-BUG — verified `cybrillaClient.fetchKycCheck` already routes to `GET /poa/pre_verifications/:id` with the POA token (`RealCybrillaClient.java:721-725`). The method name is misleading but the surface is correct.
### BUG-009 — Webhook handlers for `mf_purchase` / `payment` / `mandate` (review before change)
- **Rule:** mirror existing KYC/bank handlers — match local entity by stored FP external id, GET authoritative provider state, apply idempotently; keep polling fallback.
- **Fix:** `mf_purchase.*`/`payment.*` → `OrderService.handleOrderWebhook` (reuses idempotent `syncLumpsumOrderFromProvider`); unknown id → `ignored_no_matching_order`; added `findByExternalOrderId`. `mandate.*` → `acknowledged_no_reconcile` (follow-up **BUG-047**). Webhook secret untouched (security-deferred BUG-015).
- **Status:** `[✓]` (mf_purchase/payment) Verified — `mvnw test` 281/0/0.
### BUG-013 — Catalogue seeds non-orderable OMS rows (review before change)
- **Finding:** REAL bug — the `product-catalogue-endpoint` flag (default `poa-mf`) was honored by live-browse but IGNORED by the admin sync (`fetchProductSchemes()` hardcoded OMS).
- **Fix:** `fetchProductSchemes()` now honors the flag; default routes to POA `mf_scheme_plans/cybrillapoa`; the 2 `RealCybrillaClientTest` cases updated to the POA contract.
- **Status:** `[✓]` Verified — `mvnw test` 281/0/0.

## P4 — Config / data hygiene
- `[✓]` BUG-014 — added `JWT_SECRET`/`DB_PASSWORD`/`PAYMENT_POSTBACK_URL` to `.env.example`
- `[ ]` BUG-022 — move V41–V51 demo fixups to a dev-only seeder (migration-history change → review)
- `[ ]` BUG-030 — gate `DemoOrderAdvancer` to demo-stub orders only
- `[ ]` BUG-029 — fail-fast on FP tenant-not-configured (also needs Cybrilla tenant enablement)

## Deferred — security / auth (per user: handle after functionality)
- `[—]` BUG-028 — restrict `CybrillaDirectController` proxy to ADMIN
- `[—]` BUG-015 — constant-time webhook secret + body signature
- `[—]` BUG-020 — mask PAN/bank PII in `ExternalApiSnapshotService`
- `[—]` BUG-032 — derive `distributorId` from principal (not payload)
- `[—]` BUG-035 — `deleteOrder` ownership check before live FP sync
- `[—]` BUG-036 — hide unchecked `createRedemption(UUID,UUID)` overload

## Out of scope here / external
- `[—]` Frontend: BUG-004/016/017/018/019 (+ docs FE bugs) — no React app in this repo
- `[—]` External/Ops: BUG-044 postback tunnel; BUG-029 tenant config (Cybrilla side)
- Backlog (from `docs/bugs.md`, not re-verified post-WIP): BUG-039/040/041/042/043/045/046

---

## Feature batch (post-bugfix, per user) — payments / SIP / redemption (doc-driven)

> Decisions locked by user: in-place SIP edit if FP allows; analyse docs + diagnose payment then make success honest; every (real POA) holding redeemable; follow FP docs. See `history.md` for the doc analysis + payment-failure diagnosis.

- `[✓]` **SIP in-place edit** — `PATCH /v2/mf_purchase_plans` (amount + installment_day). Backend `OrderService.updateSipPlan` + `PATCH /orders/{id}/sip` + `SipUpdateRequest`; FE `SipDashboard` Edit modal. Verified backend 283/0/0 + FE vite build.
- `[ ]` **Payment honesty + retry** — gate `DemoOrderAdvancer` (BUG-030) so PAYMENT_PENDING no longer auto-advances to SUCCESSFUL; make sandbox "Simulate Payment" the demo driver; add FP-documented **payment-retry** (new payment attempt on failure, no re-confirm). Diagnosis: real payment isn't code-broken — only postback/finalization (localhost postback unreachable) + the timer mask; hard-fails are upstream Cybrilla tenant config (BUG-029).
- `[ ]` **Redemption** — complete FP lifecycle (consent OTP → confirm → poll), surface it (sidebar/per-holding), and produce a real redeemable holding (demo placeholder `fp_purchase_demo_001` is not a real POA folio → not redeemable). FP redemption is folio-level, gated by `redeemable_units>0`, POA-folios only.
- `[ ]` **Integration gaps** — portfolio holdings refresh after SUCCESSFUL, sync-from-cybrilla prominence, mandate webhook reconcile (BUG-047).

---

## 2026-06-16 — "Unknown Fund" + redemption/edit (product · order · portfolio · redemption · edit)

> Scope: product/order/portfolio/redemption/edit only. Cybrilla-first + DB-fallback + fresh local copy already existed in `ProductService` and was left intact. Build: `mvnw test` → **288/0/0 GREEN**; frontend `tsc --noEmit` → **exit 0**.

- `[✓]` **Unknown Fund (root cause = FE).** Backend already snapshots the fund onto every order (`OrderService.applyProductSchemeSnapshot`) and the order JSON carries `productSchemeName`; the portfolio (`DashboardService` + `ProductSchemeOrderSupport.displayName`) already falls back to it. `Transactions.tsx`/`InvestorRedeem.tsx` ignored the snapshot and used an `id→scheme` map from `/products/schemes?...size=1000|200`, which `ProductService.schemePageRequest` caps to 100 rows → orders past the first 100 schemes alphabetically missed → "Unknown fund" + false "Payment Failed". **Fix:** FE now prefers `productSchemeName`; BE adds a read-time `ensureSchemeSnapshot` self-heal (findById by PK, no cap/active filter) for legacy orders. Files: `views/Transactions.tsx`, `views/InvestorRedeem.tsx`, `service/OrderService.java`.
- `[✓]` **Logging.** `product_scheme_fetch status='cybrilla_live'` (ProductService live fetch+upsert), existing `fallback_to_cache` (DB fallback), `order_scheme_backfill` (read-time resolve), `redemption_create` start/completed/rejected.
- `[✓]` **Redemption surfaced.** New `views/Redemptions.tsx` (distributor-wide list, snapshot names), sidebar entry in `layout/AppLayout.tsx`, route in `App.tsx`. Backend `createRedemption` (POST `/v2/mf_redemptions`) unchanged + logged.
- `[✓]` **SIP edit surfaced in Transactions** (`PATCH /orders/{id}/sip`) in addition to the existing SIP Dashboard editor.
- `[✓]` **ProductServiceTest** aligned to the working-tree `refreshFromCybrilla` (`deleteStalePurchaseSchemesNotIn`, B-68/B-69) — this test was already red in the tree pre-session; test-only change.
- `[—]` **Auth token generation/caching** — review delivered as recommendations only; no token code changed (per user).
