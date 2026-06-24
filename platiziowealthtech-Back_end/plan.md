# Implementation Plan — Contact Verification, Self-Declaration, T&C, Pre-Verification Check

> Scope locked by the user (2026-06-16): **only** these four items —
> (A) Email & mobile **authentication via OTP**, (B) **Self-declaration** of email & mobile,
> (C) Acceptance of **Terms & Conditions**, (D) **Usage check** of the pre-verification API (#2, already built).
> Out of scope now: nominee (#4), transaction 2FA for purchase/redeem/SIP (#5–7), redemption-screen folio/units
> (#9), holdings calc (#10), regulated-defaults cleanup (#11). See [`flaws.md`](flaws.md) for those.
>
> **Decisions:** OTP channel — reuse the existing email OTP engine for email; add a pluggable `SmsSender`
> for mobile with a sandbox/logging impl (no real SMS gateway configured), real provider drops in later.
> **Working tree stays uncommitted**, like prior sessions. Build baseline: `mvnw -B test` → 281/0/0 GREEN.

## How A & B fit together
Email and mobile each get a **verification status** on the investor that can be satisfied **two ways**:
**OTP** (item A — send a code, verify it) **or** **self-declaration** (item B — the distributor attests the
contact belongs to the investor). Both write the same columns (`*_verified`, `*_verified_at`,
`*_verification_method = OTP | SELF_DECLARED`, `*_belongs_to`) and emit an audit event. The declared
`belongs_to` is then forwarded to FP (fixing today's hardcoded `"self"`).

---

## Tooling

| Step | Tool |
|------|------|
| Re-read exact code before each edit | `Read` / `Grep` |
| Java + SQL (Flyway) + React edits | `Edit` / `Write` |
| Backend verify (after every task) | `Bash` → `./mvnw -B test` |
| Frontend verify | `Bash` → `npx tsc --noEmit` (+ `npm run build`) |
| Parallel execution of disjoint tasks | `Agent` subagents (TDD per task; spec + quality review) |
| Spring/Flyway/React specifics | `context7` MCP if needed |

**Parallelization:** Task 1 (OTP engine) is a prerequisite for Task 2 and 3. Task 4 (T&C) and Task 5
(pre-verification) are independent of 1–3 and of each other → can run in parallel with Task 2/3.

---

## Shared facts (verified from the code)
- OTP engine: `service/OtpService.java` — keyed by `(email, purpose)`, SHA-256 hash, expiry, max-attempts,
  resend cooldown, single-live-code. `deliver()` (`:164`) routes only to email today.
- `domain/OtpPurpose.java` = `{LOGIN, SIGNUP}`. `domain/EmailOtp.java` — `email` col `length=320`, `purpose`
  col `length=20`, index on `(email, purpose)`. Repo: `EmailOtpRepository` (queries by email+purpose).
- Auth OTP endpoints: `controller/AuthController.java:79/89` (pattern to mirror).
- `domain/Investor.java` — has `email`/`mobileNumber` but **no** verification/declaration columns.
- FP contact payloads hardcode `belongs_to="self"`: `integration/RealCybrillaClient.java:2773` (email),
  `:2783` (phone).
- Latest Flyway migration is `V51`. Next free numbers: **V52, V53**.
- `domain/AuditEvent.java` + `service/AuditService.java` exist for audit trail.

---

## Task 1 — Generalize the OTP engine to email **and** SMS (backend, prerequisite)

**Files**
- Modify `domain/OtpPurpose.java` — add `EMAIL_VERIFICATION, MOBILE_VERIFICATION` (≤19 chars, fits `length=20`).
- Create `integration/sms/SmsSender.java` — interface `boolean send(String mobile, String message)`.
- Create `integration/sms/LoggingSmsSender.java` — default `@Service`/`@ConditionalOnMissingBean` impl that
  logs the code (mirrors the email-delivery-disabled branch); returns `false` so the dev-code path surfaces it
  on the `local` profile. (Real gateway implements `SmsSender` later and overrides.)
- Modify `service/OtpService.java`:
  - Inject `SmsSender`.
  - Add `private OtpChannel channelFor(OtpPurpose p)` → `MOBILE_VERIFICATION` ⇒ SMS, else EMAIL.
  - In `requestOtp(...)`: normalize + validate by channel (email regex for EMAIL; `^[6-9]\d{9}$` for SMS),
    and route `deliver(...)` by channel (`emailService.sendHtml` vs `smsSender.send`). Login/signup keep EMAIL
    behavior unchanged (they pass LOGIN/SIGNUP).
  - Add an SMS body builder (short text: "Your Platizio verification code is NNNNNN. Expires in M minutes.").

**TDD tests** (`OtpServiceTest`)
- `requestOtp(mobile, MOBILE_VERIFICATION)` stores a row, calls `SmsSender.send`, not `emailService`.
- `verify(mobile, MOBILE_VERIFICATION, code)` succeeds for the issued code, fails for a wrong code.
- Existing LOGIN/SIGNUP tests still pass (no email-path regression).

**Verify:** `./mvnw -B test`.

---

## Task 2 — Investor contact verification + self-declaration (backend)

**Files**
- Create migration `db/migration/V52__add_investor_contact_verification.sql`:
  ```sql
  ALTER TABLE investors
    ADD COLUMN email_verified            BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN email_verified_at         TIMESTAMP,
    ADD COLUMN email_verification_method VARCHAR(16),   -- OTP | SELF_DECLARED
    ADD COLUMN email_belongs_to          VARCHAR(24),   -- self | spouse | parent | ... (FP belongs_to)
    ADD COLUMN mobile_verified            BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN mobile_verified_at         TIMESTAMP,
    ADD COLUMN mobile_verification_method VARCHAR(16),
    ADD COLUMN mobile_belongs_to          VARCHAR(24);
  ```
- Create `domain/ContactVerificationMethod.java` — enum `{OTP, SELF_DECLARED}`.
- Modify `domain/Investor.java` — add the 8 fields + getters/setters (default `*_verified=false`).
- Create `service/InvestorContactVerificationService.java`:
  - `requestEmailOtp(UUID investorId, JwtAuthPrincipal actor)` → ownership check → `otpService.requestOtp(email, EMAIL_VERIFICATION)`.
  - `verifyEmailOtp(id, actor, code)` → `otpService.verify(...)` → set `emailVerified=true`, method `OTP`, `verifiedAt=now` → audit `EMAIL_OTP_VERIFIED`.
  - `requestMobileOtp` / `verifyMobileOtp` — same, MOBILE_VERIFICATION.
  - `declareContact(id, actor, channel, belongsTo)` → set `{email|mobile}Verified=true`, method `SELF_DECLARED`, `belongsTo` → audit `EMAIL_SELF_DECLARED`/`MOBILE_SELF_DECLARED`.
- Add endpoints to `controller/InvestorController.java` (reuse its `actorId(auth)` ownership pattern):
  - `POST /api/v1/investors/{id}/email/otp/request` · `POST .../email/otp/verify` (`{code}`)
  - `POST /api/v1/investors/{id}/mobile/otp/request` · `POST .../mobile/otp/verify` (`{code}`)
  - `POST /api/v1/investors/{id}/contact/declare` (`{channel: EMAIL|MOBILE, belongsTo}`)
- DTOs: `OtpVerifyCodeRequest {@NotBlank code}`, `ContactDeclarationRequest {@NotNull channel, @NotBlank belongsTo}`.
- **BUG-033:** create `validation/MobileFormat.java` (mirror `validation/PanFormat.java`) and apply
  `@Pattern("^[6-9]\\d{9}$")` to `InvestorCreateRequest.java:16` + `InvestorUpdateRequest.java:12`.
- **FP belongs_to fix:** in `RealCybrillaClient.java` thread `investor.getEmailBelongsTo()` /
  `getMobileBelongsTo()` into the email (`:2773`) and phone (`:2783`) payloads, defaulting to `"self"` when null.

**TDD tests**
- `InvestorContactVerificationServiceTest`: verify-OTP sets `emailVerified=true`+method OTP; declare sets
  method SELF_DECLARED + `belongsTo`; cross-distributor actor is rejected.
- `RequestBodyValidationTest`/DTO test: a non-`[6-9]\d{9}` mobile is rejected (BUG-033).
- `RealCybrillaClientTest`: email/phone payload `belongs_to` reflects the declared value, falls back to `self`.

**Verify:** `./mvnw -B test`.

---

## Task 3 — Frontend: contact verification + declaration UI

**Files**
- Create `Front_end/src/components/ContactVerification.tsx` — per channel: **"Send code" → enter code →
  Verify**, plus a **"Confirm this {email|mobile} belongs to the investor"** self-declaration with an
  ownership selector (`self`/`spouse`/`parent`/…). Calls the new backend endpoints via `config/api.ts`.
- Modify `Front_end/src/views/InvestorOnboarding.tsx` — mount `ContactVerification` beside the email
  (`:2478`) and mobile (`:2476`) inputs; surface verified state; stop sending the dead `contact_owner`
  free-text note (`:622`) in favour of the structured `belongsTo`.

**Verify:** `npx tsc --noEmit` + manual click-through.

---

## Task 4 — Terms & Conditions acceptance (backend + FE) — independent

**Files**
- Migration `db/migration/V53__add_terms_acceptances.sql`:
  ```sql
  CREATE TABLE terms_acceptances (
    id           UUID PRIMARY KEY,
    subject_type VARCHAR(16)  NOT NULL,   -- INVESTOR | DISTRIBUTOR
    subject_id   UUID         NOT NULL,
    document_key VARCHAR(64)  NOT NULL,   -- e.g. investor_tnc, fp_kyc_consent
    version      VARCHAR(32)  NOT NULL,
    accepted_at  TIMESTAMP    NOT NULL,
    ip_address   VARCHAR(64),
    user_agent   VARCHAR(512),
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
  );
  CREATE INDEX idx_terms_subject ON terms_acceptances (subject_type, subject_id, document_key);
  ```
- Create `domain/TermsAcceptance.java`, `repository/TermsAcceptanceRepository.java`,
  `service/TermsAcceptanceService.java` (`record(subjectType, subjectId, documentKey, version, ip, ua)` + audit `TERMS_ACCEPTED`).
- Endpoint on `InvestorController`: `POST /api/v1/investors/{id}/terms/accept` (`{documentKey, version}`),
  capturing `ip`/`userAgent` from `HttpServletRequest`; `GET .../terms` returns latest acceptances.
- Modify `Front_end/src/views/InvestorOnboarding.tsx` (`:751` `consentAcknowledged` gate) and
  `Onboarding.tsx` (`:387-390`) to actually **POST** the acceptance instead of using a client-only gate.

**TDD tests**
- `TermsAcceptanceServiceTest`: persists version+timestamp+subject; `GET` returns latest.
- Controller test: accept endpoint records a row and audits.

**Verify:** `./mvnw -B test` + `tsc`.

> Note: replacing the **placeholder legal text** (`Onboarding.tsx:164/179/193`) is a content/legal task, not
> engineering — flagged, not done here.

---

## Task 5 — Pre-verification API usage check (#2, already BUILT) — independent

The flow is wired end-to-end and unit-tested (`InvestorController.java:285/293` →
`InvestorKycService.createPreVerification:113` → `RealCybrillaClient.java:708`; tests
`InvestorKycServiceTest.java:51/80`). Deliverables here are confirmation + light hardening:
- **Parity:** add `investor_identifier` (the PAN) to the standalone `preVerificationPayload`
  (`InvestorKycService.java:1381-1390`) so it matches the onboarding payload and can do a readiness lookup.
- **Test:** assert the standalone payload now carries `investor_identifier` (extend `InvestorKycServiceTest`).
- **Document** the verified usage (readiness / PAN-name-DOB / bank lookup types in use) in `context.md`.
- **Optional (security, was deferred as BUG-028):** restrict `CybrillaDirectController` raw proxy to `ADMIN` +
  validate the body — list as a follow-up, do only if time remains.

**Verify:** `./mvnw -B test`.

---

## Verification & sequencing

1. **Task 1** (OTP engine) → `mvnw test` green.
2. **Task 2** (investor verification/declaration + BUG-033 + FP belongs_to) → `mvnw test` green.
3. **Task 4** and **Task 5** in parallel (independent) → `mvnw test` green.
4. **Task 3** (frontend) → `tsc --noEmit` + `npm run build`.
5. Final: full `./mvnw -B test` (target ≥ 281 + new tests, 0 failures) + frontend build; update
   `context.md`/`flaws.md` status for items 1, 2, 3, 8 → done. No run against a real RTA.

## Risk notes
- Storing a mobile number in `EmailOtp.email` (the destination key) is intentional reuse — documented in code;
  no schema rename to avoid touching the working login flow.
- `SmsSender` has no real provider yet → mobile OTP is delivered to logs on `local` (same pattern as email
  when delivery is disabled); production must configure a real `SmsSender` bean before go-live.
- Adding non-null-default boolean columns to `investors` is safe (default FALSE) and backfills existing rows.
- Keep all changes additive; do not alter the existing `OtpPurpose` LOGIN/SIGNUP behavior.

---

# Part 2 — Additional defects DF-07…DF-15 (verified against live code)

> Verified by a 9-agent workflow that re-opened each location (OCR'd line numbers in the source screenshot
> were corrected). **Demo deadline: 17 Jun (tomorrow).** Two corrections vs the screenshot: **DF-14** —
> `kyc_compliance_action='allow'` (`DemoDataSeeder.java:354`) is demo-only and `InvestorService.java:1886`
> SELF is a correct null-coalescing fallback, **not** blanket defaults; the real issue is the 5 resolver
> defaults. **DF-08** is **not** a demo blocker (the demo runs on `local`, where the seeder re-inserts).

## Summary

| DF | Title | Confirmed | Overlaps | Demo blocker | Effort |
|----|-------|-----------|----------|--------------|--------|
| DF-07 | Scheme NAV renders ₹0.00 everywhere | ✅ | new | **Yes** | M |
| DF-08 | V3 demo seed ships to production | ✅ | new | No | M |
| DF-09 | FE dropdowns auto-populate values | ✅ | FE half of #11 | No¹ | M |
| DF-10 | No dedicated T&C acceptance | ✅ | **Task 4** | **Yes** | L |
| DF-11 | `belongs_to` hardcoded `"self"` | ✅ | **Task 2** | No | M |
| DF-12 | Demo runs on `local` profile (seeds data, leaks OTP, auto-advances orders) | ✅ | new | **Yes** | M |
| DF-13 | Dev OTP printed on the login screen | ✅ | new (+DF-12) | **Yes** | S |
| DF-14 | Hardcoded compliance resolver defaults | ⚠️ partial | Task 2 (belongs_to) + new (resolvers) | No | M |
| DF-15 | Flyway sprawl V41–V51 (Anita fixups) | ✅ | BUG-022 | No (post-demo) | M |

¹ DF-09 is a SEBI "no auto-populated values" item (Req 1) — compliance-relevant even if the agent didn't tag
it a hard demo blocker.

## Execution specs (verified `file:line` + how to fix in place)

### DF-07 — Scheme NAV always ₹0.00 *(new, demo blocker, M)*
- **Root cause:** `ProductScheme` has no `nav`; seed `metadata_json` carries only `{"returns":…}`;
  `RealCybrillaClient.buildSchemeMetadata()` deep-copies the node without extracting NAV; the FE reads
  `nav`/`current_nav`/`last_nav` (always undefined) → `formatSchemeNav` returns the `₹0.00` literal.
- **Locations:** `domain/ProductScheme.java:12-51` · `db/migration/V3__seed_data.sql:64-66,71-72` ·
  `RealCybrillaClient.java:3014-3024` · `InvestorTransaction.tsx:34-39,119,129,648,708` ·
  `ProductMgmt.tsx:104,136,583` · `BackendFundDetailModal.tsx:333`.
- **Do (metadata-only, lowest risk for demo):** ① add a numeric `nav` to each scheme's `metadata_json` in
  `V3` **and** a new forward migration `V54__add_scheme_nav_metadata.sql` (`jsonb_set` with `coalesce` for
  NULL rows) so already-migrated DBs get NAV; ② in `buildSchemeMetadata` extract `nav`/`latest_nav` from the
  Cybrilla node into a canonical `nav` key (verify the real POA field name — POA scheme-plan may not return a
  live NAV; it usually comes from a separate AMFI feed); ③ FE: render `NAV —`/`Updating` instead of a
  misleading `₹0.00` when NAV is absent. **Label seeded NAV as static (not live pricing).**

### DF-08 — V3 demo seed ships to production *(new, not a demo blocker, M)*
- **Root cause:** `V3__seed_data.sql` inserts Bob + bank + 5 orders + redemption + lead + the `a@a.com`
  distributor; Flyway runs on **every** profile (`application.yml:40-50`, `FlywayConfig.java:27-33`); the
  `V34__remove_demo_seed.sql` the seeder Javadoc relies on **does not exist** (dir jumps V6→V36).
- **Do:** add a forward `V55__remove_demo_seed.sql` that `DELETE`s the demo rows by fixed UUID **in FK order**
  (lead_interactions → investor_leads → notifications → redemption_records → transaction_orders →
  audit_events → investor_bank_accounts → investors → the `4317cfd2…` distributor). **Keep** the canonical
  Alice distributor (`d9b2d63d…`) and the 3 `product_schemes` (a123/a222/a333). Fix the `DemoDataSeeder`
  Javadoc to name the real migration. Local dev is unaffected — `DemoDataSeeder` (`@Profile("local")`)
  re-inserts the same UUIDs. Don't edit V3 in place (checksum); use the forward DELETE.

### DF-09 — FE auto-populated dropdowns *(new = FE half of #11, Req 1, M)*
- **Locations:** defaults `InvestorOnboarding.tsx:247` (relationshipType `SELF`), `:293` (contactOwner
  `Self`), `:317` (taxResidency `India`), `:320` (PEP `No`); selects `:2444,2914,3147,3163`; guards
  `canNext` `:771,802`; `InvestorTransaction.tsx:248` (SIP frequency `MONTHLY` default), `:777` (select),
  `:93` (zod enum), `:254` (watch fallback).
- **Do:** swap each `|| 'X'` default to `|| ''` (keep the `X` only for **resume mode** — mirror in the
  `applyResumeInvestorToForm` lines `:532/540/549/552`); prepend a disabled empty `<option value="">` to each
  select; add the missing non-empty checks to `canNext` (`&& s4.contactOwner` in case 3; `&& s6.taxResidency
  && s6.politicalExp` in case 6); guard the `taxResidency !== 'India'` branch (`:3152`) against `''`; for SIP,
  drop the `MONTHLY` default + `watch('frequency') || 'MONTHLY'` fallback and require an active zod choice.
  **Note:** sandbox scenario loader (`:944`) doesn't set these — demo script must now select them.

### DF-10 — No T&C acceptance *(= Task 4, demo blocker, L)*
- Confirmed: Step 6 has only a FATCA checkbox (`InvestorOnboarding.tsx:3141-3188`), client-only, never
  persisted; no backend terms field/table. **Execute exactly as plan.md Task 4** (`V53` `terms_acceptances`
  + entity/repo/service + `POST /investors/{id}/terms/accept` + FE checkbox). **Keep the new
  `termsAccepted` flag separate from the FATCA `s6.declared`** — two distinct, independently-required boxes;
  on resume, re-fetch prior acceptance via `GET .../terms` so a returning investor isn't re-prompted.

### DF-11 — `belongs_to` hardcoded `"self"` *(= Task 2, M)*
- **Locations:** `RealCybrillaClient.java:2773` (email), `:2783` (phone); reader helper
  `onboardingNoteValue(...)` `:2739`; FE select `InvestorOnboarding.tsx:2915`, note write `:622`.
- **Do:** add a `mapBelongsTo(raw)` → `self|spouse|dependent_child|dependent_parent|guardian` (verify the
  exact FP enum before relying on it; default `self`). **Canonical (Task 2):** source from
  `investor.getEmailBelongsTo()/getMobileBelongsTo()` (new V52 columns). **Interim (if Task 2 not done by
  17 Jun):** source from `onboardingNoteValue(investor,"contact_owner")` (already captured) → `mapBelongsTo`.
  The 2-line payload edit is demo-safe; per-contact (separate email vs mobile relationship) needs the Task 2/3 UI.

### DF-12 — Demo on `local` profile *(new, demo blocker, M)*
- **Root cause:** default profile `local` (`application.yml:3`) loads `DemoDataSeeder` (`:32-35`,
  `upsertTestDistributor:276-298`), `DemoOrderAdvancer` (`:47-49,76-106`, auto-`SUCCESSFUL` every 5s), and the
  OTP `devCode` (`OtpService.java:118-123`).
- **Do:** launch the demo with `SPRING_PROFILES_ACTIVE` **explicitly** set to a non-`local` profile so none of
  the `@Profile("local")` beans load. If seeded personas are still wanted without the insecure bits, split:
  gate `DemoDataSeeder`/`DemoOrderAdvancer` behind `@ConditionalOnProperty` flags and keep `devCode`
  strictly `local`-only. Optional `ProfileGuard` that fails boot if `local` is active under a deployment
  marker. Non-`local` order completion uses the admin `PATCH /orders/{id}/status` (`OrderController.java:124`).
  **Coordinate with the demo script** — a non-`local` profile empties the seeded dashboard and orders dead-end
  at `PAYMENT_PENDING`.

### DF-13 — Dev OTP printed on screen *(new, demo blocker, S; overlaps DF-12)*
- **Locations:** `LoginPage.tsx:99,302,377,390,815-825` (renders `devCode`); `OtpService.java:118-123,197-199`;
  `OtpRequestResponse.java:11-16`; `application.yml:3`.
- **Do:** ① remove the `{otpGenerated && (…)}` amber banner (`LoginPage.tsx:815-825`) and its plumbing; ②
  backend defense-in-depth: stop returning the live code in the response by default — drop `devCode` (rely on
  the existing server log line) or gate it behind an explicit non-default `otp.expose-dev-code=false`. Note:
  hiding the UI alone isn't enough — on a `local` deploy the code is still in the raw JSON, so DF-12 + the
  backend change are both required.

### DF-14 — Hardcoded compliance resolver defaults *(partial; Task 2 + new, M)*
- **Confirmed blanket defaults (the real defect):** `resolveGender→female` (`:2666`),
  `resolveOccupation→service` (`:2679`, +unknown coercion `:2688`), `resolveIncomeSlab→upto_1lakh` (`:2695`),
  `resolveSourceOfWealth→salary` (`:2719`), `resolvePepDetails→not_applicable` (`:2736`); call site
  `investorProfilePayload` `:2464`. `belongs_to` (`:2773/2783`) overlaps Task 2.
- **NOT defects (corrected):** `DemoDataSeeder.java:354` `'allow'` is `@Profile("local")` demo-only;
  `InvestorService.java:1886` SELF is a correct null-coalescing fallback. **Leave both.**
- **Do (demo-safe):** Option A — `logger.warn` whenever a resolver falls back to a default (makes silent
  blanket-defaulting observable); Option B — externalise the constants to `application.yml`
  `@ConfigurationProperties`. **Don't** make them required for 17 Jun (Option C) — demo personas
  (Priya/Rahul/…) have no notes and rely on the fallbacks to pass order-readiness (occupation is immutable
  after first FP write). The real "collect, don't default" fix is the #11 work in `flaws.md`, post-demo.

### DF-15 — Flyway sprawl V41–V51 *(= BUG-022, post-demo, M, LOW risk)*
- Confirmed: V41–V51 are pure DML Anita fixups (fixed UUID `9b5c4d3e…`), zero DDL. V36–V40 are real schema
  DDL — **keep**. The V7–V35 "gap" is an intentional squash — benign.
- **Do (sequence carefully):** ① first add Anita's **final** state (V50/V51 end-state only, not the 11-step
  replay) to `DemoDataSeeder` guarded by `WHERE id='9b5c4d3e…'`; ② then delete the 11 `V41…V51` files; ③
  `FlywayConfig` repair-before-migrate + `ignore '*:missing'` handle orphan history rows — **boot-test against
  a DB that already ran V41–V51**, not just a clean DB; ④ flip BUG-022 to resolved in `bugs.md`.

## Revised demo-aware execution order (17 Jun)

**Tier 1 — demo blockers (do first):**
1. **DF-12 + DF-13** (S–M) — run on a non-`local` profile and remove the on-screen/raw-JSON OTP leak. *Decide
   the demo profile first — it changes what data the demo shows.*
2. **DF-07** (M) — seed NAV so screens stop showing ₹0.00.
3. **DF-10 / Task 4** (L) — T&C acceptance (mechanism; legal copy is a content task).

**Tier 2 — locked scope (this engagement):**
4. **Task 1 → Task 2** (OTP email/mobile auth + self-declaration; folds in **DF-11** and the `belongs_to`
   half of **DF-14**) → **Task 3** FE (folds in **DF-09** dropdown fixes).
5. **Task 5** (pre-verification usage check) — parallel, cheap.

**Tier 3 — post-demo hygiene:**
6. **DF-08** (remove demo seed from prod), **DF-15** (BUG-022 migration cleanup), **DF-14** resolver
   observability/parameterisation, and the rest of #11 from `flaws.md`.
