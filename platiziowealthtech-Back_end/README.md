# Wealthtech Backend - Stage 1 MVP

Spring Boot backend starter for the PRD "Stage-1 MVP - Distributor led MF and SIF transactions".

## What this backend covers

- Distributor signup, approval-state lifecycle, profile and payout details
- Distributor hierarchy with EUIN, admin, master distributor, and sub distributor roles
- Investor creation and onboarding lifecycle
- KYC status tracking
- Bank account capture and verification tracking
- Product catalog sync from Cybrilla / Fintech Primitives
- Order creation and order state management
- Redemption flow and bank-credit state tracking
- Portfolio summary placeholders
- Notifications
- Investor lead lifecycle with distributor assignment and interaction history
- Audit-friendly status/event recording

## What is intentionally kept simple in this starter

- No full JWT implementation yet
- No actual Cybrilla API wiring yet; only interface + mock service contract
- No file storage for documents yet
- No async queue infrastructure yet
- No investor panel and no admin panel UI

## Suggested next build order

1. Plug in real authentication
2. Add Cybrilla auth and API client
3. Complete investor onboarding orchestration
4. Add webhook ingestion for order / payment / mandate / KYC updates
5. Expand reporting and earnings mappings
6. Add document storage and signed URLs
7. Add production-grade audit, idempotency, rate limiting, and retry handling

## Local run

Create a small local PostgreSQL database and set credentials through environment variables, or use the defaults from `src/main/resources/application.yml`:

```sql
create database wealthtech;
create user wealthtech with encrypted password 'wealthtech';
grant all privileges on database wealthtech to wealthtech;
```

For normal local startup, run:

```bash
.\mvnw.cmd spring-boot:run
```

For local HTTP-only development, run with the `local` Spring profile:

```bash
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

The default config currently includes a local-only JWT secret fallback so the backend starts without extra environment setup. Set `JWT_SECRET` to a strong random value before using any shared, staging, or production environment. The `local` profile also sets `app.auth.cookie-secure: false` for HTTP development; do not use that profile outside local development.

JWT cookies are `Secure` by default via `AUTH_COOKIE_SECURE=true`, so browsers only send them over HTTPS unless explicitly overridden.

Default database variables:

- `DB_URL=jdbc:postgresql://localhost:5432/wealthtech`
- `DB_USERNAME=wealthtech`
- `DB_PASSWORD=wealthtech`

## Distributor and Investor APIs

- `POST /api/v1/distributors/signup` creates an admin, master distributor, or sub distributor. Use `role=MASTER_DISTRIBUTOR` for master distributors and `role=SUB_DISTRIBUTOR` plus `masterDistributorId` for sub distributors. `eUinNumber` is stored uniquely when provided.
- `GET /api/v1/distributors/sub-distributors?requesterId={id}` returns all sub distributors when the requester is an admin, or the requester's direct sub distributors when the requester is a master distributor.
- `POST /api/v1/investors` creates an investor under a distributor.
- `GET /api/v1/investors/by-distributor/{distributorId}` returns investors assigned to one distributor.
- `GET /api/v1/investors/by-distributor/{distributorId}/visible-to/{requesterId}` returns investors for a distributor only when the requester is allowed to view them.
- `GET /api/v1/investors/visible-to/{requesterId}` returns all investors for an admin, the master distributor plus sub distributor book for a master distributor, or the direct investor book for a sub distributor.

## Cybrilla integration handoff

See `CYBRILLA_INTEGRATION.md` for the adapter contract, API mapping checklist, environment variables, and the suggested real-client implementation shape.

## Main modules

- `auth`
- `common`
- `config`
- `controller`
- `domain`
- `dto`
- `integration`
- `repository`
- `service`

## Important design choice

Even though the PRD says there is no admin panel in stage 1, the backend still exposes internal approval actions because distributor approval happens manually offline and the system must support pending, approved, rejected, and inactive states.
