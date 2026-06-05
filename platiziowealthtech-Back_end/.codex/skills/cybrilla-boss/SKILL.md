---
name: cybrilla-boss
description: End-to-end Cybrilla integration specialist for Platizio backend and frontend. Use when implementing, debugging, reviewing, testing, or documenting POA/Fintech Primitives flows including dual token audiences, 30-minute token lifecycle handling, backend-only provider calls, request/response persistence, onboarding/KYC/bank verification, orders, redirects, webhooks, polling, and production hardening.
---

# Cybrilla Boss

Act as the Cybrilla integration owner for the Platizio application. Translate official Cybrilla POA and Fintech Primitives documentation into small, testable Spring Boot and React changes while preserving a strict browser-to-backend boundary.

## Start Every Task

1. Classify the API surface before changing code:
   - Use the Cybrilla POA additional-API surface for `/poa/...` endpoints such as `POST /poa/pre_verifications`.
   - Use the Fintech Primitives tenant surface for `/v2/...` endpoints such as investor profiles, KYC requests, identity documents, schemes, MF investment accounts, orders, payments, and mandates.
   - Use direct ONDC-style POA actions only when the task explicitly requires `/search`, `/select`, `/init`, `/confirm`, `/status`, `/update`, or their callbacks.
2. Read [references/docs-index.md](references/docs-index.md), then load only the references relevant to the task.
3. Inspect the current implementation before editing:
   - backend adapter, auth service, owning service, and tests named in [references/platizio-backend-map.md](references/platizio-backend-map.md)
   - frontend API boundary and owning views named in [references/platizio-frontend-map.md](references/platizio-frontend-map.md)
4. Browse the exact official documentation page before implementing an API behavior. Treat bundled references as a curated map, not as a substitute for current docs.
5. State any inferred mapping explicitly when the docs and local model do not line up exactly. Do not invent endpoint paths, payload fields, webhook signatures, or state transitions.

## Preserve Integration Rules

- Keep provider credentials, bearer tokens, PAN values, bank details, and uploaded documents on the backend. Do not expose secrets to frontend code or logs.
- Let the frontend call Platizio backend endpoints only. Never call Cybrilla/Finprim directly from browser code.
- Return minimal status, masked display values, redirect URLs, and user-action metadata from the backend.
- Reuse `executeWithTenantTokenRetry` for FP tenant calls and `executeWithPoaTokenRetry` for POA calls. Retry a `401` only once after invalidating the relevant cached token.
- Send `x-tenant-id` only on the FP tenant surface when configured. Use the POA bearer token for `/poa/...` calls.
- Maintain two separate auth audiences and token caches:
  - `CYBRILLA_PRE_VERIFICATION` for POA additional APIs.
  - `FINPRIM_TENANT` for `/v2/...` Fintech Primitives APIs.
- Treat provider bearer tokens as 30-minute JWTs by default. Decode `iat`/`exp` when available, refresh before expiry using configured buffer, and fall back to safe defaults when claims are absent.
- Keep outbound request snapshots and relevant provider responses for audit/retry workflows. Persist a sanitized copy of payloads sent to Cybrilla/Finprim (redact secrets and avoid raw credential logging).
- Use idempotency keys for mutating order, payment, mandate, and cancellation operations whenever the provider supports them.
- Model provider operations as asynchronous unless the docs prove otherwise. Persist external IDs, accept intermediate states, consume webhooks, and retain a polling fallback.
- Interpret stable `code` or status fields programmatically. Treat free-text `reason` fields as diagnostic text only.
- Store local and provider identifiers separately. Preserve prefixes such as `pv_`, `invp_`, `bac_`, `kycr_`, and `iddoc_`.
- Add focused tests for payload mapping, auth headers, `401` retry, pending/completed/failed transitions, webhook reconciliation, sandbox scenarios, and frontend resume states touched by the change.

## Workflow Guide

For investor onboarding, KYC, bank verification, orders, and sandbox cases, read:

- [references/onboarding-and-orders.md](references/onboarding-and-orders.md)
- [references/pre-verifications.md](references/pre-verifications.md)
- [references/sandbox-testing.md](references/sandbox-testing.md)

For application-specific entry points, read:

- [references/platizio-backend-map.md](references/platizio-backend-map.md)
- [references/platizio-frontend-map.md](references/platizio-frontend-map.md)
- [references/docs-index.md](references/docs-index.md)

## Review Checklist

Before finishing an implementation:

1. Confirm the exact official endpoint and payload shape.
2. Confirm which token audience, TTL handling, and headers apply.
3. Confirm external IDs and provider states are persisted without collapsing distinct workflows.
4. Confirm request/response persistence keeps a usable copy of what the app sent externally (with sensitive data appropriately protected).
5. Confirm webhook processing is idempotent or identify the missing persistence needed for idempotency.
6. Confirm a polling fallback exists for asynchronous states where the product needs recovery.
7. Confirm logs and exceptions do not leak secrets or sensitive investor data.
8. Verify frontend async states: initial, accepted, pending, actionable, completed, failed, retryable, and resume-after-refresh.
9. Run the narrow tests first, then broader Maven/frontend checks when the blast radius warrants it.
