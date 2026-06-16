# Bug Fix History — Platizio Wealthtech

> Chronological log of every bug fix applied to this codebase. Each entry links to a bug ID in `bugs.md` and a fix entry in `fix.md`.

## Format
```
### YYYY-MM-DD — BUG-XXX — <short title>
- **Files:** `path/to/file.java:line`
- **Change:** <what was done>
- **Verified by:** <mvn test result, tsc, manual repro>
```

---

## 2026-06-15 — Initial fix plan created

- **Files:** `fix.md`, `history.md`
- **Change:** Created structured fix plan from `bugs.md` audit. Identified 22 bugs across backend IDOR, frontend build break, test failures, integration hygiene, and UX gaps. Sequenced fixes by priority: P1 security → P2 build-break → P3 UX → P4 integration → P5 frontend hygiene.
- **Verified by:** Plan structure only; no code changes yet.
- **Status:** Planning complete; dispatching parallel fix agents next.

---

## 2026-06-15 — Re-audit against current working tree + build-breaker fix

- **Files:** `bugs.md`, `fix.md`, `src/test/java/com/platizio/wealthtech/controller/AuditActorControllerTest.java`
- **Audit:** 4 parallel read-only agents re-verified `bugs.md` against the current tree (~9,654 uncommitted line changes). Findings:
  - **Already fixed by WIP (verified):** BUG-002 (postal IDOR), BUG-003 (order IDOR), BUG-006 (`@Valid`), BUG-007 (not-a-bug), BUG-008 (occupation PATCH), BUG-005 (3 original failing tests gone).
  - **Still open (verified):** BUG-009/010/011/012/013/014/015/020/021/022 + BUG-001 backend half.
  - **New bugs added:** BUG-023…038 (verified) + BUG-039…046 (carried from `docs/bugs.md`, not re-verified).
  - **Scope:** React frontend absent from repo → BUG-004/016/017/018/019 + docs FE bugs are out-of-scope.
- **BUG-023 — build-breaker FIXED.**
  - **Change:** `RecordingOrderService` now overrides `createRedemption(UUID, JwtAuthPrincipal)` — the overload `OrderController.createRedemption` calls after the BUG-003 ownership fix — recording `principal.getDistributorId()`, instead of the obsolete `(UUID, UUID)` overload that the controller no longer calls (which left the real ownership-check path running against a null `transactionOrderRepository`).
  - **Verified by:** `mvnw -B test` → **Tests run: 261, Failures: 0, Errors: 0, BUILD SUCCESS**.
- **Status:** Build GREEN. Tracking docs reconciled. Ready to execute P1/P2 backend fixes.

---

## 2026-06-15 — Wave 1 functionality fixes (security deferred per user)

Dispatched 4 parallel agents on disjoint files; verified centrally. Build: **`mvnw -B test` → 270 tests, 0 failures, 0 errors, BUILD SUCCESS** (9 new tests added).

- **BUG-024 — FP provider errors now 502/503, not 400.** `service/InvestorService.java` `ensureMfInvestmentAccount` rethrows the original `CybrillaApiException` (subtype `CybrillaUnavailableException`→503) instead of wrapping in `IllegalStateException` (which mapped to 400). `GlobalExceptionHandler` already maps these correctly.
- **BUG-001 — 409 duplicate body now carries the existing investor id.** `common/DuplicateResourceException.java` gained an optional `resourceId`+`conflictField` constructor; `controller/GlobalExceptionHandler.handleDuplicateResource` returns a new `ConflictErrorResponse` (409 incl. `resourceId`) when present, else the unchanged `ApiErrorResponse`. `service/InvestorService.java` passes `existing.getId()` at the duplicate-PAN/email throw sites. Message text preserved.
- **BUG-011 — lumpsum amount validated.** `service/OrderService.validateLumpsumRequest` rejects `LUMPSUM_PURCHASE` with null/≤0 amount before persistence (bulk path covered via `createOrder`).
- **BUG-025 — orphan CREATED orders fixed.** `createOrder` catches `CybrillaApiException` post-save, marks the order `FAILED` + `failureReason`, nulls `investorActionUrl`, and rethrows (no `@Transactional` added).
- **BUG-026/027 — investor-action statuses.** All 5 `InvestorActionController` handlers catch `CybrillaUnavailableException`→503 before `CybrillaApiException`→502; FAILED-order HTML uses new `app.frontend.origin` (default `http://localhost:3000`).
- **BUG-034 — `paymentComplete`** gained an `IllegalStateException`→400 catch matching sibling handlers.
- **Test compile fix:** added missing `import java.util.Arrays;` in `OrderServiceTest` (new lumpsum test used `Arrays.asList` with a null element).
- **Verified by:** full suite green (270/0/0).
- **Next:** BUG-010 (rollback→correct status), BUG-031 (soft-deleted dup 409), BUG-037 (BAV poll interrupt), then P3 integration (BUG-012/021/038, then BUG-009/013).

---

## 2026-06-15 — Wave 1b functionality fixes

3 parallel agents on disjoint files; verified centrally. Build: **`mvnw -B test` → 277 tests, 0 failures, 0 errors** (+7 tests).

- **BUG-010 — rollback/persistence no longer masked as investor-sync 409.** `controller/GlobalExceptionHandler.java`: `handleTransactionFailure`/`handlePersistence` now return `ResponseEntity`; emit the investor-sync 409 message only when the cause chain contains `finprim`/`restore from cybrilla` (or a recognized DB constraint); otherwise a neutral HTTP 500 ("…rolled back / persistence error. Please retry."). Investor-sync text no longer leaks onto unrelated endpoints.
- **BUG-031 — soft-deleted PAN/email duplicate now a clean 409.** `repository/InvestorRepository.java` gained native `findAnyIdByPanIncludingDeleted` / `findAnyIdByEmailIncludingDeleted` (bypass `@SQLRestriction`). `service/InvestorService.createInvestor` checks them after the active-row checks and throws a friendly `DuplicateResourceException` (with the archived id) instead of letting the DB unique constraint raise a raw `DataIntegrityViolationException`. Soft-delete semantics unchanged.
- **BUG-037 — BAV poll interrupt is now retryable.** `integration/RealCybrillaClient.pollFpBankAccountVerificationUntilSettled`: on `InterruptedException` it restores the interrupt flag and throws `CybrillaUnavailableException` (→503) instead of returning a still-`pending` node that the caller treated as a hard verification failure.
- **Verified by:** full suite green (277/0/0).
- **Next:** wave 2 integration — BUG-012 (skip order-ready PATCH when `invp_`+`mfia_` linked) + BUG-038 (route POA `pv_` to POA surface). Holding BUG-021 (txn restructure), BUG-009 (webhooks), BUG-013 (catalogue source) for review.

---

## 2026-06-15 — Wave 2 integration fixes

Guided by the `cybrilla-boss` / `achilles` docs (reviewed via subagent). Build: **`mvnw -B test` → 278 tests, 0 failures, 0 errors**.

- **BUG-012 — `ensureMfInvestmentAccount` now skips the redundant order-ready PATCH when already linked.** `service/InvestorService.java`: the two order-ready FP PATCH calls (`ensureInvestorProfileOrderReady` + `ensureMfInvestmentAccountOrderReady`) are now guarded behind `if (!mfAccountLinkedAtEntry)`; the bank-verification sync and `return investor` are preserved; logs `mf_investment_account_prepare status='skipped_already_linked'`. Removes per-order/redemption FP round-trips and avoids re-triggering occupation-immutability errors. Test added in `InvestorBankVerificationSyncTest`.
- **BUG-038 — reclassified NOT-A-BUG.** Verified `CybrillaDirectService.fetchPreVerification` → `cybrillaClient.fetchKycCheck` already routes to `GET /poa/pre_verifications/:id` with the POA token (`RealCybrillaClient.java:721-725`). Only the method name is misleading; no code change. (Adversarial verification prevented an incorrect "fix".)
- **Verified by:** full suite green (278/0/0).

### Session tally (functionality)
Fixed + verified: BUG-023 (build), BUG-001, BUG-010, BUG-011, BUG-012, BUG-024, BUG-025, BUG-026, BUG-027, BUG-031, BUG-034, BUG-037. Not-a-bug: BUG-007, BUG-038. Already fixed by WIP: BUG-002, BUG-003, BUG-005, BUG-006, BUG-008.
**Remaining functional (held for review):** BUG-021 (createRedemption txn restructure — risk), BUG-009 (mf_purchase/payment/mandate webhooks — large), BUG-013 (catalogue OMS→POA — latent, data-affecting), BUG-014/022/029/030 (config/data/external).
**Deferred (security, per user):** BUG-015/020/028/032/035/036.

---

## 2026-06-15 — Wave 3 integration fixes (all remaining functional, per user)

Build: **`mvnw -B test` → 281 tests, 0 failures, 0 errors**.

- **BUG-014 — `.env.example` now boots a fresh setup.** Added `DB_PASSWORD`, `JWT_SECRET` (both no-default/required) and `PAYMENT_POSTBACK_URL` (documented default) with comments. Verified against `application.yml` (`:11`, `:144`, `:149`).
- **BUG-021 — redemption no longer holds a DB connection across the FP pre-flight.** `service/OrderService.java`: removed `@Transactional` from `createRedemption(UUID,UUID)` (single `save` → no atomicity need), mirroring the deliberately non-transactional `createOrder`. Ownership check, audit, notification preserved. Test added.
- **BUG-013 — admin "Sync from Cybrilla" now seeds POA-orderable schemes (REAL bug, confirmed not config-correct).** The `cybrilla.integration.product-catalogue-endpoint` flag (default `poa-mf`) was honored by live-browse but ignored by the admin sync: `RealCybrillaClient.fetchProductSchemes()` hardcoded `/api/oms/fund_schemes`. Now honors the flag and defaults the MF leg to `/v2/mf_scheme_plans/cybrillapoa` (OMS only when explicitly configured). The 2 `RealCybrillaClientTest` cases encoding the old OMS contract were updated to the POA contract (one renamed).
- **BUG-009 — order/payment webhooks now reconcile.** `controller/CybrillaWebhookController` routes `mf_purchase.*`/`payment.*` to a new `OrderService.handleOrderWebhook(externalId, eventType)` which looks up the order via the new `TransactionOrderRepository.findByExternalOrderId` and reuses the **existing idempotent** `syncLumpsumOrderFromProvider` (re-fetches authoritative FP state); unknown ids → `ignored_no_matching_order`. `mandate.*` → `acknowledged_no_reconcile` (follow-up **BUG-047** — mandate is `int`-keyed, not order-id-keyed). Polling fallback intact; webhook secret check untouched (security-deferred). 2 webhook tests added.
- **Verified by:** full suite green (281/0/0).

### Final session result
Build RED→GREEN; **261→281 tests, 0 failures**. 16 functionality bugs fixed + verified; 2 confirmed not-a-bug; 5 already-fixed-by-WIP confirmed. Security batch (BUG-015/020/028/032/035/036) deferred per user. Remaining functional backlog: BUG-022/029/030/033/047. All changes uncommitted.

---

## 2026-06-15 — Frontend fix: `/distributor/transactions` page (demo)

> **Frontend repo (separate from this backend):** `C:\Users\HP\Downloads\platiziowealthtech-Front_End for now\platiziowealthtech-Front_End_sample` — the LIVE demo copy (git-tracked, `node_modules` present, most-recently modified). Two stale duplicates also exist: `Downloads\platiziowealthtech-Front_End_sample` and `...platiziowealthtech-Front_End_sample (1)\...` (do not edit those).
> **File:** `src/views/Transactions.tsx`. Relates to registry **BUG-004** (syntax) + `docs/bugs.md` BUG-023/BUG-033 (silent fetch / non-paged schemes).

- **BUG-004 — JSX syntax error broke the route (demo-breaker, root cause).** In `StatusTimeline`, the `{steps.map(...)}` expression container (the ternary's else branch) was closed with `})` instead of `})}`, leaving the JSX container unclosed. esbuild/Vite could not parse the module, so the lazy-loaded `/distributor/transactions` route failed at runtime and `vite build` failed. **Fix:** `})` → `})}`.
- **Type error (masked by the parse error).** `statusConfig` declared `Cancelled`/`CANCELLED` keys (used at runtime for cancelled orders) absent from the `StatusKey` union → `tsc` TS2353. **Fix:** added both to `StatusKey`. (Vite doesn't type-check, so it didn't block runtime, but `npm run lint`/`tsc` now passes for this file.)
- **Schemes-source data bug — legitimate orders mislabeled "Payment Failed".** The page fetched `/products/schemes` (defaults to the live Finprim POA catalogue, **page 0 / size 20**) and built `schemeMap` keyed by `s.id`. Demo orders reference **persisted local** scheme UUIDs not present on page 0 of the live POA list → `schemeMap.get(o.productSchemeId)` missed → `schemeKnown=false` → real orders rendered as "Payment Failed" / "Unknown fund". **Fix:** fetch local persisted schemes with a large page — `/products/schemes?local=true&size=1000` (confirmed against backend `ProductService.resolveSchemesPage`: `local=true` → `listSchemesPage` → DB rows with UUID ids). Preserves the existing "orphaned/purged scheme → failed" intent while removing the false positives.
- **Verified by:** `npm run build` (vite) → **exit 0** (2941 modules; `Transactions-*.js` emitted); `npx tsc --noEmit` → **Transactions.tsx clean** (1 unrelated pre-existing error remains in `ProductMgmt.tsx`, out of scope). Both servers running (FE :3000, BE :8081); Vite HMR applies the edits live. In-browser visual confirmation not completed (multiple Chrome instances connected; awaiting browser selection).
- **Status:** Transactions page builds + type-checks clean; data mapping corrected. Changes uncommitted (frontend repo).

---

## 2026-06-15 — Feature: SIP in-place edit (FP "Update a Purchase Plan")

> Doc-driven (user: "in-place edit if FP allows"). Official FP docs confirm `PATCH /v2/mf_purchase_plans` edits an active plan's **amount** and **installment_day** (applies to remaining installments; must be ≥2 days before the next installment). Frequency change = cancel+recreate; pause/skip = `skip_instructions` (not built here).

**Backend** (`mvnw -B test` → **283/0/0**, +2 tests; TDD red→green):
- New `dto/SipUpdateRequest.java` — `amount` (`@DecimalMin>0`) + `installmentDay` (`@Min 1`/`@Max 28`); both optional individually, ≥1 required (service-enforced).
- `service/OrderService.updateSipPlan(orderId, request, actorId)` — mirrors `cancelSipOrder` guards (ownership, SIP-only, cancellable status, real `mfpp_` plan id required), builds `{amount, installment_day}`, calls the existing `cybrillaClient.updateMfPurchasePlan` (→ `PATCH /v2/mf_purchase_plans`), updates local `amount` + `sipStartDate` day-of-month, audits `SIP_UPDATED`, notifies. FP enforces its own 2-day rule (surfaced as 502 if violated).
- `controller/OrderController` → `PATCH /api/v1/orders/{orderId}/sip` (`@Valid` body; `RequestBodyValidationTest` stays green).
- Tests: edits amount+day → calls provider + persists; rejects non-SIP order.

**Frontend** (`vite build` exit 0; `tsc` SipDashboard.tsx clean):
- `views/SipDashboard.tsx` — "Edit" button (enabled only for Active SIPs) beside Cancel + an Edit modal (new amount / installment day 1–28) → `PATCH /orders/{id}/sip`, with client validation, error state, session-cache invalidation, optimistic amount update.

- **Verified by:** backend 283/0/0 BUILD SUCCESS; frontend vite build exit 0 + tsc (SipDashboard clean; the 1 remaining tsc error is the pre-existing unrelated `ProductMgmt.tsx`).
- **Status:** SIP edit done. Next (per doc-driven build sequence): payment honesty + retry, then redemption (consent/confirm/poll + surface + redeemable holding), then integration gaps. All changes uncommitted.

---

## 2026-06-16 — "Unknown Fund" root-cause fix + redemption/edit surfacing (product · order · portfolio · redemption · edit)

> User: still seeing "Unknown Fund"; products must be Cybrilla-first with DB fallback while keeping a fresh local copy; orders must resolve the correct fund and appear in the portfolio; redemption + edit needed in FE and BE. Scope-limited to product/order/portfolio/redemption/edit; no unrelated refactors. Investigated with a 7-agent parallel workflow (backend product/order/portfolio/redemption, FE transactions/redeem/edit, auth review) + direct reads.

**Root cause of "Unknown Fund" (confirmed):** a *frontend* defect, not a data/Cybrilla problem.
- The backend already snapshots the fund onto every order at creation (`OrderService.applyProductSchemeSnapshot` → `transaction_orders.product_scheme_name/_external_code/_isin/_amc_name`), and the order JSON already carries `productSchemeName`. The backend portfolio (`DashboardService` + `ProductSchemeOrderSupport.displayName(order, scheme)`) already falls back to that snapshot, so the portfolio resolves names correctly.
- `Transactions.tsx` (and `InvestorRedeem.tsx`) ignored the snapshot and instead built an `id → scheme` map from `GET /products/schemes?local=true&size=1000` (Redeem used `size=200`). `ProductService.schemePageRequest` hard-caps page size at `MAX_SCHEME_PAGE_SIZE = 100`, so the map only ever held the first 100 schemes (alphabetical). Any order whose scheme sorts past the first 100 → map miss → `schemeKnown=false` → "Unknown fund" **and** a false "Payment Failed" relabel.

**Frontend fixes (live demo copy `platiziowealthtech-Front_End_sample`):**
- `views/Transactions.tsx` — fund now resolved as `productSchemeName` (order snapshot) → live scheme-map → `'Unknown fund'`; `schemeKnown` true whenever a name resolves, so genuine successful orders are no longer mislabeled "Payment Failed". Added an **Edit SIP** button + modal in `TransactionDetail` (PATCH `/orders/{id}/sip` `{amount?, installmentDay?}`), mirroring the SIP Dashboard editor.
- `views/InvestorRedeem.tsx` — holding fund prefers the order snapshot; never blocks Redeem on an unresolved name.
- `views/Redemptions.tsx` (NEW) — distributor-wide redemption surface listing every redeemable (SUCCESSFUL/COMPLETED, one-time PURCHASE/LUMPSUM_PURCHASE) holding across investors, using order-snapshot names (no scheme-map, immune to the cap). Redeem → POST `/orders/{id}/redemption`; dedupes against `/orders/{id}/redemptions`.
- `layout/AppLayout.tsx` — added a **Redemptions** entry (TrendingDown icon) to `DIST_NAV_PRIMARY` (redemption was previously reachable only via a per-investor button).
- `App.tsx` — added route `/distributor/redemptions` → `Redemptions`.

**Backend fixes:**
- `service/OrderService.java` — new `ensureSchemeSnapshot(...)` read-time safety net: when an order's `productSchemeName` is blank, resolve via `productSchemeRepository.findById` (by PK — no paging cap, no active filter, so deactivated-but-present schemes still resolve) and fill the snapshot fields on the returned object; logs `order_scheme_backfill resolved_from_db` / `scheme_not_found`. Wired into `listOrders`, `listOrdersByInvestor`, `listOrdersByDistributor`, and both `getOrder` overloads. Added `redemption_create` start/completed/rejected logging.
- `service/ProductService.java` — added `product_scheme_fetch status='cybrilla_live'` logging at the live-catalogue fetch+upsert boundary (the existing `fallback_to_cache` log already covers the DB-fallback path). Cybrilla-first + DB-upsert + DB-fallback behavior was already present and unchanged.
- `test/.../ProductServiceTest.java` — aligned a **pre-existing** stale test (`syncAvailableFundsFetchesCybrillaReplacesImportedSchemesThenReturnsPagedLocalResults`) with the working-tree `refreshFromCybrilla`, which replaced `deactivateActiveSchemesNotIn` + `deleteByExternalFetchRequestJsonIsNull` with `deleteStalePurchaseSchemesNotIn` (B-68/B-69). Test-only change; no production logic touched. This test was already red in the working tree before this session's edits.

**Redemption/edit status:** redemption backend (`createRedemption` → POST `/v2/mf_redemptions`) is functionally complete and one-shot (no consent/confirm/poll needed for unit sell); it now has logging and a first-class UI. SIP edit backend (`updateSipPlan` / PATCH `/orders/{id}/sip`) already existed and is now surfaced from both SIP Dashboard and Transactions. Non-SIP (lumpsum) orders have no in-place FP edit endpoint → edit remains cancel+recreate (unchanged, by design).

- **Verified by:** `mvnw test` — `OrderServiceTest` 11/0/0, `DashboardServiceTest` 5/0/0, `ProductServiceTest` 7/0/0; full suite re-run after the test alignment (was 288 run / 1 pre-existing failure → now green). Frontend: snapshot-preference + new view/route/nav are additive; no API contracts changed.
- **Status:** Unknown Fund fixed at the source (FE prefers order snapshot; BE self-heals legacy orders). Redemption + SIP edit surfaced in FE and confirmed in BE. Changes left **uncommitted**. Auth-token review delivered as recommendations only (no token changes), per user.

### 2026-06-16 — Redeem buttons wired into Portfolio + Transactions (follow-up)
- New shared `utils/redeemOrder.ts` — `isRedeemableHolding(status, type)` + `submitRedemption(orderId)` (POST `/orders/{id}/redemption` with normalized 502/503/demo-only/profile error messaging). Single source of truth for all redemption surfaces.
- `views/Portfolio.tsx` — Holdings table gained an **Action** column with a Redeem button for completed one-time purchases (success/error banner, optimistic "Redemption submitted").
- `views/Transactions.tsx` — `TransactionDetail` action header gained a **Redeem** button (non-SIP, SUCCESSFUL/COMPLETED purchases) with success/error banners.
- **Verified by:** frontend `tsc --noEmit` → exit 0 (no type errors). Additive; no API contracts changed. Uncommitted.

### 2026-06-16 — Backend startup fix: duplicate Flyway migration version 41
- **Symptom:** `spring-boot:run` failed at boot — `FlywayException: Found more than one migration with version 41` (offenders: `V41__repair_anita_demo_fp_profile.sql` [committed] and `V41__add_order_scheme_snapshot.sql` [untracked WIP that added the order scheme-snapshot columns]). `mvn test` stayed green because tests don't run Flyway against Postgres, so this only surfaced at real startup.
- **Cause:** pre-existing — a prior session added the snapshot-columns migration at V41, colliding with the existing V41. Versions V42–V51 were already taken.
- **Fix:** renamed `db/migration/V41__add_order_scheme_snapshot.sql` → `V52__add_order_scheme_snapshot.sql` (next free version) and removed the stale compiled copy from `target/classes`. The migration is idempotent (`add column if not exists` + `coalesce` backfill), so re-homing it is safe even where the columns already exist; it also backfills snapshots for existing orders (DB-side complement to `OrderService.ensureSchemeSnapshot`).
- **Verified by:** backend boots — Flyway "Successfully applied 1 migration … now at version v52", "Tomcat started on port 8081", "Started WealthtechBackendApplication"; `GET /api/v1/products/schemes` returns HTTP 401 (serving, auth required). No production Java changed. Uncommitted.

### 2026-06-16 — Task 1: SIP order with mandate — FP rejected `start_date`
- **Root cause:** `RealCybrillaClient.mfPurchasePlanBasePayload` sent `start_date` to `POST /v2/mf_purchase_plans`. FP's purchase-plan model has no `start_date` field → `400 "Unrecognized field 'start_date'"`. FP schedules by `installment_day` (day-of-month 1–28) — the same field the existing `updateMfPurchasePlan`/SIP-edit already uses.
- **Fix (`integration/RealCybrillaClient.java`):** replaced `start_date` with `installment_day` (new `sipInstallmentDay(order)` = `sipStartDate` day-of-month, clamped 1–28, falls back to today's day); removed the non-standard `auto_generate_installments` flag; kept `systematic`, `frequency`, `number_of_installments`, `payment_method=mandate`, `payment_source=mandateId`, `generate_first_installment_now`. The chosen start date is still stored locally on the order. Added request+response logging in `createSipOrderWithMandate` (frequency / installment_day / number_of_installments / amount → plan_id / fp_state).
- **Test:** updated `RealCybrillaClientTest.createSipOrderPostsToMfPurchasePlansEndpoint` to assert the corrected payload (`installment_day: 1`, no `start_date`/`auto_generate_installments`).

### 2026-06-16 — Task 2: Lumpsum payment — invalid `upi.type`
- **Root cause:** `RealCybrillaClient.createUpiUriPayment` sent `upi.type = "URI"` (uppercase) to `POST /api/pg/payments/netbanking`. FP requires the lowercase literal → `400 "upi.type Should be either uri or collect"`.
- **Fix (`integration/RealCybrillaClient.java`):** send `upi.type = "uri"`; added `normalizeUpiPayload(...)` that lowercases and validates `upi.type ∈ {uri, collect}`, throwing a clear `CybrillaApiException` for anything else (fail-fast in our backend instead of a generic FP 400). `uri` = intent/QR/redirect; `collect` supported for future UPI-collect flows. Added payment request+response logging (method / amc_order_ids / upi_type / provider → payment_id).
- **Test:** updated `RealCybrillaClientTest.createUpiUriPaymentSendsCustomCheckoutPayload` to expect `upi.type = "uri"`.
- **Verified:** `RealCybrillaClientTest` 28/0/0, `OrderServiceTest` 11/0/0, `InvestorActionServiceTest` 5/0/0 — BUILD SUCCESS. No frontend change required (payment-mode selection already flows through `paymentMethod(...)`; backend now maps it to FP's contract). Uncommitted.

### 2026-06-16 — Task 3: Know Your Distributor (ARN validation) + distributor signup
> Extends the existing distributor signup (`POST /auth/signup` → `AuthService.signup`) with ARN/KYD validation; reuses the CybrillaClient Real/Mock `@ConditionalOnProperty` provider pattern, the PanFormat validator style, the GlobalExceptionHandler error mapping, and the existing `Distributor`/`DistributorStatus` domain. No third signup path; investor onboarding untouched.

**Backend (new):**
- `domain/ArnValidationStatus.java` — PENDING_VERIFICATION / VERIFIED / REJECTED / EXPIRED / KYD_INCOMPLETE (separate from the account `DistributorStatus`).
- `integration/arn/ArnValidationClient.java` (interface + `ArnValidationResult` record), `MockArnValidationClient` (default — `arn-validation.real-client-enabled=false`, deterministic verdict from the ARN so all branches are testable; does NOT fabricate identities), `RealArnValidationClient` (active when real-client-enabled=true + base-url set — RestClient call + defensive JSON→verdict mapping; the plug-in point), `ArnValidationProperties` (`@ConfigurationProperties("arn-validation")`).
- `validation/ArnFormat.java` — `^ARN-\d{1,9}$` regex + message (mirrors `PanFormat`).
- `dto/ArnValidationRequest`, `dto/ArnValidationResponse`, `dto/DistributorVerificationStatusResponse`.
- `service/ArnValidationService.java` — local format check → provider call; logs each attempt (ARN only, no PII).
- `V53__add_distributor_arn_validation_fields.sql` — adds `arn_validation_status, arn_validated_at, arn_holder_name, firm_name, kyd_status, arn_validation_source` to `distributors` (idempotent).

**Backend (edits):**
- `domain/Distributor.java` — six ARN/KYD fields + accessors.
- `service/AuthService.java` — signup now validates ARN before account creation: VERIFIED proceeds (and persists the verdict + provider expiry/firm/holder); EXPIRED / KYD_INCOMPLETE / REJECTED throw a clear 400 message; provider-unreachable → 503 from the client. Duplicate-ARN kept as the existing 400 field error (no FE contract change). New `verificationStatus(email)`.
- `controller/AuthController.java` — `POST /api/v1/auth/arn-validation` (public) + `GET /api/v1/auth/verification-status` (authed). Profile fetch stays on `/auth/me`.
- `application.yml` — `arn-validation` block (all env-driven; mock by default).
- Updated `AuthControllerRefreshTest` / `BlockedTokenPurgeSchedulerTest` for the new `AuthService`/`AuthController` constructor arg.

**Frontend (`views/Onboarding.tsx`):** Step 2 gains a "Validate ARN" button → `POST /auth/arn-validation`; shows verified (green) / failure (red) states, auto-fills ARN expiry from the provider, and **gates "Next" on a VERIFIED result**. Reuses `apiFetch` + existing error/badge patterns.

**Mock test patterns** (until a real provider is configured) — verdict from the ARN's trailing digit so all branches are testable with well-formed `ARN-<digits>`: ends in `9`→EXPIRED (ARN-102949), `8`→KYD_INCOMPLETE (ARN-102948), `7`→REJECTED (ARN-102947), otherwise→VERIFIED (ARN-102943); malformed→REJECTED. (First cut keyed off letter substrings — dead code, since ArnFormat permits digits only — corrected to the trailing-digit scheme.)
- **Verified:** full backend suite **288/0/0 BUILD SUCCESS**; frontend `tsc --noEmit` OK; backend **boots** (Flyway applied V53, MockArnValidationClient wired, Tomcat on 8081); live `POST /api/v1/auth/arn-validation` returns VERIFIED / EXPIRED / KYD_INCOMPLETE / REJECTED / format-REJECTED for ARN-102943/49/48/47/`12345`. Auth-token logic unchanged. Uncommitted.
