---
name: cybrilla-frontend-expert
description: Platizio React/Vite frontend expert under Cybrilla Boss. Use for InvestorOnboarding, Investors, InvestorMgmt, ProductMgmt, Ledger, kycPreVerification utils, apiFetch boundary, and onboarding/KYC/transaction UI states. Ensures browser never calls Cybrilla directly and Cybrilla workflows map cleanly to backend endpoints.
---

# Cybrilla Frontend Expert

Sub-agent of [cybrilla-boss](../SKILL.md). Owns distributor/investor UI and the Platizio API boundary.

## Before Every Task

1. Read parent [SKILL.md](../SKILL.md): **never call Cybrilla/Finprim from the browser**.
2. Load [references/platizio-frontend-map.md](../references/platizio-frontend-map.md).
3. Trace: view → `apiFetch` → backend endpoint → expected Cybrilla side effect (console `[Cybrilla Workflow]` logs).

## Key Files

| Area | Path |
|------|------|
| API base | `src/config/api.ts` — `VITE_API_BASE_URL` must point to backend (`http://localhost:8081/api/v1`) |
| KYC utils | `src/utils/kycPreVerification.ts` |
| Onboarding | `src/views/InvestorOnboarding.tsx` |
| Investor list / detail | `src/views/Investors.tsx` |
| Admin investors | `src/views/InvestorMgmt.tsx` |
| Products | `src/views/ProductMgmt.tsx`, `Ledger.tsx` |
| Orders | `src/views/Transactions.tsx`, `InvestorTransaction.tsx` |

## KYC UI Rules

- One mutating KYC path per user action — no `kyc-checks` + `kyc/apply` double-submit.
- Skip `POST .../kyc/apply` on final submit when step-3 pre-verification already succeeded (`kycDecision.canProceed`).
- Use `POST .../kyc/reapply` with `forceNewCheck: true` after identity (PAN/name/DOB) change.
- Map backend `kycStatus` + `externalKycPayloadJson` to retry/failure copy via `getPreVerificationDecision()`.
- On 502, surface backend `message`; stale-reference errors → prompt fresh check.

## Product UI Rules

- Live sync: `GET /products/schemes/page?syncFromCybrilla=true&forceCatalogueRefresh=true` on explicit refresh.
- Skip 15-minute localStorage backoff when user clicks **Fetch from Cybrilla** (`forceRefresh: true`).
- Admin force sync: `POST /products/schemes/refresh` when page query sync fails.
- Dashboard/other views read cached DB — empty UI means catalogue never synced successfully.

## Testing Protocol

```bash
cd platiziowealthtech-Front_End_sample
npm run lint
npm run build
```

Manual: login → onboarding step 3 KYC → bank → products refresh → create order.

## Output Format

Report: **UI symptom → API call made → backend endpoint → fix → lint/build result**.
