# Page → Backend → Cybrilla Map

Use this when building or fixing a React page. `achilles` wires UI; `cybrilla-boss` validates provider steps.

**API base:** `http://localhost:8081/api/v1` (dev proxy `/api/v1` from Vite :3000)

## Distributor pages

| Page | Route | Primary backend APIs | Cybrilla / FP APIs | Token |
|------|-------|---------------------|-------------------|-------|
| Dashboard | `/distributor/dashboard` | `GET /dashboard/distributor/{id}` | Holdings poll via local orders + FP purchase status | FP |
| Investors | `/distributor/investors` | `GET /investors`, `POST /investors/sync-from-cybrilla` | `GET /v2/investor_profiles` (list/filter) | FP |
| Investor onboarding | `/distributor/investor-onboarding` | `POST /investors`, `.../kyc-flow/*`, `.../kyc-checks`, `.../kyc-requests`, `.../identity-documents`, `.../bank-accounts` | POA `POST /poa/pre_verifications`; FP KYC + identity docs | POA + FP |
| Investor KYC modify | `/distributor/investors/:id/kyc-modify` | `GET/POST .../kyc-form/*`, `.../refresh`, `.../esign/start` | FP `/v2/kyc_requests`, `/v2/identity_documents` | FP |
| Ledger | `/distributor/ledger` | `GET /products/schemes/page`, `POST /orders` | POA `GET /v2/mf_scheme_plans/cybrillapoa` for orderable ISINs | FP |
| Investor transaction | `/distributor/investor-transaction` | `GET /investors/search`, `POST /orders` | Same as Ledger + `ensureMfInvestmentAccount` pre-flight | FP |
| Portfolio | `/distributor/portfolio` | `GET /dashboard/distributor/{id}/portfolio` | Aggregates local orders; unknown scheme → Payment Failed | — |
| Transactions | `/distributor/transactions` | `GET /orders`, filters | Order list from local DB synced with FP IDs | — |
| SIP dashboard | `/distributor/sip-dashboard` | `GET /orders?sip=`, `POST /orders/{id}/cancel` | FP `POST /v2/mf_purchase_plans/cancel` | FP |
| Investor redeem | `/distributor/investors/:id/redeem` | `POST /orders/{id}/redemption` | FP `POST /v2/mf_redemptions` | FP |
| Notifications | `/distributor/notifications` | `GET /notifications/distributor/{id}` | Local only | — |

## Admin pages

| Page | Route | Primary backend APIs | Cybrilla note |
|------|-------|---------------------|---------------|
| Product mgmt | `/admin/product-mgmt` | `GET /products/schemes`, `POST /products/schemes/sync` | OMS sync = reference; label "not for orders" |
| Investor mgmt | `/admin/investor-mgmt` | `GET /investors`, KYC admin actions | FP profile IDs |

## Investor-action (backend HTML, not React)

| Step | URL | Backend | FP APIs |
|------|-----|---------|---------|
| Consent + payment | `http://localhost:8081/investor-actions/{token}` | `InvestorActionService` | purchase consent → confirm → `POST /api/pg/payments/netbanking` |
| Sandbox simulate | same + `.../sandbox/simulate-payment` | sandbox helpers | FP sandbox simulation doc |
| Mandate approve | `.../sandbox/simulate-mandate` | SIP branch | mandate authorize simulate |

## Page-build checklist (copy for each new page)

```
[ ] Route added in App.tsx under AppLayout
[ ] apiFetch only — no direct FP/POA URLs in React
[ ] loading / error / empty / success states
[ ] disable submit while in-flight
[ ] BACKEND_ORIGIN for investor-action links (not :3000)
[ ] Cybrilla async states not collapsed to instant success
[ ] Sandbox test row documented in PR/chat
[ ] pending-work.md gap updated if closing an item
```

## Common page mistakes

| Mistake | Fix |
|---------|-----|
| Catalogue from OMS or `?local=true` on Ledger | Use POA `mf_scheme_plans/cybrillapoa` |
| Invest Now on unknown ISIN | Backend marks `schemeKnown=false` → show Payment Failed |
| Payment link opens on :3000 | Use `buildInvestorActionUrl` → `:8081` |
| SIP listed before payment/mandate complete | `pendingSetup` bucket until ACTIVE |
| Cancel SIP via DELETE | Use `POST /orders/{id}/cancel` → FP cancel API |
| 401 on refresh with absolute API URL | Dev uses `/api/v1` proxy for cookies |

## Official docs (re-check before coding)

See [docs-index.md](docs-index.md). Minimum set for transactions:

- POA pre-verifications
- FP cybrillapoa API reference
- FP gateway overview + sandbox simulation
- Mandate payment use cases
