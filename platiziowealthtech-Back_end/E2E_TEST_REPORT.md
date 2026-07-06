# Platizio WealthTech — Deep E2E + API Test Report

**Date:** 2026-07-03 · **Environment:** local profile (mock Cybrilla client, local data sources, demo seeder) · **Backend:** Spring Boot on `:8081` (PID 2340, built from `investor/platiziowealthtech-Back_end`) · **Frontend:** Vite/React on `:3002` · **DB:** PostgreSQL `localhost:5432/postgres`

Testing method: two live servers driven end-to-end via **Claude-in-Chrome** (real browser, distributor + investor portals), **8 parallel API sub-agents** (~226 targeted HTTP cases with negative/edge coverage), and the **backend JUnit suite**. Evidence is from live HTTP responses and the running app; code citations are against the running `investor/` copy.

---

## 1. Verdict

The application is **functional and, in its most compliance-critical path, correctly hardened** — the transaction 2FA engine passes every adversarial check (master-code, wrong-code, no-consent, replay all rejected), the previously-documented investor-side IDOR holes are closed, CORS is locked, login rate-limiting works, and **503/503 JUnit tests pass**. However, testing surfaced **three real defects**, one of them a **critical 2FA bypass on the profile-change approval path**, plus a **cross-distributor data-exposure (BOLA)** on the distributor endpoints.

| Severity | Count | Issues |
|---|---|---|
| 🔴 Critical | 1 | Profile-change 2FA accepts master code `000000` (SEBI 2FA bypass) |
| 🟠 High | 1 | BOLA — any distributor reads any other distributor's PII + bank details |
| 🟡 Medium | 1 | Missing required `@RequestParam` → HTTP 500 instead of 400 (systemic) |
| 🟢 Low | 7 | UX/hardening/mock items (see §5) |

---

## 2. Coverage

| Domain | Tests | ✅ Pass | ❌ Fail | ⚠️ Warn | Method |
|---|---:|---:|---:|---:|---|
| Auth & session (distributor) | 22 | 22 | 0 | 0 | API |
| Investors & onboarding | 35 | 28 | 1 | 6 | API |
| KYC & pre-verification | 29 | 5 | 0 | 24* | API |
| Products / catalogue / bank ref | 21 | 19 | 0 | 2 | API |
| Orders / payments / SIP / redemption | 24 | 21 | 0 | 3 | API |
| Transaction 2FA / approvals / links | 30 | 26 | 1 | 3 | API |
| Dashboard / portfolio / holdings / reports | 27 | 21 | 2 | 4 | API |
| Security / IDOR / CORS / input-val | 38 | 30 | 3 | 5 | API |
| **API subtotal** | **226** | **172** | **7** | **47** | |
| Backend JUnit (surefire) | 503 | 503 | 0 | 0 | unit/integration |
| Browser E2E (distributor + investor) | — | pass | — | 2 UI | Chrome |

\* Nearly all 24 KYC "warnings" are **correct by-design 403s** (distributor is intentionally blocked from the KYC surface) or mock-client limitations — **not defects**. See §4.4.

The 7 API failures collapse to **3 distinct root causes** (one appears on 4 endpoints).

---

## 3. What works well (verified)

- **Transaction 2FA is correctly hardened.** `POST /orders/{id}/request-investor-approval` issues a challenge and returns **no OTP**; the investor Approval Center shows an immutable **snapshot hash + versioned consent + masked destination**. Adversarial checks all pass: approve with `000000` → **401**, wrong 6-digit → **401**, `consent=false` → **400**, correct challenge OTP + consent → **200** `PAYMENT_REDIRECT`, and **replay of a consumed challenge → 400** ("not awaiting a code"). Redemption self-withdraw shares the same `TRANSACTION_APPROVAL` guard and inherits the same rejection.
- **Investor-side IDOR from `bugs.md` is closed.** Cross-distributor investor read → 400/403; create-under-another-distributor → 403 (BUG-032 guarded); all unauthenticated endpoints → 401; distributor cookie on `/api/v1/investor/**` → 401 (session-type isolation).
- **CORS is locked.** Evil origin → 403 with **no** `Access-Control-Allow-Origin`; `localhost:3002` allowed.
- **Login rate-limiting works.** 5 failed logins then **429** (per-IP token bucket).
- **Compliance gates hold.** Orders for a `NOT_STARTED`/unverified-bank investor are blocked 400 ("KYC must be completed before order creation"); ineligible investors cannot transact.
- **Auth suite 22/22**, input validation clean (SQLi → `200 []`, bad UUID → 400, malformed JSON → 400), full scheme CRUD lifecycle, order lifecycle, SIP create→edit→cancel, redemption draft→sync all green.
- **503/503 JUnit** across 86 test classes (0 failures/errors/skips).
- **Browser E2E:** distributor login → dashboard (AUM, pipeline) → Investor Archive with correct **state-gated actions** → investor detail/compliance; investor portal login → dashboard with **XIRR** → Approval Center rendering real 2FA challenges. (Recording: `weathtech_e2e_distributor_investor.gif`.)

---

## 4. Failures (detailed)

### 4.1 🔴 CRITICAL — Profile-change 2FA approval accepts the master login code `000000`

**Confirmed live, with DB evidence.** The distributor-initiated **profile-change** approval (address, DOB, bank, nominees, tax/PEP, email/mobile — regulated KYC/profile fields) can be approved with the dev **master code `000000`** instead of the real per-challenge OTP, and the change is fully applied (investor advances to `READY_FOR_TRANSACTIONS`).

- **Repro:** legitimate invite → signup → link-approve → skip → `profile/submit` chain created a `PROFILE_APPROVAL` challenge; the real OTP was issued (`757235`); then `POST /api/v1/investor/profile-changes/{id}/approve` with `code=000000` → **HTTP 200** `{linkingStatus:READY,"approved and applied"}`. DB: challenge `CONSUMED`, and the **genuine OTP row unconsumed (attempts=0)** — i.e. `000000` bypassed it.
- **Root cause:** `OtpService.verify` (`OtpService.java:181-183`) exempts **only** `OtpPurpose.TRANSACTION_APPROVAL` from the dev-master-code branch. `ProfileChangeApprovalService.approve` verifies with `OtpPurpose.PROFILE_APPROVAL` (`ProfileChangeApprovalService.java:224`), which is **not** excluded, so `app.otp.dev-master-code` (=`000000` on local, `application-local.yml:27`) is accepted.
- **Why the transaction path is safe but this isn't:** the transaction engine defends in depth (hard-excludes `TRANSACTION_APPROVAL` regardless of the code value); the profile-change engine relies **solely** on the master code being blank.
- **Environment nuance:** `application.yml`'s default `app.otp.dev-master-code` is **blank**, so the bypass is **inert in production unless `OTP_DEV_MASTER_CODE` is ever set**. It is active on the local/dev profile. This is a defense-in-depth gap, exploitable on any environment where a dev master code is configured.
- **Fix:** exclude **all `*_APPROVAL` purposes** (`PROFILE_APPROVAL`, and any future approval purpose) from the master-code branch — mirror the transaction engine's hard exclusion rather than depending on config.

### 4.2 🟠 HIGH — BOLA: any distributor can read any other distributor's PII + bank details

`DistributorController` has **no object-level authorization** on its read/update routes.

- **Repro:** login `a@a.com` → `GET /api/v1/distributors/search?query=a` returns **all** distributors' full records; `GET /api/v1/distributors/{otherId}` → **200** with `bankAccountNumber`, `bankIfsc`, `bankAccountHolderName`, `email`, `mobile`, ARN, NISM of an unrelated distributor. (No password/token leaked.)
- **Root cause:** `getById` / `search` / `PUT /{id}` / `sub-distributors` lack `@PreAuthorize` + ownership checks (unlike `DELETE` and `PATCH /status`, which are correctly ADMIN-gated). `sub-distributors` additionally trusts a `requesterId` **query param** instead of the authenticated principal.
- **Fix:** authorization-gate those routes, derive `requesterId` from the JWT principal (never the query string), and mask/omit bank fields on cross-entity reads.

### 4.3 🟡 MEDIUM — Missing required `@RequestParam` → HTTP 500 instead of 400 (systemic)

A missing **required** query parameter yields a generic **500** rather than Spring's default **400**, on at least three endpoints — hit independently by three agents:
- `GET /api/v1/distributors/sub-distributors` (no `requesterId`) → 500
- `GET /api/v1/investors/households/{id}` (no `distributorId`) → 500
- `PATCH /api/v1/orders/{id}/status` (no `status`) → 500

- **Root cause:** an over-broad `@ExceptionHandler(Exception.class)` in `GlobalExceptionHandler` swallows framework `MissingServletRequestParameterException` (a 4xx) and maps it to 500. No stack trace is leaked, but the status code is wrong and spurious 500s pollute monitoring.
- **Fix:** let Spring's default 4xx handling stand for `MissingServletRequestParameterException` / `MethodArgumentTypeMismatchException` (add explicit handlers returning 400), and narrow the catch-all.

---

## 5. Low-severity findings & environment caveats

**Genuine low-severity items:**
- **Dual notification stores disagree.** The dashboard feed (`/dashboard/.../notifications`) is empty / unread=0 while `NotificationController` reports 13–15 unread for the same distributor — two parallel stores, inconsistent counts.
- **"Unknown fund" holding.** One of Anita's holdings resolves to `schemeName="Unknown fund"` (scheme id not reconciled) — visible in both the API and the investor dashboard.
- **`resend-approval-link` echoes the investor's OTP** in the body on local (`expose-dev-code=true`, `application-local.yml:23`). Hidden in production (DF-13); still weakens factor separation wherever that flag is true.
- **Stored XSS in `fullName`.** `<script>…</script>` is stored/returned verbatim. The API is `application/json` + `X-Content-Type-Options: nosniff`, so the API itself is not an execution sink — but the **frontend must escape** it on render.
- **No `Content-Security-Policy` / `Referrer-Policy`** headers; login cookie is `HttpOnly` + `SameSite=Lax` but `Secure=false` on local HTTP (expected for localhost).

**Frontend/UX (from browser E2E):**
- **Compliance tab shows a contradictory "Platizio KYC error / Access denied" modal.** The distributor's investor-detail → Compliance tab fires a live KYC compliance-check that is `denyAll` for the distributor role (see §4.4), producing a red error modal even though the cached compliance fields all read **VERIFIED**. Backend behavior is correct-by-design; the **frontend should not call a role-denied endpoint** (or should treat its 403 as "not applicable," not an error).
- **Invest/SIP wizard is blocked — "No orderable funds found."** The wizard lists ~2,983 schemes but none are selectable because **no seeded scheme is POA-orderable**; all seeded schemes are OMS `fund_schemes` that `requirePoaOrderable()` rejects with 400 ("cannot be ordered via Cybrilla POA"). Independently confirmed by the orders agent. Order placement still works via the API once a POA-orderable scheme exists (the startup demo warmup proved the backend order path).

### 4.4 Note — distributor KYC 403s are **by design** (not a bug)
Every distributor call to the KYC/pre-verification surface (`/pre-verifications/**`, `/*/kyc-flow/**`, `/*/kyc-checks`, `/*/kyc-compliance-check`, `/*/kyc-requests`, `/*/kyc/apply|rekyc`, `/*/identity-documents`, `/*/esign/**`, `/*/kyc-form/**`) returns **403 "Access denied"**. This is an intentional `denyAll()` in `SecurityConfig.java:76-92` ("the distributor may only enter investor details; the investor alone performs pre-verification, PAN, KYC, DigiLocker, eSign"). KYC is **investor-portal-only** (`/api/v1/investor/**`, `ROLE_INVESTOR`). `PATCH /{id}/kyc` and `/{id}/bank-verify` are separately `@PreAuthorize hasRole('ADMIN')`. No 500s, no security gap.

**Mock-client limitations (expected — not defects, would need the real Cybrilla sandbox client):**
- `MockCybrillaClient.createPreVerification` always returns `verified` for every PAN — the granular simulator outcomes (`kyc_unavailable`, `invalid`, `aadhaar_not_linked`, name/dob mismatch) are unimplemented in mock mode.
- Order amount rule (ends-in-1 → RTA failure) is ignored by the mock; bank verify only fails accounts ending `1515`.
- `banks/pincodes/000000` and `banks/ifsc/NOTREAL00` echo generic "Mock City/Bank" (200) rather than 404 for unknown-but-well-formed values.
- Bulk order is **non-atomic**: a multi-line bulk partially commits (one line's bank-gate 400 didn't roll back a sibling line), with no partial-success report to the caller.

---

## 6. Environment caveats affecting interpretation

1. **Two backend copies exist** (`Back_end/platiziowealthtech-Back_end` and `investor/platiziowealthtech-Back_end`). The **running server is the `investor/` copy** (the newer, more complete one). All live findings and code citations here are against that copy; a sub-agent that grepped the other copy saw some routes as "missing" that are present on the running server (confirmed via `/v3/api-docs`).
2. **Shared dev DB.** Agents ran concurrently against one Postgres. Test data was additive (throwaway investors/orders/schemes with unique keys); no seed investor was deleted. The catalogue has accumulated ~3,312 schemes from prior syncs.
3. **Mock, not sandbox.** `CYBRILLA_REAL_CLIENT_ENABLED=false`. External KYC/PAN/bank/order simulator outcomes are only fully exercised against the real Cybrilla sandbox client.

---

## 7. Recommended fixes (priority order)
1. **(Critical)** Exclude all `*_APPROVAL` OTP purposes from the dev-master-code branch in `OtpService.verify` — do not rely on config being blank.
2. **(High)** Add ownership/role authorization to `DistributorController` `getById`/`search`/`PUT`/`sub-distributors`; derive `requesterId` from the principal; mask bank fields cross-entity.
3. **(Medium)** Add explicit 400 handlers for missing/mistyped request params; narrow the catch-all `@ExceptionHandler(Exception.class)`.
4. **(Low)** Reconcile the two notification stores; fix the "Unknown fund" scheme resolution; have the frontend suppress the role-denied KYC compliance-check call; seed at least one POA-orderable scheme so the Invest wizard is demoable; add CSP/Referrer-Policy headers.
