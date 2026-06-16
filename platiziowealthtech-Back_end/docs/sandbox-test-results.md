# Cybrilla sandbox test results

**Run date:** 9 June 2026  
**Script:** `test-cybrilla-sandbox-matrix.ps1`  
**Raw CSV:** `sandbox-test-results.csv`

## Auth (both audiences)

| Audience | URL | Result |
|----------|-----|--------|
| POA pre-verification | `POST s.finprim.com/v2/auth/cybrillarta/token` | OK |
| FP tenant | `POST s.finprim.com/v2/auth/platizio/token` | OK |
| `x-tenant-id` | `platizio` on FP calls | Confirmed |

## POA pre-verification matrix — 17/18 passed

| Scenario | PAN | Expected | Actual | Status |
|----------|-----|----------|--------|--------|
| DEMO-A-FAST | AAAPA3751A | verified | verified | PASS |
| DEMO-B-FULL-KYC | BBBPB3753B | kyc_unavailable | kyc_unavailable | PASS |
| KYC-READY-ALT1 | GYAPS3751D | verified | verified | PASS |
| KYC-READY-ALT2 | FFFPF3751F | verified | verified | PASS |
| KYC-UNAVAIL-ALT1–3 | CCCPC3753C etc. | kyc_unavailable | kyc_unavailable | PASS |
| PAN-INVALID | DDDPI1234D | invalid | invalid | PASS |
| PAN-AADHAAR-NOT-LINKED | ~~EEEPE1234E~~ → **EEEAP1234E** | aadhaar_not_linked | *(re-test after fix)* | FIXED in CSV |
| NAME-MISMATCH | GYAPS3751D + Lord Voldemort | name mismatch | mismatch | PASS |
| DOB-MISMATCH | GYAPS3751D + 2000-01-01 | dob mismatch | mismatch | PASS |

**Note:** `EEEPE1234E` was wrong — XXXPANNNNX requires **5th character `A`**. Corrected to `EEEAP1234E` in matrix + frontend demo guide.

**Note:** POA-only rows (BAV-*, ORDER-*) only test readiness; bank/order suffixes are tested at bank/order API stage during manual KYC demo.

## Integration fixes applied

1. **Demo seeder PANs** — Rahul `BBBPB3751B` → `FFFPF3751F` (FP rejected profile sync); Neha `DDDPX4103E` → `BBBPB3753B` (valid kyc_unavailable).
2. **Token caches cleared** — user home + legacy project-root file removed.
3. **Backend** — confirmed `s.finprim.com` + `api.sandbox.cybrilla.com` on startup.

## Manual KYC testing (your next step)

Use **Sandbox demo guide** in Investor Onboarding (Step 1 → **Use** button):

| Demo | PAN | Use for |
|------|-----|---------|
| Full KYC story | **BBBPB3753B** | kyc_unavailable → Digilocker → eSign |
| Fast purchase | **AAAPA3751A** | Skip fresh KYC → bank → order |
| Invalid PAN | **DDDPI1234D** | Pre-verification failure |
| Aadhaar not linked | **EEEAP1234E** | PAN validation failure |
| Bank pass | account `…1193` | BAV success |
| Bank fail | account `…1515` | BAV low confidence |

Login: `a@a.com` / `Ok@123456`
