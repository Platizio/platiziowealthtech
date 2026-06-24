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
