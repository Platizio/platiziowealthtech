# Platizio Wealthtech — Working Context & Handoff

> **Purpose:** Single-page orientation for anyone (human or agent) picking up this work. Captures the project layout, what's been done, the current verified state, how to run the demo, and what's left. Pairs with `bugs.md` (registry), `fix.md` (plan), `history.md` (chronological audit trail).
> **Last updated:** 2026-06-15.

---

## 1. What this project is

Platizio Wealthtech — a distributor-facing mutual-fund platform. A distributor onboards investors (KYC), browses a scheme catalogue, and places orders (lumpsum / SIP / redemption). Orders are fulfilled through **Cybrilla / Fintech-Primitives (FP)** + a **POA** layer. Investors complete payment/consent via tokenized **investor-action** pages.

| Layer | Tech | Where |
|-------|------|-------|
| Backend | Java + Spring Boot, Maven (`mvnw`), Flyway, PostgreSQL | `C:\Users\HP\Downloads\platiziowealthtech-Back_end\platiziowealthtech-Back_end` |
| Frontend | React 19 + Vite 6 + TypeScript + Tailwind v4 + Redux Toolkit + react-router 7 | see §2 — **separate repo** |
| External | Cybrilla / Fintech-Primitives (FP) + POA APIs | sandbox tenant `platizio` |

Backend API base: `http://localhost:8081/api/v1`. Frontend dev: `http://localhost:3000` (proxies to `:8081`). Investor-action pages are served by **Spring Boot on :8081** (`/investor-actions/{token}`), **not** Vite.

---

## 2. Repository layout (IMPORTANT — frontend is a separate folder, with duplicates)

**Backend (this repo):** `C:\Users\HP\Downloads\platiziowealthtech-Back_end\platiziowealthtech-Back_end`
- The tracking docs (`bugs.md`, `fix.md`, `history.md`, this `context.md`) live here.
- Note the nested folder: the git root is `...\platiziowealthtech-Back_end`, the Maven project is one level down in `...\platiziowealthtech-Back_end\platiziowealthtech-Back_end`.

**Frontend — three copies exist on disk. Only ONE is live:**

| Path | Live? | Signals |
|------|-------|---------|
| `C:\Users\HP\Downloads\platiziowealthtech-Front_End for now\platiziowealthtech-Front_End_sample` | ✅ **LIVE — edit this one** | git-tracked, `node_modules` present, most-recently modified, user-confirmed |
| `C:\Users\HP\Downloads\platiziowealthtech-Front_End_sample` | ❌ stale | no `.git` |
| `C:\Users\HP\Downloads\platiziowealthtech-Front_End_sample (1)\platiziowealthtech-Front_End_sample` | ❌ stale | no `.git` |

> Always edit the **"Front_End for now"** copy. The other two are old downloads.

---

## 3. How to run the demo

Per project ops notes (`docs/bugs.md`, `docs/sandbox-*`):
- **Backend:** from the Maven project dir, `.\start-backend.ps1 -Restart` (or `.\mvnw.cmd spring-boot:run`). Listens on **:8081**. If port is busy, kill the existing JVM first (single instance).
- **Frontend:** from the live frontend copy, `npm run dev` → **:3000** (script: `vite --port=3000 --host=localhost`).
- **Login:** `a@a.com` / `Ok@123456`.
- **Demo investor (Anita):** PAN `KRTPX3751K`, bank ending `1193`, payment amount ending `0`.
- **Sandbox PAN pattern:** `XXXPX3751X` (KYC ready) / `XXXPX3753X` (fresh KYC). See `docs/cybrilla-kyc-test-matrix.csv`.
- Both servers were confirmed **listening** (:3000 PID 14324, :8081 PID 11216) during this session.

---

## 4. Current verified state (2026-06-15)

| Check | Result | Evidence |
|-------|--------|----------|
| Backend build/tests | 🟢 **GREEN — 281 run, 0 failures, 0 errors** | fresh `mvnw -B test` → BUILD SUCCESS |
| Frontend `/distributor/transactions` build | 🟢 **vite build exit 0** (2941 modules) | `npm run build` |
| Frontend type-check (Transactions.tsx) | 🟢 **clean** | `npx tsc --noEmit` (1 unrelated error remains in `ProductMgmt.tsx`) |
| Git | **All changes UNCOMMITTED** (backend tree ~9,654 lines WIP + this session) | per user: leave uncommitted |

---

## 5. What was done (summary — full detail in `history.md`)

### Backend — 16 functionality bugs fixed + verified (build RED→GREEN, 261→281 tests)
Scope decision (user): **fix functionality first; defer security/auth.**
- **Build:** BUG-023 — `AuditActorControllerTest` NPE build-breaker (stale stub after the order-ownership change). Fixed: stub overrides `createRedemption(UUID, JwtAuthPrincipal)`.
- **Wave 1:** BUG-001 (409 body now carries existing investor id), BUG-011 (lumpsum amount validated), BUG-024 (FP errors → 502/503 not 400), BUG-025 (orphan CREATED orders → FAILED), BUG-026/027 (investor-action 503 mapping + configurable frontend origin), BUG-034 (`paymentComplete` 400 catch).
- **Wave 1b:** BUG-010 (rollback/persistence no longer masked as investor-sync 409), BUG-031 (soft-deleted PAN/email dup → clean resumable 409), BUG-037 (BAV poll interrupt → retryable 503).
- **Wave 2:** BUG-012 (`ensureMfInvestmentAccount` skips redundant order-ready PATCH when already linked). BUG-038 reclassified **NOT-A-BUG** (POA `pv_` already routed correctly).
- **Wave 3:** BUG-014 (`.env.example` boots a fresh setup), BUG-021 (redemption no longer holds DB txn across FP pre-flight), BUG-013 (admin sync seeds POA-orderable schemes, not OMS), BUG-009 (`mf_purchase`/`payment` webhooks reconcile idempotently; `mandate` acked → follow-up BUG-047).
- Already fixed by prior WIP (confirmed): BUG-002, BUG-003 (the two IDOR holes), BUG-005, BUG-006, BUG-008. Not-a-bug: BUG-007, BUG-038.

### Frontend — `/distributor/transactions` made demo-ready (file: `src/views/Transactions.tsx`)
- **BUG-004 (demo-breaker):** JSX syntax error in `StatusTimeline` — `{steps.map(...)}` closed with `})` instead of `})}`. esbuild/Vite couldn't parse the module → the route failed at runtime + `vite build` failed. Fixed.
- **Type error:** `statusConfig` had `Cancelled`/`CANCELLED` keys missing from the `StatusKey` union → added them.
- **Data bug ("Payment Failed" on good orders):** page fetched `/products/schemes` (defaults to live POA, page 0 / size 20) so demo orders against **local persisted** scheme UUIDs didn't resolve and were mislabeled. Fixed → `/products/schemes?local=true&size=1000` (local DB rows keyed by UUID).

---

## 6. The original "409 conflict" question (resolved understanding)

The backend 409 on investor onboarding (`POST /api/v1/investors`) is **correct by design** — a genuinely duplicate, fully-onboarded PAN/email *should* 409, and same-distributor DRAFT/ONBOARDING records correctly resume (200). The pain was: (a) the 409 body carried **no investor id** so a client couldn't deep-link to resume → fixed (BUG-001, backend now returns `ConflictErrorResponse` with `resourceId`); (b) a soft-deleted PAN/email produced a raw DB-integrity 409 → fixed (BUG-031, clean resumable 409). The remaining amplifiers (re-POST after refresh, fresh-POST on fingerprint change) are **frontend** behaviors in `InvestorOnboarding.tsx`.

---

## 7. What's left

**Functional backlog (in `fix.md`):**
- BUG-033 — `mobileNumber` 10-digit `@Pattern` (verify demo/seed numbers first).
- BUG-022 — move demo-data Flyway migrations V41–V51 into a dev-only seeder (migration-history change → review).
- BUG-029 — fail-fast on FP "tenant not configured" (also needs Cybrilla to enable FP BAV for tenant `platizio`).
- BUG-030 — gate `DemoOrderAdvancer` to demo-stub orders only (it auto-completes orders on the `local` profile).
- BUG-047 — `mandate.*` webhook reconcile (mandate is `int`-keyed, not order-id-keyed).

**Deferred — security/auth (per user, after functionality):**
- BUG-028 (restrict `CybrillaDirectController` to ADMIN), BUG-015 (constant-time webhook secret + body signature), BUG-020 (mask PAN/bank PII in `ExternalApiSnapshotService`), BUG-032 (derive `distributorId` from principal), BUG-035 (`deleteOrder` ownership before FP sync), BUG-036 (hide unchecked `createRedemption(UUID,UUID)` overload).

**Frontend (other, not yet touched):** registry BUG-016/017/018/019 + `docs/bugs.md` FE bugs (BUG-009/010/011/012/022/023/024/025/032/033/034). The Transactions page (BUG-004) is the only FE item fixed so far.

**External / Cybrilla (not code):** FP tenant BAV enablement for `platizio`, payment-postback reachability in dev (tunnel/HTTPS). See `docs/cybrilla-*`.

---

## 8. Key files

**Backend (`src/main/java/com/platizio/wealthtech/`):**
- `controller/OrderController.java`, `service/OrderService.java` — orders, redemptions, ownership checks, webhooks entry.
- `controller/InvestorController.java`, `service/InvestorService.java` — onboarding, 409 logic, `ensureMfInvestmentAccount`.
- `controller/GlobalExceptionHandler.java` — status-code mapping (409/502/503), `ConflictErrorResponse`.
- `controller/InvestorActionController.java` — investor-action pages, 502/503, `app.frontend.origin`.
- `controller/CybrillaWebhookController.java` — webhook routing.
- `integration/RealCybrillaClient.java` — FP/POA calls, catalogue, BAV, order-ready PATCH (large file).
- `service/ProductService.java` — `resolveSchemesPage(local,…)`: `local=true` → DB rows; default → live POA.
- `src/main/resources/db/migration/` — Flyway (V1…V51); V41–V51 are demo-data fixups (BUG-022).

**Frontend (live copy, `src/`):**
- `views/Transactions.tsx` — the page fixed this session.
- `config/api.ts` (`apiFetch`), `utils/pagination.ts` (`getPageContent`/`getPageMeta`), `utils/productSchemeKey.ts` (`isPersistedSchemeId`), `utils/investorAction.ts`.

**Cybrilla integration rules (read before touching FP code):** `.codex/skills/cybrilla-boss/references/` (e.g. `fp-profile-patch-rules.md`, `pre-verifications.md`).

---

## 9. Verification commands

```powershell
# Backend (from ...\platiziowealthtech-Back_end\platiziowealthtech-Back_end)
.\mvnw.cmd -B -ntp test            # expect: Tests run: 281, Failures: 0, Errors: 0

# Frontend (from ...\platiziowealthtech-Front_End for now\platiziowealthtech-Front_End_sample)
npm run build                      # vite build — expect exit 0
npx tsc --noEmit                   # type-check (1 known unrelated error in ProductMgmt.tsx)
npm run dev                        # serve on :3000 (HMR)
```

---

## 10. Gotchas / things to know

- **Heavy uncommitted WIP:** the backend tree has ~9,654 uncommitted lines (a KYC/Cybrilla feature build-out) *plus* this session's fixes. Line numbers in older reports are stale — verify against current code.
- **Two bug registries, different numbering:** the canonical merged registry is the root `bugs.md` (reconciles `docs/bugs.md`). Don't trust cross-registry IDs without checking the mapping in `bugs.md`.
- **Three frontend copies** — only edit "Front_End for now" (§2).
- **Vite doesn't type-check** — only *syntax* errors break `vite build`/the dev route; type errors surface via `tsc`/`npm run lint`, not at runtime.
- **`ProductMgmt.tsx`** has one pre-existing `tsc` error (`Product` vs `SchemeLike`) — unrelated to Transactions, left as-is.
- **`DemoOrderAdvancer`** auto-advances orders to SUCCESSFUL on the `local` profile (~15s) — convenient for demos, but it masks real FP flows (BUG-030).
- **Security batch is intentionally deferred** per the user — do functionality first, then auth/security.
- All work is **uncommitted** — nothing has been pushed; the user commits when ready.

---

<!-- Merged note (investor-distributor-linking lineage): the engineering context below
     supersedes/extends the handoff above as of 2026-06-23. -->

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
