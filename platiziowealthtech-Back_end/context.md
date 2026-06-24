# Platizio Wealthtech — Engineering Context

> Single-page orientation for anyone (human or agent) picking up this codebase. Pairs with
> [`bugs.md`](bugs.md) (defect registry), [`fix.md`](fix.md) (active fix plan), [`history.md`](history.md)
> (chronological audit trail) and [`flaws.md`](flaws.md) (the 11-to-do compliance/feature audit).
> Last updated: 2026-06-23.

---

## 1. What this product is

Platizio Wealthtech is a **mutual-fund distribution platform** for distributors/RIAs in India. It lets a
distributor onboard investors (KYC, bank verification), browse a fund catalogue, and place **lumpsum
purchases, redemptions, and SIPs** on behalf of investors. All regulated money-movement and KYC is brokered
through **Cybrilla** — specifically the **Cybrilla POA additional APIs** and the **Fintech Primitives (FP)
cybrillapoa gateway**. Platizio never touches the RTA/AMC directly; Cybrilla/FP is the system of record for
KYC, folios, orders, payments and mandates.

The two end-users:
- **Distributor / Admin** — the React SPA (`Front_end`), logs in, manages investors and orders.
- **Investor** — two surfaces: (a) the legacy **investor-action links** (server-rendered HTML on the backend,
  **not** part of the React app) for per-action consent/mandate/payment; and (b) **since 2026-06-22, a real
  investor portal** in the same React SPA — passwordless OTP signup/login (`investor_accounts`, separate from
  the distributor-owned `Investor`), an **approval-gated** onboarding flow (distributor can't finalize until the
  investor attests the exact submission), and investor-self contact verification. See the 2026-06-22 `history.md`
  entry; governed by SRS `PLZ-SRS-INV-COMP-001 v1.0`. **Phases 1–3 done** (2026-06-22/23): P1 = passwordless
  signup/login + approval-gated onboarding; P2 = transaction-2FA engine (every Cybrilla money write gated
  behind investor OTP+consent on a hashed snapshot) + Approval Center + Withdrawal page; P3 = holdings
  dashboard (`HoldingsService`: authoritative units×NAV, cost basis, 1-day/total returns, XIRR) + recharts UI
  at `/investor/dashboard`. See `PHASE2_PLAN.md` + the 2026-06-22/23 history entries.

---

## 2. Repository layout

```
Wealthtech/
├── Back_end/
│   └── platiziowealthtech-Back_end/        ← Spring Boot (Java) service  +  investor-action HTML
│       ├── src/main/java/com/platizio/wealthtech/
│       │   ├── controller/    REST controllers (+ GlobalExceptionHandler, webhook, investor-action)
│       │   ├── service/       business logic (Investor, Order, Portfolio, Kyc, InvestorAction, Otp…)
│       │   ├── integration/   CybrillaClient (interface) + Real/Mock adapters + auth token services
│       │   ├── domain/        JPA entities + enums (TransactionOrder, RedemptionRecord, OtpPurpose…)
│       │   ├── dto/           request/response DTOs
│       │   ├── repository/    Spring Data repositories
│       │   ├── config/        SecurityConfig, rate-limit filter, etc.
│       │   └── init/          DemoDataSeeder / DataInitializer
│       ├── src/main/resources/
│       │   ├── application.yml
│       │   └── db/migration/  Flyway V1…V60 (V41–V51 sandbox demo fixups — BUG-022; V56–V58 investor portal P1; V59–V60 transaction 2FA P2)
│       ├── src/test/java/...   261→393 tests (JUnit)
│       ├── bugs.md / fix.md / history.md / context.md / flaws.md   ← tracking docs
│       └── .codex/skills/cybrilla-boss/   ← Cybrilla/FP integration "source of truth" reference docs
│           └── references/   docs-index, pre-verifications, onboarding-and-orders, page-cybrilla-map…
└── Front_end/                              ← Vite + React + TypeScript SPA (distributor/admin console)
    └── src/
        ├── views/        page components (InvestorOnboarding, InvestorRedeem, SipDashboard, Portfolio…)
        ├── components/    shared UI (KycFlowPanel, CybrillaKycWarnings, InvestorActionLink…)
        ├── store/         Redux (slices + api)
        ├── config/api.ts  the single backend boundary (VITE_API_BASE_URL → :8081/api/v1)
        └── utils/         kycFlow / kycPreVerification / kycActionLocks
```

**Ports / origins:** backend `http://localhost:8081/api/v1`; SPA `http://localhost:3000`; investor-action
pages `http://localhost:8081/investor-actions/{token}` (served by Spring Boot, **not** Vite).

**Stack:** Backend = Spring Boot, JPA/Hibernate, Flyway, JWT auth, JUnit. Frontend = Vite, React, TypeScript,
Redux, `react-hook-form` + `zod`, axios/fetch, Lucide icons.

---

## 3. Provider integration model (Cybrilla POA + Fintech Primitives)

This is the heart of the system. The integration rules are codified by the **`cybrilla-boss`** agent
([`.cursor/agents/cybrilla-boss.md`](.cursor/agents/cybrilla-boss.md)) and its reference docs under
`.codex/skills/cybrilla-boss/references/`.

### Two API surfaces, two token audiences
| Surface | Base path | Used for | Token audience |
|---------|-----------|----------|----------------|
| **POA additional APIs** | `/poa/...` | pre-verifications (readiness, PAN/name/DOB, bank), KYC forms | `CYBRILLA_PRE_VERIFICATION` |
| **FP tenant** | `/v2/...` | investor_profiles, mf_investment_accounts, kyc_requests, identity_documents, scheme plans, mf_purchases, mf_redemptions, mf_purchase_plans, mandates, payments | `FINPRIM_TENANT` |

- Two independent token caches, 30-minute JWTs, single 401-retry per audience
  (`integration/auth/ExternalBearerTokenService.java`).
- **Browser → Platizio `/api/v1` only.** No provider URLs, tokens or tenant headers in React.
- Adapter boundary: `integration/CybrillaClient.java` (interface), `RealCybrillaClient.java` (live),
  `MockCybrillaClient.java` (tests/local).
- Persist provider IDs with prefixes: `pv_` (pre-verification), `invp_` (investor profile),
  `mfia_` (mf investment account), `bac_` (bank account), `kycr_` (kyc request), `iddoc_` (identity doc).

### Non-negotiable integration rules (from `cybrilla-boss`)
1. Orders use the **POA catalogue** `GET /v2/mf_scheme_plans/cybrillapoa` (ISIN). OMS `/api/oms/fund_schemes`
   is **reference-only**, never orderable (`ProductSchemeOrderSupport.requirePoaOrderable`).
2. **Never PATCH `occupation`** on an existing `invp_` profile (FP hides it on GET but stores it; re-sending
   triggers *"occupation is already set and cannot be modified"*). See `fp-profile-patch-rules.md`.
3. Model async states honestly — never collapse `accepted → pending → payment → successful` into one UI step.

### Pre-verification API (POA) — the readiness/KYC engine
`POST /poa/pre_verifications` → persist `pv_…` → poll `GET /poa/pre_verifications/:id` until `status=completed`;
webhooks `pre_verification.accepted` / `.completed`. Supports three lookup types: **readiness**
(`investor_identifier: PAN`), **PAN/name/DOB validation**, and **bank-account verification**. Program against
nested `code` not free-text `reason`. **The POA pre-verification API does not verify email or mobile** — those
are app-side concerns (see `flaws.md` items 1 & 3).

### Transaction lifecycles (built vs pending)
- **Lumpsum purchase:** `POST /v2/mf_purchases` → review (under_review→pending) → **consent** → confirm →
  submitted → `POST /api/pg/payments/netbanking` → investor redirect → postback/webhook → successful.
  Built through payment in `OrderService` + `InvestorActionService.submitPurchaseForPayment`.
- **Redemption:** `POST /api/v1/orders/{purchaseOrderId}/redemption` → `OrderService.createRedemption`
  (requires a real FP `externalOrderId`; runs the same `ensureMfInvestmentAccount` pre-flight) → `POST /v2/mf_redemptions`.
- **SIP + mandate:** create mandate (eNACH/NACH) → authorize (`token_url`) → approved → create
  `mf_purchase_plan` → review → consent → confirm → first NACH installment.

> The "consent" in these provider lifecycles is **FP provider consent on the order**, captured on the
> server-rendered investor-action page. It is distinct from (a) **distributor-side 2FA/OTP** and (b)
> **platform Terms & Conditions acceptance** — both of which the 11-to-do audit treats as separate
> requirements. See `flaws.md`.

---

## 4. Auth, security & OTP (current state)

- Distributor/admin auth: JWT (`JwtService`, `AuthService`, `RefreshTokenService`, `AuthCookieService`),
  login rate-limiting (`config/LoginRateLimitFilter.java`), blocked-token purge scheduler. Unchanged — the
  Supabase work below did NOT touch distributor login.
- **Login/signup OTP** (`domain/OtpPurpose.java` = `LOGIN, SIGNUP`): `OtpService` + `EmailService` send/verify
  email OTPs. As of 2026-06-16 the dev-code leak is fixed — `OtpService` returns the code in the response
  only when `app.otp.expose-dev-code=true` (local profile only, default false; DF-13). The same flag pattern
  guards the password-reset token (`app.password-reset.expose-dev-token`).
- **Investor email/mobile contact verification (Supabase, 2026-06-16).** New, separate from login: an investor's
  email/mobile is verified either by a Supabase Auth **OTP** round-trip or by distributor **self-declaration**
  (to-dos 1 & 3). All Supabase calls are **backend-mediated** — `integration/SupabaseAuthClient` (interface) →
  `RealSupabaseAuthClient` (Spring `RestClient` → GoTrue `/auth/v1/otp` + `/auth/v1/verify`) or
  `DisabledSupabaseAuthClient` (no-op fallback, dev code `000000`), selected by `supabase.auth.real-client-enabled`
  (code default **false**, overridden to **true** in the git-ignored `.env` on 2026-06-17 so the real client is
  active for email OTP). `InvestorContactVerificationService` + 6 endpoints on `InvestorController`
  (`/{id}/email|mobile/otp/request|verify`, `/{id}/contact/declare`, `/{id}/contact/status`) record
  `email/mobile_verified` + method + `belongs_to` on `Investor` (V53). The declared `belongs_to` now flows to FP
  (DF-11; was hardcoded `"self"`). **Status (2026-06-17):** Supabase **email** OTP is now live-verified against
  project `volmpsvzrbzialnrrqjk` (`real-client-enabled=true` in the git-ignored `.env`; live send 200 + bad-code
  verify 403). The Magic Link template must include `{{ .Token }}` for the 6-digit code. **Mobile/SMS OTP is
  deferred** — the Supabase Phone provider is disabled (likely a paid plan), so `/mobile/otp` returns a clean 400
  and `contact/status` reports `smsOtpEnabled=false`.
- **Still no transaction-level 2FA** for purchase / redemption / SIP (to-dos 5–7) — the OTP engine is reused for
  contact verification, not order confirmation.
- Known deferred security items (per user, after functionality): webhook secret is non-constant-time + no
  body signature (BUG-015); `CybrillaDirectController` raw proxy reachable by any authenticated user
  (BUG-028); provider snapshots store PAN/bank PII unredacted (BUG-020); `distributorId` trusted from
  payload (BUG-032). See `bugs.md`.

---

## 5. Build & test status

- **Backend:** `mvnw -B test` → **295 tests, 0 failures, 0 errors (GREEN)** as of 2026-06-16 (was 281; +14 for
  Tier 1 + Supabase contact verification + the T&C / pre-verification / cleanup batch). All changes currently
  **uncommitted** (not a git repo here).
- **JDK:** this machine has **no JDK on PATH by default** — OpenJDK 21 was installed via Homebrew at
  `/opt/homebrew/opt/openjdk@21`. Run builds with `JAVA_HOME` set inline (shell state doesn't persist between calls).
- **Test infra quirk:** the suite is **all pure Mockito unit tests — zero `@SpringBootTest`**, so `mvnw test`
  does NOT boot Spring/Flyway/Postgres. Migrations and `@Profile`/`@ConditionalOnProperty` wiring must be
  validated separately. Live `SPRING_PROFILES_ACTIVE=demo` boots on 2026-06-16 (Postgres 17) confirmed Flyway
  V1→**V55**, Hibernate `ddl-auto: validate`, clean context start, the T&C endpoint E2E (login→accept→list),
  that V55 leaves exactly the 3 canonical schemes, and that deleting V41–V51 is safe on a DB that already ran
  them (repair + `ignore *:missing`). Latest migration is **V55**.
- **Frontend:** React app present (`Front_end/`). Verify with `tsc --noEmit` (= `npm run lint`) + `vite build`
  (Node 24; `npm install` first). The >500 kB chunk warning is pre-existing/advisory.

---

## 6. The bug-tracking system (how the docs relate)

| Doc | Role |
|-----|------|
| `bugs.md` | Canonical defect **registry** — BUG-001…047, severity, area, verification legend (✅/☑️/🔎). |
| `fix.md` | Active **fix plan** — status legend `[ ]/[~]/[x]/[✓]/[—]`, sequenced by priority. |
| `history.md` | Chronological **audit trail** — every wave of fixes with `mvnw test` evidence. |
| `context.md` | **This file** — orientation + architecture. |
| `flaws.md` | The **11-to-do compliance/feature audit** — what's built, feasibility in ~5h, flaws + solutions (tabular). |

Prior audits used a `supervisor` agent over `backend-auditor` / `frontend-auditor` / `integration-auditor`
workers. Net result of the last sessions: 16 functional bugs fixed + verified; security batch deferred per
user; build GREEN. Open functional backlog includes BUG-009 (`mandate.*` reconcile → BUG-047), BUG-013
catalogue source, BUG-022 demo migrations, BUG-029/030 tenant/demo gating, BUG-033 mobile validation.

---

## 7. The 11 to-dos (this engagement)

These are the compliance/feature items under review. **Detailed evidence-based assessment — flaws (with
`file:line`), ~5-hour feasibility, and solutions — lives in [`flaws.md`](flaws.md).** Status snapshot updated
**2026-06-16** (after the Tier 1 + Supabase contact-verification work — see `history.md` / `TEAM_HANDOFF.md`):

| # | To-do | Status | Sev | Notes |
|---|-------|--------|-----|-------|
| 1 | Email verification (OTP + self-declaration) | **BUILT** ✅ | High | Supabase OTP + declare; **email OTP live-verified** (2026-06-17, project `volmpsvzrbzialnrrqjk`; needs `{{ .Token }}` in Magic Link template) |
| 2 | Usage check of the pre-verification API | **BUILT** ✅ | Low | (the `investor_identifier` parity add is still TODO) |
| 3 | Mobile verification (OTP + self-declaration) | **BUILT** ✅ | High | Supabase SMS OTP + declare; **mobile/SMS deferred** — Phone provider disabled (likely paid plan); `/mobile/otp` → 400, self-declaration works; email path is live |
| 4 | Nomination capability + nominee flow | **NOT BUILT** | Critical | — |
| 5 | 2FA for Purchase + consent at OTP entry | **NOT BUILT** | Critical | — |
| 6 | 2FA for Redemption + consent at OTP entry | **NOT BUILT** | Critical | — |
| 7 | 2FA for SIP transactions | **NOT BUILT** | Critical | — |
| 8 | Acceptance of Terms & Conditions | **BUILT** ✅ | High | DF-10; V54 `terms_acceptances` + endpoints + FE checkbox; E2E-verified |
| 9 | Redemption screen: folio / scheme / units | **PARTIAL** | High | — |
| 10 | Holdings calculation method | **PARTIAL** | High | — |
| 11 | No auto-populated defaults for all customers | **PARTIAL** | Critical | FE dropdowns (DF-09) ✅ + resolver defaults made observable (DF-14) ✅; still does not COLLECT the 7 regulated fields |

**Net (2026-06-16):** #1, #2, #3, #8 built. #2 pre-verification now also sends `investor_identifier` (parity).
#11 is improved but not closed — DF-09 (FE no auto-defaults) and DF-14 (resolver defaults now log a WARN
instead of silently fabricating) are done, but the backend still *defaults* the 7 regulated fields rather than
*collecting* them. #4 (nomination) and #5–7 (transaction 2FA) remain net-new; #9/#10 still on a
non-authoritative local-order basis. Status (2026-06-17): the Supabase **email** OTP path (#1) is now live-verified
(project `volmpsvzrbzialnrrqjk`, `real-client-enabled=true` in the git-ignored `.env`); the **mobile** path (#3) is
**deferred** (Phone provider disabled, likely a paid plan) and returns a clean 400 until `SUPABASE_SMS_ENABLED=true`.
Also done this session but outside the
11 to-dos: DF-08 (V55 removes the demo seed from prod) and DF-15 (deleted the V41–V51 Anita-repair migrations).

### Cross-cutting reality (drives several verdicts)
- **OTP exists but only for LOGIN/SIGNUP** (`OtpPurpose.java`) → 2FA to-dos (5–7) reuse the existing hashed-OTP
  engine but need a new transaction purpose, an order-bound `email_otps` row, an SMS channel, and a
  `TransactionConsent` record. FP provides the `consent` object but does **not** generate/send/verify the OTP.
- **Provider consent ≠ distributor 2FA ≠ platform T&C** — the code conflates all three; keep them separate.
- **POA pre-verification doesn't cover email/mobile** → to-dos 1 & 3 are app-side declaration/OTP capture
  (now built via Supabase, 2026-06-16). FP `belongs_to` is **no longer hardcoded** — it flows from the declared
  `email/mobile_belongs_to` on `Investor`, defaulting to `"self"` (DF-11 resolved).
- **Nominee** has no backend persistence at all (only a 2-field UI widget dropped on submit) → largest net-new
  build; FP supports it via `related_parties` + `folio_defaults`.
- **Holdings/units** are summed from **local order amounts**, not the FP/RTA holdings report
  (`GET /api/oms/reports/holdings`) → correctness risk for to-dos 9 & 10; folio number is not shown at all.
- **Regulated defaults (#11)** — ≥7 fields (`tax_status`, `nationality`, `country_of_birth`, PEP, gender,
  occupation, income, source-of-wealth) are blanket-defaulted for **every** customer in
  `RealCybrillaClient`, and the FATCA declaration is discarded → fabricated declarations reach the RTA.

### Cross-cutting reality (drives several verdicts)
- **OTP exists but only for LOGIN/SIGNUP** → 2FA to-dos (5–7) reuse existing infra but need a new
  `OtpPurpose` + a transaction-consent capture surface.
- **Provider consent ≠ distributor 2FA ≠ platform T&C** — keep these three separate when reading the code.
- **POA pre-verification doesn't cover email/mobile** → to-dos 1 & 3 are app-side declaration capture.
- **Nominee** has no backend persistence found in initial scouting (only a stray UI reference) → likely the
  largest net-new build.
- **Holdings** may be computed from local order state rather than an authoritative FP folio/holdings report →
  correctness risk for to-dos 9 & 10.

---

## 8. Working agreements / gotchas

- This is **production-grade software** — treat compliance gaps (uniform defaults, missing nomination,
  missing 2FA/consent, non-authoritative holdings) as serious, not cosmetic.
- The repo often carries **active uncommitted work** — re-read live files before editing.
- After backend changes, free port **8081** before a single `mvnw spring-boot:run` (stale JVM is a recurring
  red herring — see `pending-work.md`).
- Sandbox quick data: login `a@a.com` / `Ok@123456`; distributor `4317cfd2-a41f-4320-a5dc-26835c7210ac`;
  Anita Verma `9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03` (KYC + bank verified); payment amount ending `0` →
  success, `1` → failure.
- **Never** put provider tokens/calls in React. **Never** PATCH `occupation`. **Never** order off OMS
  `fund_schemes`.
