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
