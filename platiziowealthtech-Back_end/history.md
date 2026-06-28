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

### 2026-06-16 — SIP payment stuck: demo advancer raced the mandate flow + FP rejected `gateway`
- **Symptom:** SIP order in `SUBMITTED` with "Mandate simulation is only available while SIP mandate authorization is pending." DB showed it later went `SUCCESSFUL` with `mandate_status=AUTH_PENDING` and `external_order_id=null` — a bogus success (no FP plan, mandate never approved).
- **Root cause 1 (the reported error):** `init/DemoOrderAdvancer` (local-profile demo simulator) auto-advanced EVERY `PAYMENT_PENDING` order `PAYMENT_PENDING→SUBMITTED→PROCESSING→SUCCESSFUL` on a 5s timer — including SIP mandate orders. A SIP must sit in `PAYMENT_PENDING` awaiting e-mandate authorization; the advancer pushed it past that, gating off the mandate-simulation step (which requires `PAYMENT_PENDING`) and fabricating a SIP success with no plan.
  - **Fix:** `DemoOrderAdvancer` now skips `transactionType == SIP` (logs `skipped_sip`). SIPs complete only via the mandate flow (sandbox "Simulate Mandate Approval" or the live e-mandate auth postback → creates the FP plan → `ACTIVE`). Lumpsum auto-advance unchanged.
- **Root cause 2 (next FP rejection, found by driving the flow):** after the Task-1 `start_date`→`installment_day` fix, FP rejected the SIP plan payload with `400 "Unrecognized field 'gateway'"`. `gateway`/`initiated_via` are valid for `/v2/mf_purchases` (lumpsum) but NOT for `/v2/mf_purchase_plans`.
  - **Fix:** removed `gateway` and `initiated_via` from `mfPurchasePlanBasePayload` (kept on the lumpsum `mfPurchasePayload`). Updated `RealCybrillaClientTest.createSipOrderPostsToMfPurchasePlansEndpoint` to drop the `gateway` assertion + `jsonPath("$.gateway").doesNotExist()`.
- **Verified END-TO-END (live sandbox):** reset the stuck order, drove it through the real flow → mandate `21` APPROVED → SIP plan created `external_order_id=mfpp_89f4774da6264914a6b78472eb6e9cea` → first-installment NACH payment `25` → order **ACTIVE**. Confirmed the demo advancer now leaves a SIP in `PAYMENT_PENDING` across multiple cycles. `RealCybrillaClientTest` 28/0/0.
- Files: `init/DemoOrderAdvancer.java`, `integration/RealCybrillaClient.java`, `test/.../RealCybrillaClientTest.java`. Uncommitted.

### 2026-06-17 — Redemption: completed the FP lifecycle (backend + frontend)
> Was one-shot: `createRedemption` → `POST /v2/mf_redemptions` → `RedemptionRecord(CREATED)`, never synced. Docs (onboarding-and-orders.md:53) require review→consent→confirm→submission→success/failure. Completed it mirroring the purchase flow.

**Backend:**
- `integration/CybrillaClient.java` + `RealCybrillaClient` + `MockCybrillaClient`: added `fetchRedemption` (GET `/v2/mf_redemptions/:id`), `updateRedemptionConsent` (PATCH `{id,consent}`), `confirmRedemption` (PATCH `{id,state:'confirmed'}`) — mirror `fetchMfPurchase`/`updateMfPurchaseConsent`/`confirmMfPurchase`. Mock returns canned states (confirmed→submitted→successful) so the demo reaches a terminal state.
- `service/OrderService.java`: `createRedemption` now creates → consent → confirm → reads FP state (record SUBMITTED, reconciled by sync). New `syncRedemptionsForOrder(orderId, principal)` (ownership-checked) fetches each record's FP state and maps it via `mapRedemptionState` (created/under_review→CREATED, confirmed/submitted→SUBMITTED, processing→PROCESSING, successful→SUCCESSFUL, bank_credit_*→BANK_CREDIT_*, failed/rejected/cancelled→FAILED), captures `bank_credit_reference`/`failure_reason`, notifies on terminal. Skips terminal/no-FP-id records.
- `controller/OrderController.java`: `POST /api/v1/orders/{orderId}/redemptions/sync`.
- Test: `OrderServiceTest.createRedemptionSavesRecordWithoutRequiringTransactionManager` now asserts SUBMITTED (was CREATED).

**Frontend:**
- `utils/redeemOrder.ts`: `fetchRedemptions`, `syncRedemptions`, `latestRedemptionStatus`, `redemptionStatusMeta` (status→label+badge classes).
- `views/Redemptions.tsx` + `views/InvestorRedeem.tsx`: redeemed holdings now show the real redemption **status badge** (Submitted/Processing/Redeemed/Failed/Bank-credit) with a **Sync** button (POST …/redemptions/sync) instead of a static "Redemption submitted".

- **Verified:** backend full suite **288/0/0 BUILD SUCCESS**; frontend `tsc --noEmit` clean; backend boots, redemption + sync endpoints wired (401 unauth). `gateway` kept in `redemptionPayload` (matches the working lumpsum `mfPurchasePayload`; redemptions are POA transactions like `/v2/mf_purchases`, unlike `/v2/mf_purchase_plans` which rejects it). Live end-to-end redeem still needs a real SUCCESSFUL POA purchase (a few `mfp_` lumpsum orders exist) + an authenticated session. Uncommitted.

### 2026-06-17 — Remaining fix.md bugs, 1-by-1 (BUG-030, 033, 029, 047; BUG-022 deferred)
Build: `mvnw test` → **290/0/0 BUILD SUCCESS** (+2 mobile-validation tests).

- **BUG-030 — payment honesty + retry.** `init/DemoOrderAdvancer` now skips real FP-backed orders (`mfp_`/`mfpp_`) in addition to SIPs (new `isRealFpOrder`), so only demo-stub orders auto-advance and real lumpsum completes via the sandbox Simulate-Payment flow. **Retry:** a failed payment now sets `RETRY_AVAILABLE` (not FAILED) and the investor-action page shows a **Retry Payment** button → `POST /investor-actions/{token}/retry-payment` → `InvestorActionService.retryLumpsumPayment` re-initiates payment on the still-submitted purchase (no re-confirm). Files: `DemoOrderAdvancer.java`, `service/InvestorActionService.java`, `controller/InvestorActionController.java`.
- **BUG-033 — mobile validation.** `dto/InvestorCreateRequest.mobileNumber` gained `@Pattern(^[6-9]\d{9}$)` (matches the FE rule; all seed/test numbers conform). +2 tests in `InvestorCreateRequestValidationTest`.
- **BUG-029 — FP config fail-fast.** New `integration/auth/ExternalIntegrationConfigValidator` logs `external_integration_config status='configured'|'INCOMPLETE' missing=[…]` at `ApplicationReadyEvent` when `cybrilla.integration.real-client-enabled=true` — clear, secret-free, non-fatal (mock mode and non-FP features still run).
- **BUG-047 — mandate webhook reconcile.** `CybrillaWebhookController` routes `mandate.*` to `OrderService.handleMandateWebhook` (find SIP order by FP mandate id via new `findFirstByExternalMandateId` → fetch authoritative state → APPROVED records approval / REJECTED|FAILED|CANCELLED fails the order + notifies), replacing `acknowledged_no_reconcile`. Portfolio refresh was already live (DashboardService derives from orders per request).
- **BUG-022 — deferred (deliberate).** V41–V51 are already scoped to one demo-investor UUID → production-safe no-ops. Relocating 11 applied migrations into a seeder (replicating their cumulative effect) is error-prone for cosmetic benefit and risks the just-stabilized Flyway boot; `ignore-migration-patterns: "*:missing"` means it can be done later as a separately-reviewed change.
- **Verified:** full suite 290/0/0; backend compiles. Uncommitted.

### 2026-06-17 — Doc verification (cybrilla-boss + official FP) + redemption lifecycle correction + demo-readiness audit
**Verified my recent changes against the docs** (`.codex/skills/cybrilla-boss/references/onboarding-and-orders.md` + official FP pages):
- **Payment retry** — FP `payment-retry` doc confirms exactly: failed payment leaves the order `submitted`, NO re-confirm, reuse the payment endpoint with same `amc_order_ids`/`bank_account_id`, "only one pending attempt per order". My `retryLumpsumPayment` + `RETRY_AVAILABLE` flow matches (it only fires post-failure, and `submitPurchaseForPayment` returns the existing URL if one is still pending → one-pending honored).
- **Redemption lifecycle** — FP overview confirms states `under_review → pending (review passed) → confirmed → submitted → successful/failed`, and that **consent is collected at `pending`**. My first cut consented/confirmed immediately after create (premature on real FP) and the sync only read state. **Fixed:** new `advanceRedemptionLifecycle(record, investor)` fetches authoritative state and submits consent+confirm only when state is `pending`; used by BOTH `createRedemption` (best-effort) and `syncRedemptionsForOrder` (the Sync button now DRIVES the lifecycle forward, not just reads it). `mapRedemptionState` corrected so `pending` = CREATED (awaiting consent), not PROCESSING. Endpoints (`POST/PATCH/GET /v2/mf_redemptions`) confirmed against the official nav. `gateway:"ondc"` kept on the redemption payload (matches the working lumpsum `/v2/mf_purchases`; redemptions are POA transactions, unlike `/v2/mf_purchase_plans`) — the one field not explicitly shown in the docs; confirm on a live redeem.
- **Verified:** `OrderServiceTest` 11/0/0, `RealCybrillaClientTest` 28/0/0 (test proxy updated to return a redemption state).

**Demo-readiness audit (2 parallel agents, evidence-based):**
- **Backend:** suite **290/0/0**; live probes OK (ARN endpoint returns VERIFIED/EXPIRED/REJECTED; products 401=serving). All 9 flows (signup+ARN, onboarding/KYC, catalogue, lumpsum+payment, SIP+mandate, payment-retry, redemption, mandate-webhook, portfolio/transactions) wired controller→service→client with **ZERO** TODO/stub/dead-end on any demo path. Risks are operational: live FP-sandbox latency/availability, the **amount-ending-in-0** sandbox rule, tenant-config quirk, the `local`-profile webhook accepting no secret.
- **Frontend:** `tsc --noEmit` clean + `vite build` exit 0 (hard blockers pass). Every sidebar route wired to a real component+endpoint. **Cosmetic gaps to steer around in the demo:** `Earnings.tsx` is static mock data (+ no-op Download), `Communications.tsx` "Send" is local-only (no backend), Step-5 onboarding agreement bodies are `PLACEHOLDER` text, `TaxCalculator` slabs hardcoded.

### 2026-06-17 — Onboarding hard-blocked by false "missing occupation" on existing FP profile
- **Symptom (both reported issues, one root cause):** investor onboarding/KYC failed with *"Unable to sync investor profile before KYC: Fintech Primitives investor profile `invp_…` is missing occupation, which cannot be added via PATCH after the profile was first created."* Presented to the user as "occupation not detected" + "KYC readiness failing".
- **Root cause:** when a PAN already has an FP profile (the common sandbox case — PANs are reused, and a prior onboarding already created a complete profile via our `POST /v2/investor_profiles`, which always includes occupation), `createInvestorProfile` resolves it and called `assertExistingProfileSupportsOrderSubmission`, which **hard-threw** because FP's `GET /v2/investor_profiles` omits `occupation` even when it is stored server-side (documented quirk — `fp-profile-patch-rules.md`). KYC does not require occupation, so the throw blocked onboarding on a false signal.
- **Files:** `integration/RealCybrillaClient.java` — renamed `assertExistingProfileSupportsOrderSubmission` → `warnIfExistingProfileMissingOccupation`; now logs `status='occupation_absent_on_get'` and proceeds instead of throwing. Both call sites (`createInvestorProfile` existing-profile + create-conflict branches) updated. Genuine order-readiness stays enforced by FP at order-submission time (`ensureInvestorProfileOrderReady` + immutable-field retry already handle it).
- **Verified:** `mvn compile` clean; `RealCybrillaClientTest` 28/0/0, `InvestorKycServiceTest` 36/0/0, `InvestorBankVerificationSyncTest` 9/0/0; backend restarted on :8081 (health UP). Uncommitted. Retry the onboarding that failed — it now resolves the existing profile and continues.

### 2026-06-17 — Pre-verification button stays locked after going back and editing identity
- **Symptom:** after running POA pre-verification, going **back** in the onboarding wizard and changing identity (PAN / name / DOB / …) did not re-enable the "Run pre-verification" (pre-KYC) button, and the stale result was not cleared — so the new data was never re-checked.
- **Root cause (frontend, `views/InvestorOnboarding.tsx`):** the button lock (`shouldLockPoaRunButton`) keyed only off `poaPreVerificationComplete`, which stayed true from the prior (now stale) result. The "reset on identity change" effect keyed only off `draftIdentityFingerprint`, which is empty on a resumed/loaded investor — so an edit-after-resume never cleared it. The KYC flow-status poll also re-populated `kycPreVerification` from the backend's stale identity (`prev || rebuilt`), re-locking the button.
- **Fix:** derive `identityChangedSinceKyc = lastKycIdentityFingerprint && lastKycIdentityFingerprint !== identityFingerprint` (authoritative "last check ran on different identity"). (1) Button: pass `preVerificationComplete: poaPreVerificationComplete && !identityChangedSinceKyc` so an edit re-enables Run. (2) Reset effect now also fires on `lastKycIdentityFingerprint` mismatch (covers resume+edit) and keeps `lastKycIdentityFingerprint` so the unlock persists until a fresh run. (3) Flow-status rebuild guarded with `&& !identityChangedSinceKyc` so the stale result isn't resurrected. Re-run then sends `forceNewCheck` (existing `shouldForceNewKycCheck`) so the backend resets attempt state for the new identity. `computeIdentityFingerprint` already normalizes both the `fullName` and `firstName/lastName` shapes, so a clean resume does NOT false-trigger.
- **Verified:** `tsc --noEmit` exit 0. Frontend hot-reloads (Vite). Uncommitted.

---

## 2026-06-16 — Tier 1 demo blockers (DF-13 / DF-12 / DF-07) + reset-token leak

Environment note: no JDK was present on this machine — installed **OpenJDK 21** via Homebrew (`/opt/homebrew/opt/openjdk@21`) so the suite could actually run. Build: **`mvnw -B test` → 283 tests, 0 failures** (+2 security tests).

- **DF-13 — dev OTP code no longer leaks.** `service/OtpService.java`: replaced the `isLocalProfile()` gate on `devCode` with a config flag `@Value("${app.otp.expose-dev-code:false}")` (removed `Environment`/`Profiles`/`isLocalProfile()`). Default false, so the live code is never returned in deployed/demo environments; `application-local.yml` sets it true for dev. `views/LoginPage.tsx`: removed the amber "Dev mode — your OTP is" banner and all `otpGenerated`/`devCode` plumbing. New test `requestOtpNeverReturnsDevCodeWhenExposeDevCodeDisabled`.
- **DF-12 — clean demo profile.** New `application-demo.yml` (no OTP leak, seeded data, `warm-on-startup:false` so it boots without live Cybrilla creds). `app.otp.expose-dev-code` + `app.demo.order-advancer-enabled` default false in `application.yml`, true in `application-local.yml`. `init/DemoDataSeeder` → `@Profile({"local","demo"})` (+ corrected stale V34 Javadoc); `init/DemoOrderAdvancer` → `@Profile({"local","demo"})` + `@ConditionalOnProperty(app.demo.order-advancer-enabled)`. Run the demo with `SPRING_PROFILES_ACTIVE=demo`.
- **DF-07 — scheme NAV no longer renders ₹0.00.** Forward migration `V52__add_scheme_nav_metadata.sql` (text::jsonb, NULL-safe, idempotent via `jsonb_exists`) seeds a numeric `nav` into the five V3 schemes. `integration/RealCybrillaClient.buildSchemeMetadata` extracts a canonical `nav` (new `firstNav(...)`). `views/InvestorTransaction.tsx` + `views/ProductMgmt.tsx` render `—` instead of a misleading `₹0.00`/`Rs 0`.
- **Bonus (monitor finding) — reset-token leak.** `service/PasswordResetService.java`: the raw reset token was returned on the local profile (same leak class as DF-13). Now gated on `@Value("${app.password-reset.expose-dev-token:false}")` (true only in `application-local.yml`). New disabled-case test.
- **Verified by:** 283/0/0; `tsc --noEmit` clean + `vite build`; `V52` validated against real Postgres 17 (idempotent, preserves data); independent monitor sub-agent → Tier 1 READY.

---

## 2026-06-16 — Tier 2 (partial): Supabase email/mobile contact verification + self-declaration

User chose **Supabase Auth** for email + phone OTP. Built backend-mediated (browser → Platizio `/api/v1` only; the backend calls Supabase GoTrue). Build: **`mvnw -B test` → 292 tests, 0 failures** (+9 tests).

- **Supabase client (pluggable).** `integration/SupabaseAuthClient.java` (interface) + `RealSupabaseAuthClient` (Spring `RestClient` → `POST /auth/v1/otp`, `POST /auth/v1/verify`; 200→verified, 4xx→false, 5xx/network→`SupabaseAuthException`) + `DisabledSupabaseAuthClient` (no-op fallback, accepts dev code `000000`, logged) selected by `supabase.auth.real-client-enabled` (code default **false**; overridden to **true** in the git-ignored `.env` on 2026-06-17, so `RealSupabaseAuthClient` is the active bean for email OTP). `integration/auth/SupabaseAuthProperties.java` + `application.yml` `supabase.auth.*` block.
- **Contact verification.** `service/InvestorContactVerificationService.java` (request/verify email & mobile OTP, self-declare, status; ownership-checked; audits each action; mobile sent in E.164). 6 endpoints on `controller/InvestorController.java` (`/{id}/email|mobile/otp/request|verify`, `/{id}/contact/declare`, `/{id}/contact/status`). `domain/Investor.java` + `V53__add_investor_contact_verification.sql` add 8 columns; enums `ContactChannel`, `ContactVerificationMethod`; DTOs `OtpVerifyCodeRequest`/`ContactDeclarationRequest`/`ContactVerificationStatus`.
- **BUG-033 — mobile validation.** New `validation/MobileFormat.java` (`^[6-9]\d{9}$` + `toE164India`) applied via `@Pattern` on `InvestorCreateRequest`/`InvestorUpdateRequest`. 3 validation tests added.
- **DF-11 — `belongs_to` no longer hardcoded.** `RealCybrillaClient` email/phone payloads now use `resolveBelongsTo(investor.getEmail/MobileBelongsTo())`, default `self`.
- **Frontend.** `components/ContactVerification.tsx` (Send OTP → verify, or self-declare with relationship) mounted beside the email & mobile inputs in `views/InvestorOnboarding.tsx`; calls the backend only.
- **Verified by:** 292/0/0; `tsc --noEmit` clean + `vite build`; `V52`+`V53` validated on Postgres 17; independent monitor sub-agent → Tier 2 READY; **LIVE BOOT** on `SPRING_PROFILES_ACTIVE=demo` against a fresh Postgres DB — Flyway applied 24 migrations through V53, Hibernate `ddl-auto: validate` passed (8 new columns OK), context started in 4.1s, password login → 200, new endpoints → 401 unauth / 404 authenticated-not-found (full security→controller→service→repo chain exercised).

### Session result (2026-06-16)
**281 → 292 tests, 0 failures.** Tier 1 (DF-13/12/07) + reset-token leak: done & verified. Tier 2 Supabase contact-verification slice (DF-11, BUG-033, email/mobile OTP + self-declaration): done & verified, including a live demo-profile boot.

**NOT done / still open** (be explicit — these are NOT complete):
- **Supabase email OTP — now live-verified (2026-06-17; see the entry below).** A real project (`volmpsvzrbzialnrrqjk`) is configured with `real-client-enabled=true` in the git-ignored `.env`; the live email `type:"email"` round-trip is proven (send 200, bad-code verify 403 `otp_expired`). **Mobile/SMS `type:"sms"` is deferred** — the Supabase Phone provider is disabled (likely a paid plan), so it stays unexercised.
- **T&C acceptance** (DF-10 / original Task 4) — not built.
- **Pre-verification `investor_identifier` parity** (original Task 5) — not built.
- **DF-08** (V3 demo seed reaches prod), **DF-09** (FE auto-populated dropdowns / SEBI no-defaults), **DF-14** (5 hardcoded compliance resolver defaults), **DF-15** (Flyway V41–V51 sprawl / BUG-022) — not built.
All changes uncommitted.

---

## 2026-06-16 — Remaining batch: T&C (Task 4/DF-10), pre-verification parity (Task 5), DF-08/09/14/15

Build: **`mvnw -B test` → 295 tests, 0 failures** (+3 TermsAcceptance tests).

- **Task 4 / DF-10 — Terms & Conditions acceptance.** `V54__add_terms_acceptances.sql` (id/created_at/updated_at + subject_type/subject_id/document_key/version/accepted_at/ip_address/user_agent + index). `domain/TermsAcceptance.java` (extends BaseEntity), `repository/TermsAcceptanceRepository.java`, `service/TermsAcceptanceService.java` (record() truncates ip/ua, audits `TERMS_ACCEPTED`; getLatest/list). `controller/InvestorController.java` POST `/{id}/terms/accept` + GET `/{id}/terms`, both ownership-checked via `investorService.getInvestor(id, actorId)`; `clientIp()` reads X-Forwarded-For→getRemoteAddr. `dto/TermsAcceptanceRequest.java`. FE `views/InvestorOnboarding.tsx`: a **separate** `termsAccepted` checkbox (distinct from FATCA `declared`), gated in step-6 `canNext`, POSTs on submit, re-fetches `GET /terms` on resume to pre-check. 3 service tests.
- **Task 5 — pre-verification investor_identifier parity.** `service/InvestorKycService.preVerificationPayload` now adds `investor_identifier` as a plain-string PAN (matches POA `pre-verifications.md` and the onboarding payload); `InvestorKycServiceTest` flipped to assert it.
- **DF-14 — compliance resolver observability.** `integration/RealCybrillaClient` resolvers (gender/occupation/income_slab/source_of_wealth/pep) now route blanket-default fallbacks through a `complianceDefault(...)` helper that logs WARN with the field/investor/reason. Legitimate mappings are not warned. Demo-only `'allow'` and the `InvestorService` SELF fallback left untouched.
- **DF-08 — remove V3 demo seed from prod.** `V55__remove_demo_seed.sql` forward-DELETEs the demo rows by fixed UUID in FK order (Bob + bank + 5 orders + redemption + notification + Charlie lead/interaction + signup audit + a@a.com distributor) AND the two extra `gen_random_uuid` schemes by code (MF-201, SIF-301). KEEPs Alice (`d9b2d63d`) + the 3 canonical schemes. `DemoDataSeeder` (@Profile{local,demo}) re-inserts on local/demo.
- **DF-09 — no auto-populated FE dropdowns (SEBI Req 1).** `InvestorOnboarding.tsx`: relationshipType/contactOwner/taxResidency/politicalExp defaults `|| ''` (new + resume), disabled empty placeholders, `canNext` requires each (case 1/3/6), `taxResidency !== 'India'` branch guarded against ''. `InvestorTransaction.tsx`: SIP frequency no MONTHLY default/fallback, empty option, zod still required, `frequencyLabel` handles undefined.
- **DF-15 — Flyway V41–V51 cleanup (BUG-022).** Deleted the 11 Anita sandbox-repair DML files (their final state — pan `KRTPX3751K` — is already encoded in `DemoDataSeeder`). `FlywayConfig` repair()+migrate() and `application.yml` `ignore-migration-patterns: "*:missing"` handle DBs that already applied them.
- **Verified by:** 295/0/0; `tsc --noEmit` clean + `vite build`; V54/V55 validated on Postgres 17; independent monitor sub-agent → READY (all 6 PASS). **Live demo-profile boots against real Postgres 17:** (a) fresh DB → "applied 15 migrations, now at v55", Started, product_schemes = exactly the 3 canonical (MF-201/SIF-301 removed), Bob re-seeded; (b) DB that already ran V41–V51 → "Successfully repaired schema history" + "validated 26 migrations" + Started (DF-15 deletion is safe on pre-migrated DBs); (c) T&C endpoint E2E: login 200 → POST `/terms/accept` 200 (record with ip `::1` + UA captured) → GET `/terms` returns it → DB row present.

### Session result (2026-06-16, full)
**281 → 295 tests, 0 failures.** All of: Tier 1 (DF-13/12/07), Tier 2 Supabase contact verification (DF-11, BUG-033), reset-token leak, **and the remaining batch (Task 4/DF-10, Task 5, DF-08, DF-09, DF-14, DF-15)** — done & verified (backend suite + FE build + live Postgres boots + monitor audits). **Still NOT done:** nomination (#4), transaction 2FA for purchase/redeem/SIP (#5–7), redemption-screen folio/units (#9) and authoritative holdings (#10), the rest of regulated-defaults collection (#11 — DF-14 only made the defaults observable, it does not yet COLLECT the fields), and the live-Supabase **mobile/SMS** OTP round-trip — **deferred** (Phone provider disabled, likely a paid plan; the **email** OTP round-trip is now live-verified — see the 2026-06-17 entry). All changes uncommitted.

---

## 2026-06-17 — Supabase email OTP go-live + mobile/SMS deferred

Configured a real Supabase project and made **email** contact-verification OTP live; **mobile/SMS deferred** per product decision. Build: **`mvnw -B test` → 296 tests, 0 failures** (+1 mobile-guard test).

- **Live email OTP.** `real-client-enabled=true` + `SUPABASE_URL` (project `volmpsvzrbzialnrrqjk`) + `SUPABASE_ANON_KEY` set in a new git-ignored `.env` (anon key decodes to `role=anon` / correct ref; `.env` confirmed git-ignored). Live curl proof against the project: `GET /auth/v1/settings` → 200 (`email:true`); `POST /auth/v1/otp` (email) → 200 (email dispatched); `POST /auth/v1/verify` bad code → 403 `otp_expired` (the 4xx `RealSupabaseAuthClient` maps to not-verified). Caveat: the dashboard Magic Link template must include `{{ .Token }}` for the email to carry the 6-digit code.
- **Mobile/SMS deferred + made graceful.** Supabase Phone provider disabled (`settings.phone:false`; two live phone `/auth/v1/otp` calls failed with timeout / connection-reset) — likely a paid plan. Added a per-channel `supabase.auth.sms-enabled` flag (`SUPABASE_SMS_ENABLED`, default **false**) on `SupabaseAuthProperties` + new `SupabaseAuthClient.isSmsEnabled()` (Real → property; Disabled → `true` so local dev keeps the `000000` mobile flow). `InvestorContactVerificationService.requestMobileOtp`/`verifyMobileOtp` now guard on it and throw `IllegalStateException` → existing handler maps to a clean **400** ("use self-declaration") instead of an opaque 500. `ContactVerificationStatus` gains `smsOtpEnabled` so the FE can hide mobile OTP. Mobile self-declaration is unaffected.
- **Files:** `.env` (new, git-ignored), `.env.example`, `application.yml`, `integration/SupabaseAuthClient.java`, `integration/RealSupabaseAuthClient.java`, `integration/DisabledSupabaseAuthClient.java`, `integration/auth/SupabaseAuthProperties.java`, `dto/ContactVerificationStatus.java`, `service/InvestorContactVerificationService.java`, `service/InvestorContactVerificationServiceTest.java`; doc updates in `TEAM_HANDOFF.md`, `context.md`, `history.md`.
- **Verified by:** 296/0/0 (`./mvnw -B test`, JDK 21); live GoTrue curl round-trip (email send 200 + bad-code verify 403); config re-verified by an independent agent (all 5 checks pass).
- **Residual (user/dashboard):** add `{{ .Token }}` to the Magic Link template; **rotate** the `service_role` / secret / JWT keys shared during setup; to enable mobile later, turn on Authentication → Providers → Phone with an SMS provider and set `SUPABASE_SMS_ENABLED=true`. All changes uncommitted.

---

## 2026-06-22 — Investor-facing portal, Phase 1 (SRS PLZ-SRS-INV-COMP-001 v1.0)

Built a genuine **investor surface** alongside the distributor app: passwordless investor signup/login, an approval-gated onboarding flow (the distributor **cannot finalize** until the investor attests the exact submission), immutable consent evidence, and investor-self contact verification. **`mvnw -B test` → 321 tests, 0 failures** (295 → 321; +26 new). FE `tsc --noEmit` + `vite build` clean. Built in parallel with agents, then independently audited + live-verified. Phases 2 (transaction 2FA + withdrawals) and 3 (units×NAV holdings + XIRR) remain outlined-only.

- **New investor identity (separate from distributor-owned `Investor`).** `V56 investor_accounts` (full_name, pan unique, email unique, mobile_number, email/mobile_verified, status `PENDING_ACTIVATION|ACTIVE|BLOCKED`, nullable `investor_id` link, activated_at) + `InvestorAccount`/`InvestorAccountStatus`/`InvestorAccountRepository`. **No password column** — login is email OTP. Account links to a distributor-created `Investor` by **PAN match on signup** (no silent auto-link elsewhere).
- **Passwordless auth, structurally isolated from the distributor session.** `OtpPurpose` gains `INVESTOR_LOGIN/INVESTOR_SIGNUP` (reuses the in-house `OtpService` email engine). `JwtService` adds a `typ` claim (`DISTRIBUTOR|INVESTOR`) + `generateInvestorToken`; new `AuthenticatedInvestorPrincipal` (authority `ROLE_INVESTOR`) deliberately does **not** implement `JwtAuthPrincipal`. `JwtAuthFilter` branches by request path: investor paths read the `investor_access_token` cookie and build the investor principal only when `typ=INVESTOR` (and vice-versa) — so neither portal's cookie is even read on the other's endpoints. `AuthCookieService` gains the investor cookie (HttpOnly) read/write/clear. `InvestorAuthService` (signup verifies+consumes OTP before account creation, rejects duplicate email/PAN, records T&C + email-ownership consent; login restricted to ACTIVE).
- **Approval gate (immutable revisions).** `V58 onboarding_submissions` (revision_no, status `DRAFT_AWAITING_INVESTOR|ATTESTED|SUPERSEDED`, payload_json, content_sha256, attest ip/ua). `OnboardingSubmissionService`: distributor `submitForInvestorReview` freezes a SHA-256-hashed snapshot; investor `attest` is **bound to that exact hash** (stale hash rejected); `assertFinalizable` blocks finalize unless the latest revision is `ATTESTED` **and** its hash equals the currently-rendered snapshot; any distributor edit (`PUT /investors/{id}`) calls `invalidateAttestationOnEdit`. Distributor `finalize` calls `assertFinalizable` then the additive `InvestorService.markReadyAfterInvestorApproval` (ownership-checked → `READY_FOR_TRANSACTIONS` + audit `ONBOARDING_FINALIZED`).
- **Immutable consent evidence.** `V57 consent_records` (subject_type/id, consent_key, template_version, rendered_text + content_sha256, ip/ua) + `ConsentRecordService` (records `investor_tnc`, `contact_ownership_email`, `onboarding_attestation`; T&C row also via existing `TermsAcceptanceService`). Placeholder canonical text — **compliance to finalize wording/versions**.
- **Investor-self contact verification.** `InvestorContactVerificationService` `*AsInvestor(...)` variants resolve the `Investor` via the account link (no distributor-ownership check); reuse the Supabase email path; mobile returns a clean 400 while SMS is dormant.
- **Controllers + security.** `InvestorAuthController` (`/api/v1/investor-auth`: otp/request, login/otp/verify, signup, logout); `InvestorPortalController` (`/api/v1/investor`: me, onboarding/review, onboarding/attest, contact self-verify); distributor gate endpoints on `InvestorController` (submit-for-review, approval-status, finalize). `SecurityConfig` permits `/api/v1/investor-auth/**` and gates `/api/v1/investor/**` to `hasRole("INVESTOR")`.
- **Frontend (matches the existing navy/Inter design system).** `investorAuthSlice` (+ path-aware 401 in `config/api.ts` → investor 401s go to `/investor/login`, never the distributor refresh); `InvestorLoginPage` (6-box OTP, mobile toggle disabled "soon"); `InvestorSignup` (email→OTP, name/PAN/mobile, **unticked** ownership + T&C); `InvestorOnboardingReview` (read-only draft + self ContactVerification + **unticked** attest → posts the revision hash); distributor `InvestorOnboarding` gains "Submit for investor approval" + a blocking banner + finalize gated on `ATTESTED`; `App.tsx` investor routes. No password fields, no preselected consents.
- **Security audit (independent agent) — 2 Medium findings fixed:** (1) a `BLOCKED` investor kept portal access until JWT expiry → `InvestorAuthService.requireAccount` now rejects non-ACTIVE on **every** session use (was login-only); + investor logout now revokes the token jti server-side (`BlockedTokenService`, honored by `JwtAuthFilter:83` which checks `isBlocked` before the principal branch). (2) investor-auth surface had no IP throttle → added the 3 investor-auth POST paths to `LoginRateLimitFilter`. Core model passed: IDOR closed (identity from principal only), cross-portal isolation structural, gate hash-bound, no PAN/OTP/token leakage in responses.
- **Files (new):** migrations `V56/V57/V58`; `domain/InvestorAccount`,`InvestorAccountStatus`,`ConsentRecord`,`OnboardingSubmission`,`OnboardingSubmissionStatus`; `repository/InvestorAccountRepository`,`ConsentRecordRepository`,`OnboardingSubmissionRepository`; `service/InvestorAuthService`,`ConsentRecordService`,`OnboardingSubmissionService`; `security/InvestorAuthPrincipal`,`AuthenticatedInvestorPrincipal`; `controller/InvestorAuthController`,`InvestorPortalController`; `dto/InvestorSignupRequest`,`InvestorOtpRequest`,`InvestorOtpVerifyRequest`,`InvestorAuthResponse`,`OnboardingAttestRequest`; FE `store/slices/investorAuthSlice`,`types/investorAuth`,`views/InvestorLoginPage`,`InvestorSignup`,`InvestorOnboardingReview`. **(modified):** `domain/OtpPurpose`,`service/OtpService`,`AuthService`,`JwtService`,`AuthCookieService`,`InvestorService`,`InvestorContactVerificationService`,`config/JwtAuthFilter`,`SecurityConfig`,`LoginRateLimitFilter`,`controller/InvestorController`,`application.yml`; FE `config/api.ts`,`store/index.ts`,`components/ContactVerification.tsx`,`views/InvestorOnboarding.tsx`,`App.tsx`.
- **Verified by:** 321/0/0 (`./mvnw -B test`, JDK 21); FE `tsc`+`vite build` clean; **live demo-profile boot** on a fresh DB (Flyway applied through V58, Hibernate `validate` passed, context started); full **E2E** (distributor submit-for-review → `DRAFT_AWAITING_INVESTOR`; finalize pre-attest → **400 blocked**; investor signup w/ Anita's PAN → `investorLinked=true`/ACTIVE/email-verified, OTP consumed; investor review shows the **same snapshot hash**; attest → finalize post-attest → **200**; DB confirms `READY_FOR_TRANSACTIONS` + all 3 consent rows + submission `ATTESTED`); **cross-portal isolation** (investor cookie → distributor endpoint and distributor cookie → investor endpoint both **401**, neither token honored on the other portal).
- **Deferred / residual:** no investor refresh-token rotation in Phase 1 (1h access cookie + re-login; `RefreshTokenService` is distributor-only); mobile/SMS OTP dormant; **compliance must supply final T&C / ownership / attestation rendered text + versions** (schema already stores text+hash+version); Phase 2 (transaction 2FA + withdrawal page w/ self-withdrawal warning) and Phase 3 (units×NAV holdings dashboard + 1-day/total/XIRR) are outlined in the plan only. All changes uncommitted.

---

## 2026-06-23 — Investor portal Phase 2 (transaction 2FA engine + withdrawal)

Built the SRS Phase-2 surface: a shared transaction-approval **2FA engine** that hard-gates every Cybrilla/FP money write behind investor OTP+consent on an immutable hashed snapshot, plus the investor **Approval Center** and **Withdrawal** pages. **`mvnw -B test` → 379 tests, 0 failures** (321 → 379; +58). FE `tsc`+`vite build` clean. Plan + decisions in `PHASE2_PLAN.md`. Built with parallel agents and **two rounds of adversarial security review** (a real money-movement bypass was found and fixed — see below). All changes uncommitted.

- **The gate.** New `transaction_approval_challenges` (V59) + `TransactionApprovalService` mirrors the Phase-1 onboarding hash/attest pattern. `createChallenge` freezes a stable-ordered JSON snapshot of the txn (purchase/SIP/redemption) + SHA-256; the investor moves it `PENDING → CHALLENGE_SENT → APPROVED` via consent-checkbox + email OTP. The single hard gate **`assertApprovedAndConsume(txnId, recomputedHash)`** — placed immediately before each provider write, inside the same `@Transactional` method — atomically asserts a live APPROVED challenge whose frozen hash still equals a freshly-recomputed snapshot hash and flips it to CONSUMED: a provider exception rolls the flip back (**retry-safe**), a replay finds CONSUMED not APPROVED (**exactly-once**), a post-approval distributor edit drifts the hash and is rejected. Gates planted at **Gate A** lumpsum payment chokepoint (`InvestorActionService.submitPurchaseForPayment`, covering the pending/submitted/confirmed branches after the idempotency short-circuit), **Gate B** SIP `createMandate`, **Gate C** redemption (`OrderService.createRedemption` split into `createRedemptionDraft` + `@Transactional submitRedemptionToProvider`).
- **Locked decisions (user, 2026-06-22):** (1) the legacy **unauthenticated investor-action token-confirm can no longer mutate the provider** — it hits the gate (no challenge → throws) and renders a friendly "log in to the portal to approve" page (never a 500); (2) **self-withdrawal executes a real Cybrilla redemption** via the gated `submitRedemptionToProvider` after 2FA; request-to-distributor only drafts (no provider call).
- **Cross-consume fix (review round 1).** The OTP was keyed only on `(email, purpose)`, so two concurrent challenges for one investor could cross-consume codes. Added a nullable `email_otps.reference_id` (V60) + reference-scoped `OtpService.requestOtp/verify` overloads; each approval OTP is bound to its `challengeId` (login/signup pass `null`, unchanged). Supersedes the original "one live challenge per account" idea (the Approval Center is a list — concurrent challenges are intended).
- **Money-movement bypass fix (review round 2).** The first gate pass guarded only the purchase `"pending"` branch; the lumpsum payment write (`createNetbankingPayment`) was still reachable ungated via the `"submitted"`/`"confirmed"` branches — **including from the unauthenticated legacy token endpoint**. Relocated Gate A to a single chokepoint after the idempotency short-circuit so all three minting branches consume exactly once. Also fixed: partial-withdrawal amount/units were silently discarded (now honored), and redemption submit made `@Transactional` for retry-safety. SIP confirmed: only one (gated) `createMandate` path, so recurring NACH debits run under a 2FA-consumed mandate (documented as by-design — the e-mandate authorization is the standing second factor).
- **Endpoints.** Distributor (`/api/v1/orders`): `request-investor-approval`, `resend-approval-link` (neither ever returns the OTP). Investor (`/api/v1/investor`, ROLE_INVESTOR, identity from the authenticated principal only): `GET/POST /approvals*`, `GET /holdings` (investor-scoped `PortfolioService.getInvestorHoldings`, surfaces `dataQuality` STALE/UNAVAILABLE, never an order-amount fallback), `POST /withdrawals/request-to-distributor`, `POST /withdrawals/self`. `InvestorAccountRepository.findByInvestorId` added. FR-2FA-007 test: a distributor token → **403** on the investor approve/otp endpoints.
- **Frontend (navy/Inter design system).** `components/TransactionApprovalPanel` (frozen snapshot read-only + unticked consent + 6-box OTP + approve, follows nextAction), `views/InvestorApprovalCenter` (`/investor/approvals`, EmptyState), `views/InvestorWithdrawal` (`/investor/withdrawals`: FR-RED disclosures, amount/units both unselected, explicit full-redemption, request-to-distributor + self-withdraw → 2FA), distributor "Submit for investor approval" + "Resend secure link" buttons (no OTP field ever shown). Routes in `App.tsx` InvestorShell.
- **Verified by:** 379/0/0 (`./mvnw -B test`, JDK 21); FE `tsc`+`vite build` clean; two independent adversarial security reviews → **READY** (gate atomicity/exactly-once, hash binding, no IDOR, no OTP leakage, no ungated provider write on any path incl. the legacy token route); **live demo-profile boot** confirmed Flyway applies through **V60** (V59 challenges + V60 email-otp reference), Hibernate `validate` passed, context started, and the live flow (distributor request-investor-approval → investor approvals list/detail) returned frozen snapshots with **no OTP leaked**. The live distributor-403 and provider-fires-only-after-approval negatives are additionally covered by the unit suite (FR-2FA-007 + the gate/bypass tests).
- **Residual:** the **self-withdrawal compliance warning** is a placeholder constant (`WITHDRAWAL_COMPLIANCE_WARNING` in `InvestorWithdrawal.tsx`) — user/compliance to supply final copy; transaction consent template text is also placeholder (schema stores text+hash+version). Phase 3 (units×NAV holdings dashboard + 1-day/total/XIRR) remains outlined only. All changes uncommitted.

---

## 2026-06-23 — Investor portal Phase 3 (holdings dashboard + XIRR)

Built the SRS Phase-3 investor **holdings dashboard**: authoritative units×NAV valuation, weighted cost basis, absolute/percent + 1-day returns, and money-weighted **XIRR**, with a `recharts` UI. **`mvnw -B test` → 393 tests, 0 failures** (379 → 393; +14). FE `tsc`+`vite build` clean. **No new tables/migrations** — holdings are derived from existing successful orders/redemptions + scheme NAV metadata (the fast, correct path; a separate ledger was unnecessary).

- **`service/HoldingsService.java`** — per investor, grouped by scheme: net units = settled purchase units (PURCHASE/LUMPSUM_PURCHASE/SIP in SUCCESSFUL/COMPLETED/ACTIVE) − successfully redeemed units (**never** from order amount); current value = net units × latest NAV from `ProductScheme.metadataJson`; weighted-average cost basis of held units; returns (absolute, percent, 1-day when a previous-day NAV exists else null — never zeroed); per-holding + portfolio XIRR. Honors locked decision #4: when NAV/units are missing `currentValue` is null + `dataQuality=UNAVAILABLE`; NAV without an as-of is `STALE`; never falls back to amount/zero.
- **`service/XirrCalculator.java`** — money-weighted return over irregular dated cashflows (purchases negative, redemptions + current value positive): NPV solved by Newton-Raphson seeded at 0.1 with a bracketed-bisection fallback; returns **null (never NaN/Infinity)** when undefined (<2 flows, all same sign, zero horizon, non-convergence).
- **Endpoint:** `GET /api/v1/investor/dashboard` (ROLE_INVESTOR, identity from the authenticated principal) → `InvestorDashboardResponse{holdings[], totals}`. The Phase-2 `GET /investor/holdings` (withdrawal page) is untouched.
- **Frontend:** `views/InvestorDashboard.tsx` — stat cards (total invested / current value / total return / portfolio XIRR), holdings table (scheme, folio, units, NAV+as-of, invested, current value, 1-day, total %, XIRR), and `recharts` allocation donut + invested-vs-current bar. STALE/UNAVAILABLE rows show a caveat and are excluded from charts (never a fabricated 0). Routed as the investor **landing page** (`/investor` → `/investor/dashboard`) in the InvestorShell.
- **Verified by:** 393/0/0 (`./mvnw -B test`, JDK 21); FE `tsc`+`vite build` clean. **XIRR pinned by `XirrCalculatorTest`** (3 hand-computed roots — doubling ≈+100%, multi-cashflow closed-form ≈6.81%, loss ≈−50% — plus degenerate-input + NaN/Inf guards). **`HoldingsServiceTest`** (7) pins the derivation: units×NAV valuation + cost basis + XIRR wiring, net-units = purchases−redemptions, UNAVAILABLE/STALE quality, fully-redeemed exclusion, pending-order exclusion, units-not-amount. FE dashboard field names verified to match the DTO. (Note: the original build agent died on a session usage cap after writing the service/endpoint/UI; the dashboard was completed + tested directly in the main thread.)
- **Residual:** Phase 3 reads NAV from existing `ProductScheme.metadataJson` — richer NAV history / a dedicated nav-snapshot table is a future optimization, not required. Investor portal Phases 1–3 are now functionally complete. All changes uncommitted.
