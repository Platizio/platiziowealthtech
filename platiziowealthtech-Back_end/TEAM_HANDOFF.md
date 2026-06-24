# Platizio Wealthtech — Team Handoff (2026-06-16)

> **Demo deadline: 17 Jun.** Do Tier 1 first. All changes stay uncommitted until you confirm.
> Backend is Spring Boot (Java). Frontend is React + TypeScript + Vite. Build: `./mvnw -B test`.

---

## ✅ Tier 1 — IMPLEMENTED & VERIFIED (2026-06-16)

All three Tier 1 demo blockers are done and verified. **Changes are uncommitted.**

**Verification evidence**
- Backend: `./mvnw -B test` → **283 tests, 0 failures, 0 errors (BUILD SUCCESS)** — up from 281 baseline (+2 new security tests). Runs on JDK 21.
- Frontend: `npx tsc --noEmit` clean + `vite build` ✓ (built in ~2.4s).
- Migration `V52` SQL validated against **real Postgres 17**: first run `UPDATE 5` on a clean DB (UPDATE 4 in the idempotency fixture), second run `UPDATE 0` (idempotent), NULL metadata handled, existing `returns` preserved, pre-existing NAV not clobbered.
- Independent monitor sub-agent audit: **all three DFs PASS → Tier 1 READY.**

**DF-13 (OTP devCode leak) — done**
- `service/OtpService.java`: devCode now gated on `@Value("${app.otp.expose-dev-code:false}")` (the `Environment`/`isLocalProfile()` gate is removed). `dto/OtpRequestResponse.java` doc updated. New test `requestOtpNeverReturnsDevCodeWhenExposeDevCodeDisabled`.
- `Front_end/src/views/LoginPage.tsx`: amber "Dev mode — your OTP is" banner + all `otpGenerated`/`devCode` plumbing removed (grep-clean across `Front_end/src`).

**DF-12 (clean demo profile) — done**
- New config: `app.otp.expose-dev-code` (default **false**) and `app.demo.order-advancer-enabled` (default **false**) in `application.yml`; both **true** in `application-local.yml` (dev behavior preserved).
- New `src/main/resources/application-demo.yml` — run the demo with `SPRING_PROFILES_ACTIVE=demo`. No OTP leak, seeded personas, orders auto-complete (toggle `DEMO_ORDER_ADVANCER_ENABLED=false` to demo the manual flow), lazy token fetch so it boots without live creds.
- `init/DemoDataSeeder.java`: `@Profile({"local","demo"})` + corrected Javadoc (removed bogus V34 reference).
- `init/DemoOrderAdvancer.java`: `@Profile({"local","demo"})` + `@ConditionalOnProperty(app.demo.order-advancer-enabled)`.
- **Demo login tip:** use password login (e.g. `alice@example.com` / `Platizio@2024`); OTP login on the demo profile needs real SMTP or reading the code from server logs (by design).

**DF-07 (NAV ₹0.00) — done**
- New forward migration `db/migration/V52__add_scheme_nav_metadata.sql` (idempotent, NULL-safe, `text::jsonb`).
- `integration/RealCybrillaClient.java`: `buildSchemeMetadata` extracts a canonical numeric `nav` (new `firstNav(...)` helper).
- `views/InvestorTransaction.tsx` + `views/ProductMgmt.tsx`: render `—` (em dash) instead of a misleading `₹0.00`/`Rs 0`.

**Bonus security fix (monitor finding):** `service/PasswordResetService.java` leaked the raw reset token in the response on the local profile — the same leak class as DF-13. Now gated on `@Value("${app.password-reset.expose-dev-token:false}")` (true only on local), with a new disabled-case test. Not a demo blocker (demo profile never leaked it), but closed for consistency.

> **⚠️ Migration renumber for Tier 2:** `V52` is now taken by the NAV migration. The Tier 2 plan below originally reserved V52/V53 — **shift those to V53 (investor contact verification) and V54 (terms_acceptances)**, and V55 for the NAV/demo items it referenced. Pick the next free number at implementation time.

**Residual (not runtime-verified here):** the full `SPRING_PROFILES_ACTIVE=demo` boot (profile + conditional-bean wiring) was validated by unit tests + static review, not a live boot — do one demo-profile smoke boot before 17 Jun.

---

## ✅ Tier 2 (partial) — Supabase email/mobile contact verification — IMPLEMENTED & VERIFIED (email OTP LIVE 2026-06-17; mobile/SMS deferred)

Per the chosen approach (Supabase Auth for email + phone OTP), the **investor email/mobile contact-verification + self-declaration** slice is built and verified. **Uncommitted.**

**Architecture:** the Spring backend mediates all Supabase calls (`POST {url}/auth/v1/otp` and `/auth/v1/verify`) — the browser only ever calls Platizio `/api/v1`, never Supabase. A pluggable `SupabaseAuthClient` has a real HTTP impl and a disabled fallback so local/demo/tests run without a Supabase project. **As of 2026-06-17 a real project IS configured** (ref `volmpsvzrbzialnrrqjk`) with `real-client-enabled=true` in the git-ignored `.env`, so the real client is active and **email OTP is live-verified**; **mobile/SMS OTP is deferred** (Supabase Phone provider disabled, `sms-enabled=false`). The existing distributor JWT login is untouched.

**Verification evidence**
- Backend: `./mvnw -B test` → **292 tests, 0 failures** (283 → 292, +9 Tier 2 tests). JDK 21.
- Frontend: `tsc --noEmit` clean + `vite build` ✓.
- Migrations `V52` (NAV) and `V53` (contact columns) validated against **real Postgres 17** (apply + idempotent + NOT NULL backfill).
- Independent monitor sub-agent audit: **all 5 areas PASS → Tier 2 READY** (boundary intact, dev-code backdoor gated to the disabled client only, ownership enforced on all 6 endpoints, the two `SupabaseAuthClient` beans are provably exclusive-and-exhaustive over one property).

**What was built**
- `integration/SupabaseAuthClient.java` (interface) + `RealSupabaseAuthClient` (RestClient → GoTrue) + `DisabledSupabaseAuthClient` (no-op, dev code `000000`) + `integration/auth/SupabaseAuthProperties.java` + `SupabaseAuthException`.
- `service/InvestorContactVerificationService.java` — request/verify email & mobile OTP, self-declare, status; ownership-checked; audits each action; mobile sent in E.164.
- `controller/InvestorController.java` — 6 endpoints: `POST /{id}/email|mobile/otp/request`, `.../otp/verify`, `POST /{id}/contact/declare`, `GET /{id}/contact/status`.
- `domain/Investor.java` + `V53__add_investor_contact_verification.sql` — 8 columns (`*_verified`, `*_verified_at`, `*_verification_method`, `*_belongs_to`). Enums `ContactChannel`, `ContactVerificationMethod`.
- `validation/MobileFormat.java` + `@Pattern` on `InvestorCreateRequest`/`InvestorUpdateRequest` (BUG-033).
- `RealCybrillaClient` — `belongs_to` now sourced from the declared value, default `self` (DF-11), replacing the hardcoded `"self"`.
- FE `components/ContactVerification.tsx` mounted beside the onboarding email & mobile inputs (Send OTP → verify, or self-declare with relationship).

**Live status (2026-06-17):** `SUPABASE_REAL_CLIENT_ENABLED=true`, `SUPABASE_URL` (project `volmpsvzrbzialnrrqjk`), and `SUPABASE_ANON_KEY` are set in the git-ignored `.env`, so the real client is active and **email OTP is live-verified** — a live `POST /auth/v1/otp` (email) returned 200 and `POST /auth/v1/verify` returned 403 `otp_expired` for a bad code (the 4xx the client maps to "not verified"). **For the email to carry a 6-digit code, the dashboard Magic Link template must include `{{ .Token }}`.** **Mobile/SMS OTP is deferred** (`sms-enabled=false`): the Supabase Phone provider is disabled (likely needs a paid plan), so the `/mobile/otp` endpoints return a clean **400** ("use self-declaration") and `contact/status` reports `smsOtpEnabled=false` so the UI hides mobile OTP. To enable later: turn on Authentication → Providers → Phone (with an SMS provider) and set `SUPABASE_SMS_ENABLED=true`. **Security:** rotate the `service_role` / secret / JWT keys shared during setup. The disabled client (dev code `000000`) is the fallback only when `real-client-enabled` is false.

**Still open in Tier 2 (NOT Supabase, not built yet):** Terms & Conditions acceptance (table + endpoint + FE checkbox) and the pre-verification `investor_identifier` parity check. These are independent of the Supabase work.

**Known cosmetic note:** the FE shows the literal dev code `000000` only when `otpEnabled=false` (i.e. Supabase disabled / non-prod); it never renders once Supabase is configured.

---

## Codebase quickmap

```
Back_end/platiziowealthtech-Back_end/src/main/java/com/platizio/wealthtech/
  controller/    → REST endpoints (AuthController, InvestorController, OrderController …)
  service/       → business logic (OtpService, InvestorService, InvestorKycService …)
  integration/   → provider adapters (RealCybrillaClient, EmailService, auth token service)
  domain/        → JPA entities + enums (Investor, OtpPurpose, EmailOtp …)
  dto/           → request/response objects
  repository/    → Spring Data JPA repos
  init/          → DemoDataSeeder, DataInitializer

Back_end/.../resources/db/migration/   → Flyway files V1–V51 (latest = V51)
Front_end/src/views/                   → InvestorOnboarding.tsx, InvestorTransaction.tsx, LoginPage.tsx …
Front_end/src/components/              → shared components
```

**Verification after any backend change:** `cd Back_end/platiziowealthtech-Back_end && ./mvnw -B test`
**Verification after any frontend change:** `cd Front_end && npx tsc --noEmit`

---

## Tier 1 — Demo blockers (do these first, 17 Jun)

### DF-13 — Dev OTP code printed on the login screen *(S effort, ~1h)*

**Problem:** On the local profile, the backend returns the raw OTP in the JSON response, and the
frontend displays it in an amber banner. Anyone watching the screen or the network tab can see it.

**Backend fix — stop returning the code in the response:**

File: `service/OtpService.java` at lines 118–123.

```java
// Current (lines 118-123) — returns devCode when delivery fails + isLocalProfile()
if (!delivered && isLocalProfile()) {
    return new OtpRequestResponse(maskedTarget, cooldownSeconds, plainCode); // remove plainCode
}
return new OtpRequestResponse(maskedTarget, cooldownSeconds, null);          // already done when delivered
```

Change the failure branch to pass `null` as the third arg instead of `plainCode`. The plain code is
already logged by the service (check the log line a few lines above) — the log is enough for dev.

File: `dto/OtpRequestResponse.java` — leave the `devCode` field in the DTO for now (removing it
would break the compile); just stop populating it from `OtpService`. If you want a hard removal,
delete the field and fix the constructor call.

**Frontend fix — remove the amber banner:**

File: `Front_end/src/views/LoginPage.tsx` lines 815–825. Delete the entire block:
```tsx
{otpGenerated && (
  <div className="...amber banner...">
    <p>Dev code: {otpGenerated.devCode}</p>
  </div>
)}
```
Also remove the `otpGenerated` state reads at lines 302 and 377 that populate it, and the
`setOtpGenerated` calls.

**Verify:** `./mvnw -B test` (no new tests needed — existing OTP tests pass; manually confirm no
amber box on login page).

---

### DF-12 — Demo runs on the `local` Spring profile (seeds fake data, auto-advances orders) *(M effort, ~2h)*

**Problem:** The default `SPRING_PROFILES_ACTIVE=local` activates `DemoDataSeeder` (inserts
`a@a.com` distributor + Bob investor on every boot) and `DemoOrderAdvancer` (auto-advances
every order to SUCCESSFUL every 5 seconds). This means:
1. The dashboard auto-fills with fake Bob orders — not suitable for a real demo.
2. Orders never wait at PAYMENT_PENDING, so payment flows can't be shown step-by-step.
3. The `devCode` (DF-13 above) is active.

**Fastest fix for 17 Jun — run the demo on a non-`local` profile:**

When starting the backend for the demo, set the env variable:
```
SPRING_PROFILES_ACTIVE=prod java -jar wealthtech.jar
```
Or in a run config / docker-compose, override `spring.profiles.active=prod`.

This kills all `@Profile("local")` beans. The dashboard starts empty — the demo presenter creates
an investor and places an order live.

**If you need seeded demo personas without the insecure bits (recommended):**

Add a `demo` profile. Create `src/main/resources/application-demo.yml` with any demo-specific
overrides. Then annotate `DemoDataSeeder` and `DemoOrderAdvancer` with `@Profile({"local","demo"})`
instead of just `@Profile("local")`. Inside `DemoDataSeeder`, add a guard around the OTP dev-code
return in `OtpService` using a property:

In `application.yml`, add:
```yaml
otp:
  expose-dev-code: false   # set to true only for local
```
In `application-local.yml` (create if it doesn't exist):
```yaml
otp:
  expose-dev-code: true
```
Then in `OtpService.java:197-199`, change the `isLocalProfile()` guard to read the property:
```java
@Value("${otp.expose-dev-code:false}")
private boolean exposeDevCode;

// in deliver() failure branch:
if (!delivered && exposeDevCode) { ... }
```
This way the `demo` profile re-inserts personas but doesn't leak OTP codes.

**Order completion without `DemoOrderAdvancer`:** use the admin endpoint
`PATCH /api/v1/orders/{id}/status` (see `OrderController.java:124`) to manually advance order state
during the demo.

**Verify:** boot with `SPRING_PROFILES_ACTIVE=prod`, hit `GET /api/v1/investors` — returns empty
list (no Bob). Login as `a@a.com` / `Ok@123456` still works (distributor is in V3 seed, not
demo-only). Run `./mvnw -B test` to confirm all tests still pass.

---

### DF-07 — Scheme NAV shows ₹0.00 everywhere *(M effort, ~2–3h)*

**Problem:** The `ProductScheme` entity has no NAV field. The seed data's `metadata_json` only has
returns data. `RealCybrillaClient.buildSchemeMetadata()` copies catalogue metadata without extracting
a NAV key. The frontend looks for `['nav','current_nav','last_nav']` — finds nothing — so
`formatSchemeNav` (line 34–39 of `InvestorTransaction.tsx`) always returns `₹0.00`.

**Fix A — seed static NAV into existing scheme metadata (lowest risk for demo):**

Step 1: Edit `db/migration/V3__seed_data.sql` lines 64–72. Each scheme has a
`metadata_json` value. Add `"nav": 50.00` (or the appropriate value) to each:

```sql
-- Line 64 (scheme a123, e.g. HDFC Equity)
('a123...', ..., '{"returns":{"1y":12.5}, "nav":52.34}', ...)
```

**Important:** Do not modify V3 if it has already run on your database (Flyway will checksum-fail).
In that case, use a forward migration instead:

Create `db/migration/V54__add_scheme_nav_metadata.sql`:
```sql
UPDATE product_schemes
SET metadata_json = jsonb_set(
    COALESCE(metadata_json, '{}'),
    '{nav}',
    '52.34',
    true
)
WHERE isin = 'INF179K01VX9';   -- repeat for each scheme ISIN

UPDATE product_schemes
SET metadata_json = jsonb_set(
    COALESCE(metadata_json, '{}'),
    '{nav}',
    '143.22',
    true
)
WHERE isin = 'INF204K01FO0';   -- example; use your actual ISINs
```

Step 2: In `RealCybrillaClient.java` at `buildSchemeMetadata` (lines 3014–3024), extract NAV from
the provider response and add it to the metadata node:
```java
// After deep-copying the Cybrilla catalogue node, add:
JsonNode navNode = cybrillaScheme.path("nav"); // or "latest_nav" — check real POA field name
if (!navNode.isMissingNode()) {
    ((ObjectNode) meta).set("nav", navNode);
}
```

Step 3: Frontend — make ₹0.00 not misleading. In `InvestorTransaction.tsx:34-39`:
```tsx
function formatSchemeNav(meta?: MetaRecord[]): string {
  const val = firstMetaValue(meta, ['nav', 'current_nav', 'last_nav']);
  if (!val) return '—';      // show dash instead of ₹0.00
  return `₹${parseFloat(val).toFixed(2)}`;
}
```
Also add a tooltip: "NAV as of [date]. Static seed value — live NAV pending AMFI feed integration."

**Verify:** restart backend (new Flyway migration runs), open InvestorTransaction, NAV column shows
actual numbers not ₹0.00. Run `./mvnw -B test`.

---

## Tier 2 — Core features (locked scope, after demo blockers)

> These five tasks are ordered by dependency. Run tests after each task.

### Task 1 — Generalize the OTP engine to support mobile SMS *(prerequisite for Tasks 2 & 3)*

**Problem:** Today `OtpPurpose` only has `LOGIN` and `SIGNUP`. The OTP engine always delivers to
email. We need to add email-verification and mobile-verification purposes, and route mobile OTPs
to SMS.

**Step 1: Add new OTP purposes.**

File: `domain/OtpPurpose.java` (currently just `{LOGIN, SIGNUP}`).
Add two values: `EMAIL_VERIFICATION` and `MOBILE_VERIFICATION`. Both are ≤19 chars and fit the
`length=20` column in `email_otps.purpose`. No schema migration needed.

```java
public enum OtpPurpose {
    LOGIN,
    SIGNUP,
    EMAIL_VERIFICATION,
    MOBILE_VERIFICATION
}
```

**Step 2: Create the SmsSender interface.**

Create `integration/sms/SmsSender.java`:
```java
package com.platizio.wealthtech.integration.sms;

public interface SmsSender {
    /** @return true if sent successfully */
    boolean send(String mobileNumber, String message);
}
```

**Step 3: Create the logging (no-op) SMS sender.**

Create `integration/sms/LoggingSmsSender.java`:
```java
package com.platizio.wealthtech.integration.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnMissingBean(SmsSender.class)  // real gateway overrides this
public class LoggingSmsSender implements SmsSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public boolean send(String mobileNumber, String message) {
        log.info("[DEV-ONLY] SMS to {}: {}", mobileNumber, message);
        return false; // false → triggers the devCode path on local profile
    }
}
```

**Step 4: Update OtpService to route by channel.**

File: `service/OtpService.java`.

At the top, inject `SmsSender`:
```java
@Autowired private SmsSender smsSender;
```

Add a helper enum and routing method inside the class:
```java
private enum OtpChannel { EMAIL, SMS }

private OtpChannel channelFor(OtpPurpose purpose) {
    return purpose == OtpPurpose.MOBILE_VERIFICATION ? OtpChannel.SMS : OtpChannel.EMAIL;
}
```

In `requestOtp(String target, OtpPurpose purpose, ...)` at line ~90:
- Before hashing, validate by channel:
  - EMAIL channel: validate `target` matches email regex
  - SMS channel: validate `target` matches `^[6-9]\d{9}$` (10-digit Indian mobile)
- In the `deliver()` section (around line 164), replace the hardcoded email call:
```java
private boolean deliver(String target, String plainCode, OtpPurpose purpose) {
    if (channelFor(purpose) == OtpChannel.SMS) {
        String msg = "Your Platizio verification code is " + plainCode + ". Expires in 5 minutes.";
        return smsSender.send(target, msg);
    }
    // existing email delivery
    return emailService.sendHtml(target, "Your OTP", buildEmailBody(plainCode));
}
```

Existing `LOGIN`/`SIGNUP` behavior is unchanged — they pass `EMAIL_VERIFICATION` for email delivery.

**Tests to write** (in `OtpServiceTest`):
1. `requestOtp("9876543210", MOBILE_VERIFICATION)` → `SmsSender.send` is called, not `emailService`.
2. `verify("9876543210", MOBILE_VERIFICATION, correctCode)` → returns success.
3. `verify("9876543210", MOBILE_VERIFICATION, wrongCode)` → returns failure/INVALID_CODE.
4. Existing LOGIN + SIGNUP tests unchanged (regression check).

**Verify:** `./mvnw -B test` → all existing 281 tests still green + new tests green.

---

### Task 2 — Investor contact verification + self-declaration (backend)

**Problem:** The `investors` table has no columns to record whether email/mobile has been verified
or declared, and by what method. `RealCybrillaClient` hardcodes `belongs_to="self"` for both email
and phone — a compliance problem (the investor's number might belong to a spouse or parent).

**Step 1: Flyway migration — add 8 columns to the investors table.**

Create `db/migration/V52__add_investor_contact_verification.sql`:
```sql
ALTER TABLE investors
  ADD COLUMN email_verified            BOOLEAN     NOT NULL DEFAULT FALSE,
  ADD COLUMN email_verified_at         TIMESTAMP,
  ADD COLUMN email_verification_method VARCHAR(16),
  ADD COLUMN email_belongs_to          VARCHAR(24),
  ADD COLUMN mobile_verified           BOOLEAN     NOT NULL DEFAULT FALSE,
  ADD COLUMN mobile_verified_at        TIMESTAMP,
  ADD COLUMN mobile_verification_method VARCHAR(16),
  ADD COLUMN mobile_belongs_to         VARCHAR(24);
```
(`*_verification_method` values will be `OTP` or `SELF_DECLARED`; `*_belongs_to` values mirror
FP's enum: `self`, `spouse`, `dependent_child`, `dependent_parent`, `guardian`.)

**Step 2: Add an enum.**

Create `domain/ContactVerificationMethod.java`:
```java
public enum ContactVerificationMethod {
    OTP,
    SELF_DECLARED
}
```

**Step 3: Add the 8 fields to the Investor entity.**

File: `domain/Investor.java`. Add fields with JPA column mappings and getters/setters. Defaults
for the boolean fields are `false` (matching the SQL default).

**Step 4: Create the service.**

Create `service/InvestorContactVerificationService.java` with these methods:

```
requestEmailOtp(investorId, actor)
  → ownership check
  → otpService.requestOtp(investor.getEmail(), OtpPurpose.EMAIL_VERIFICATION)

verifyEmailOtp(investorId, actor, code)
  → otpService.verify(investor.getEmail(), EMAIL_VERIFICATION, code)
  → if success: investor.emailVerified=true, emailVerifiedAt=now(), emailVerificationMethod=OTP
  → auditService.record(investor, "EMAIL_OTP_VERIFIED")

requestMobileOtp / verifyMobileOtp  — same pattern, using MOBILE_VERIFICATION + investor.getMobileNumber()

declareContact(investorId, actor, channel, belongsTo)
  → channel = EMAIL: set emailVerified=true, emailVerificationMethod=SELF_DECLARED, emailBelongsTo=belongsTo
  → channel = MOBILE: same for mobile columns
  → auditService.record(investor, "EMAIL_SELF_DECLARED" or "MOBILE_SELF_DECLARED")
```

All methods must check that the acting distributor owns the investor (see how `InvestorService`
checks `investor.getDistributorId().equals(actor.distributorId())`).

**Step 5: Add endpoints to InvestorController.**

File: `controller/InvestorController.java`. Add:
```
POST /api/v1/investors/{id}/email/otp/request     → requestEmailOtp
POST /api/v1/investors/{id}/email/otp/verify       → body: { "code": "123456" }
POST /api/v1/investors/{id}/mobile/otp/request    → requestMobileOtp
POST /api/v1/investors/{id}/mobile/otp/verify      → body: { "code": "123456" }
POST /api/v1/investors/{id}/contact/declare        → body: { "channel": "EMAIL", "belongsTo": "spouse" }
```

Look at the existing `POST /investors/{id}/kyc-request` handler pattern for ownership checks and
response format to copy.

Create two DTOs:
- `dto/OtpVerifyCodeRequest.java` — `@NotBlank String code`
- `dto/ContactDeclarationRequest.java` — `@NotNull String channel` (EMAIL/MOBILE), `@NotBlank String belongsTo`

**Step 6: Fix the BUG-033 mobile validation.**

Look at `validation/PanFormat.java` for the pattern. Create an identical `validation/MobileFormat.java`
with pattern `^[6-9]\\d{9}$`. Apply the annotation on:
- `dto/InvestorCreateRequest.java:16` (the `mobileNumber` field)
- `dto/InvestorUpdateRequest.java:12` (same)

**Step 7: Fix the hardcoded `belongs_to` in RealCybrillaClient.**

File: `integration/RealCybrillaClient.java`.

At line 2773 (email payload) — replace `"self"` with:
```java
private String resolvedEmailBelongsTo(Investor investor) {
    String raw = investor.getEmailBelongsTo();
    return (raw != null && !raw.isBlank()) ? raw : "self";
}
```
Then use `resolvedEmailBelongsTo(investor)` where `"self"` is hardcoded.

At line 2783 (phone payload) — same pattern using `investor.getMobileBelongsTo()`.

**Interim fix (if Task 2 UI not done before 17 Jun):** use the existing `onboardingNoteValue(investor,
"contact_owner")` helper at line 2739 as the source and pass it through the same mapper — this reads
the `contact_owner` string already captured in `onboardingNotes`.

**Tests to write** (in `InvestorContactVerificationServiceTest`):
1. `verifyEmailOtp` sets `emailVerified=true` and `emailVerificationMethod=OTP`.
2. `declareContact(EMAIL, "spouse")` sets `emailVerified=true`, method=`SELF_DECLARED`, `emailBelongsTo="spouse"`.
3. A distributor who doesn't own the investor gets `403` / ownership exception.
4. Mobile regex validation rejects `"12345"` (DTO validation test).
5. `RealCybrillaClient` email payload `belongs_to` = declared value, falls back to `"self"` when null.

**Verify:** `./mvnw -B test`.

---

### Task 3 — Frontend: contact verification + declaration UI

**Problem:** The onboarding form has bare email and mobile inputs with no way to verify or declare
them. The `contact_owner` note is written as a free-text string into `onboardingNotes` (line 622
of `InvestorOnboarding.tsx`) and never structured.

**Step 1: Create the ContactVerification component.**

Create `Front_end/src/components/ContactVerification.tsx`.

It receives props: `investorId`, `channel` (`"email"|"mobile"`), `currentValue` (the email/mobile
string), `isVerified` (bool), `onVerified()` callback.

It renders two options side by side:
- **"Verify with OTP"** — a "Send code" button → input field for 6-digit code → "Submit" button.
  Calls `POST /api/v1/investors/{id}/{channel}/otp/request` then
  `POST /api/v1/investors/{id}/{channel}/otp/verify`.
- **"Self-declare"** — a dropdown: `self / spouse / parent / guardian` + a "Confirm" button.
  Calls `POST /api/v1/investors/{id}/contact/declare`.

Show a green tick + "Verified (OTP)" or "Verified (Declared — spouse)" once done. The `isVerified`
prop controls whether to show the verification widget at all or just the tick.

**Step 2: Mount it in InvestorOnboarding.**

File: `Front_end/src/views/InvestorOnboarding.tsx`.

Near line 2478 (email input) and 2476 (mobile input), add the component after each field:
```tsx
{investorId && (
  <ContactVerification
    investorId={investorId}
    channel="email"
    currentValue={s4.email}
    isVerified={emailVerified}
    onVerified={() => setEmailVerified(true)}
  />
)}
```

Remove the dead `contact_owner` free-text note write at line 622.

**Step 3: Also fix DF-09 dropdown defaults in the same file.**

In `InvestorOnboarding.tsx`, change these `useState` defaults from pre-populated to empty:
- Line 247: `relationshipType: 'SELF'` → `''`
- Line 293: `contactOwner: 'Self'` → `''`
- Line 317: `taxResidency: 'India'` → `''`
- Line 320: `politicalExp: 'No'` → `''`

For each corresponding `<select>`, add a disabled empty first option:
```tsx
<option value="" disabled>Select…</option>
```

Update the `canNext` guards (around lines 771, 802) to require these fields before allowing next:
```js
case 3: return s3.name && ... && s4.contactOwner !== '';
case 6: return s6.taxResidency !== '' && s6.politicalExp !== '';
```

In `InvestorTransaction.tsx` line 248, remove `defaultValue='MONTHLY'` from the SIP frequency
select, and remove the `|| 'MONTHLY'` fallback at line 254. The zod schema (line 93) should require
an active selection.

**Verify:** `npx tsc --noEmit` → zero errors. Manual click-through of onboarding flow.

---

### Task 4 — Terms & Conditions acceptance (backend + frontend)

**Problem:** The current onboarding has a FATCA consent checkbox (line 3172 `InvestorOnboarding.tsx`)
that is client-only and never persisted. There is no backend record of an investor accepting T&C.
SEBI requires this to be stored with a timestamp.

**Step 1: Flyway migration — create the terms_acceptances table.**

Create `db/migration/V53__add_terms_acceptances.sql`:
```sql
CREATE TABLE terms_acceptances (
  id           UUID         PRIMARY KEY,
  subject_type VARCHAR(16)  NOT NULL,
  subject_id   UUID         NOT NULL,
  document_key VARCHAR(64)  NOT NULL,
  version      VARCHAR(32)  NOT NULL,
  accepted_at  TIMESTAMP    NOT NULL,
  ip_address   VARCHAR(64),
  user_agent   VARCHAR(512),
  created_at   TIMESTAMP    NOT NULL,
  updated_at   TIMESTAMP    NOT NULL
);
CREATE INDEX idx_terms_subject ON terms_acceptances (subject_type, subject_id, document_key);
```

(`subject_type` = `INVESTOR` or `DISTRIBUTOR`; `document_key` = `investor_tnc` / `fp_kyc_consent`;
`version` = `"v1.0"` or a date string.)

**Step 2: Create entity, repo, service.**

- `domain/TermsAcceptance.java` — JPA entity mapping to the table above.
- `repository/TermsAcceptanceRepository.java` — Spring Data repo; add:
  `Optional<TermsAcceptance> findTopBySubjectTypeAndSubjectIdAndDocumentKeyOrderByAcceptedAtDesc(...)`
- `service/TermsAcceptanceService.java`:
  ```
  record(subjectType, subjectId, documentKey, version, ipAddress, userAgent)
    → new TermsAcceptance(UUID.randomUUID(), subjectType, subjectId, documentKey,
                          version, now, ipAddress, userAgent, now, now)
    → repository.save(...)
    → auditService.record("TERMS_ACCEPTED")

  getLatest(subjectType, subjectId, documentKey) → Optional<TermsAcceptance>
  ```

**Step 3: Add endpoints to InvestorController.**

```
POST /api/v1/investors/{id}/terms/accept
  body: { "documentKey": "investor_tnc", "version": "v1.0" }
  → capture HttpServletRequest for IP + User-Agent
  → termsAcceptanceService.record("INVESTOR", investorId, documentKey, version, ip, ua)
  → 200 OK

GET /api/v1/investors/{id}/terms
  → returns list of latest acceptance records for this investor
```

**Step 4: Wire in the frontend.**

File: `Front_end/src/views/InvestorOnboarding.tsx` near line 751 (the `consentAcknowledged` gate):

Add a separate checkbox ABOVE the FATCA box:
```tsx
<input type="checkbox" id="tnc" checked={tncAccepted} onChange={e => setTncAccepted(e.target.checked)} />
<label htmlFor="tnc">I confirm that the investor has read and accepted the Platizio Terms & Conditions (v1.0).</label>
```

When the user submits step 6, after the investor is created/updated, call:
```
POST /api/v1/investors/{investorId}/terms/accept
  { documentKey: "investor_tnc", version: "v1.0" }
```

Keep the existing FATCA `s6.declared` checkbox as-is — it is a separate legal declaration.

On resume (re-opening an existing investor), call `GET /api/v1/investors/{id}/terms` and if an
acceptance record exists, pre-check the box and disable it (they already accepted).

**Tests to write:**
1. `TermsAcceptanceServiceTest`: `record(...)` persists version + timestamp + subject.
2. `record(...)` called twice → two records exist (idempotency: re-acceptance is allowed, stored).
3. Controller test: `POST .../terms/accept` returns 200 and creates a DB row.

**Verify:** `./mvnw -B test` + `tsc --noEmit`.

---

### Task 5 — Pre-verification API usage check (already built, light hardening only)

**Problem:** The pre-verification flow is already wired and tested. One small gap: the standalone
pre-verification payload (for the readiness lookup) at `InvestorKycService.java:1381-1390` does not
include `investor_identifier` (the PAN), so it can only do the bank lookup type, not the readiness
lookup.

**Fix:** In `InvestorKycService.java` at lines 1381–1390, add the PAN to the standalone payload:
```java
payload.put("investor_identifier", investor.getPan());
```
(The onboarding payload already has this — make them consistent.)

**Test:** Extend `InvestorKycServiceTest.java` (test at line 80) to assert the standalone payload
contains `investor_identifier`.

**Verify:** `./mvnw -B test`.

---

## Tier 3 — Post-demo hygiene (do after demo, no rush)

### DF-08 — Demo seed data will reach production

**Problem:** `db/migration/V3__seed_data.sql` inserts Bob, his bank account, 5 orders, a
redemption, the `a@a.com` distributor, and more. Flyway runs on every profile. The `V34` cleanup
migration referenced in `DemoDataSeeder.java`'s Javadoc does not exist.

**Fix:** Create `db/migration/V55__remove_demo_seed.sql`. Delete the demo rows by their fixed UUIDs
in FK order (children before parents):

```sql
DELETE FROM lead_interactions WHERE investor_lead_id = '...';
DELETE FROM investor_leads WHERE investor_id IN ('<bob-uuid>', ...);
DELETE FROM notifications WHERE ...;
DELETE FROM redemption_records WHERE investor_id = '<bob-uuid>';
DELETE FROM transaction_orders WHERE investor_id = '<bob-uuid>';
DELETE FROM audit_events WHERE investor_id = '<bob-uuid>';
DELETE FROM investor_bank_accounts WHERE investor_id = '<bob-uuid>';
DELETE FROM investors WHERE id = '<bob-uuid>';
DELETE FROM users WHERE id = '<acom-distributor-uuid>';  -- if not the Alice distributor
```

Check all UUIDs in V3. Keep: the canonical distributor and the 3 product schemes.
Fix `DemoDataSeeder.java:9` Javadoc to name `V55` instead of the non-existent `V34`.

---

### DF-14 — Compliance resolver defaults are silent and fabricated

**Problem:** Five methods in `RealCybrillaClient.java` silently default every investor:
- `resolveGender` (line 2666) → `female`
- `resolveOccupation` (lines 2679, 2688) → `service`
- `resolveIncomeSlab` (line 2695) → `upto_1lakh`
- `resolveSourceOfWealth` (line 2719) → `salary`
- `resolvePepDetails` (line 2736) → `not_applicable`

This means every investor gets fabricated values sent to the RTA — a SEBI compliance risk.

**Fix for now (log the defaults so they're not silent):**
```java
private String resolveGender(Investor investor) {
    String raw = /* read from investor or onboardingNotes */;
    if (raw == null || raw.isBlank()) {
        log.warn("COMPLIANCE: gender defaulted to 'female' for investor {}", investor.getId());
        return "female";
    }
    return mapGender(raw);
}
```
Add `log.warn` to all five resolver methods. This makes the problem visible in production logs.

**Real fix (post-demo, larger scope):** Collect these fields in the onboarding form and store them
on the `investors` table. The full plan is in `flaws.md` under to-do #11.

---

### DF-15 — Flyway V41–V51 are messy and unnecessary

**Problem:** Migrations V41 through V51 are pure DML fixup scripts for one specific investor record
(Anita Verma, UUID `9b5c4d3e-…`). They should be in the data seeder, not in versioned migrations.

**Fix (do carefully, in order):**
1. Take the end-state of V51 and encode it as `DemoDataSeeder` upsert logic with
   `WHERE id='9b5c4d3e-…'`.
2. Delete the 11 migration files V41–V51.
3. In `FlywayConfig.java`, add `flyway.ignore-migration-patterns=*:missing` so Flyway doesn't fail
   on DBs that already ran those migrations (their history entries remain; the scripts just don't
   exist on disk).
4. Test against a DB that already ran V41–V51 (non-empty `flyway_schema_history`) before deploying.

---

## Final verification checklist

After all Tier 1 + Tier 2 tasks:

```
□ ./mvnw -B test                      → should be ≥ 281 tests, 0 failures, 0 errors
□ npx tsc --noEmit                    → 0 TypeScript errors
□ Backend starts on non-local profile → no DemoDataSeeder / DemoOrderAdvancer logs
□ Login page                          → no amber OTP banner visible
□ Network tab after OTP request       → devCode is null / absent in response JSON
□ InvestorOnboarding step 1           → no pre-populated gender/relationship/tax fields
□ InvestorOnboarding step 6           → separate T&C checkbox + FATCA checkbox, both required
□ Scheme list / transaction page      → NAV shows a number, not ₹0.00
□ Contact verification flow           → "Send OTP" button visible next to email + mobile
□ Database after T&C accept           → row in terms_acceptances table
```

---

## Contact for questions

This handoff covers `plan.md` Tasks 1–5 + DFs 07, 08, 09, 10, 11, 12, 13, 14, 15.
Full architectural context: `context.md`. Full defect registry: `bugs.md` + `flaws.md`.
Full audit trail: `history.md`.
