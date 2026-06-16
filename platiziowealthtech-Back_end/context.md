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
