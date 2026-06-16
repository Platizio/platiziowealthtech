# Onboarding & KYC E2E Test Results

**Date:** 2026-06-09  
**Backend:** `http://localhost:8081` (profile `local`, demo seeder)  
**Frontend:** Vite sample app — hard-refresh after pull  
**Login:** `a@a.com` / `Ok@123456`

---

## What was finished today

| Area | Change |
|------|--------|
| **Step 7 UI** | Document checklist + green **Submit documents & complete onboarding** button |
| **Submit validation** | Blocks final submit until PAN, address proof, and signature are saved (or selected) |
| **Resume routing** | `GET /investors/{id}/onboarding/resume` returns `documentsComplete`, `missingDocumentTypes`, `nextStep=UPLOAD_DOCUMENTS` when KYC+bank OK but docs missing |
| **Frontend resume** | Loads saved docs on resume; jumps to step 7 when KYC+bank verified and docs incomplete |
| **API tests** | `test-onboarding-platizio-api.ps1` — 12/12 PASS |

---

## Maven unit tests (PASS)

```powershell
.\mvnw.cmd test "-Dtest=InvestorOnboardingResumeTest,InvestorDocumentServiceTest,InvestorServicePincodeTest,InvestorServiceIfscTest,InvestorKycServiceTest"
```

| Test class | Coverage |
|------------|----------|
| `InvestorOnboardingResumeTest` | KYC→bank→`UPLOAD_DOCUMENTS`→`READY_FOR_TRANSACTIONS` resume chain |
| `InvestorDocumentServiceTest` | Upload, list metadata (no binary), access control |
| `InvestorServicePincodeTest` | Cybrilla FP pincode proxy |
| `InvestorServiceIfscTest` | IFSC lookup proxy |
| `InvestorKycServiceTest` | POA pre-verification, sync, re-KYC skip |

---

## Platizio API matrix (`test-onboarding-platizio-api.ps1`)

**Result: 12/12 PASS** (after backend restart with latest code)

| Scenario | Status | Notes |
|----------|--------|-------|
| AUTH-LOGIN | PASS | Demo distributor cookie session |
| PIN-400001 | PASS | Mumbai → city/state from Cybrilla |
| PIN-INVALID | PASS | `000000` rejected |
| IFSC-HDFC | PASS | `HDFC0001234` resolved |
| INV-CREATE | PASS | Sandbox PAN pool (`BBBPB3753B`, etc.) |
| DOC-UPLOAD-PAN/ADDRESS/SIGNATURE | PASS | `PUT /investors/{id}/documents` multipart |
| DOC-LIST | PASS | `GET /investors/{id}/documents` — metadata only |
| RESUME-DOCS-COMPLETE | PASS | `documentsComplete=true` after 3 uploads |
| RESUME-NEXT-STEP | PASS | New investor → `APPLY_KYC` (expected) |
| DOC-COUNT | PASS | 3 rows in `investor_documents` |

**Run:**

```powershell
powershell -ExecutionPolicy Bypass -File .\test-onboarding-platizio-api.ps1
```

---

## Demo investor resume spot-check

**Anita Verma** (`9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03`) — KYC COMPLETED, bank VERIFIED, no Platizio docs:

```json
{
  "nextStep": "UPLOAD_DOCUMENTS",
  "documentsComplete": false,
  "missingDocumentTypes": ["PAN", "ADDRESS", "SIGNATURE"],
  "readyForTransactions": false
}
```

UI should open **step 7** on resume for this investor.

---

## Manual UI test matrix (run in browser)

Use sandbox data from `docs/aadhaar-kyc-sandbox-test-matrix.csv` and `docs/investor-document-upload-guide.md`.

| # | Scenario | Steps | Expected |
|---|----------|-------|----------|
| 1 | **Fast KYC path** | PAN `AAAPA3751A`, mobile `9876543211`, account `…1193`, IFSC `HDFC0001234` | Step 4 skips fresh KYC; bank verifies; step 7 submit succeeds |
| 2 | **Full KYC path** | PAN `BBBPB3753B`, Digilocker/simulate on step 4 | KYC → bank → FATCA → docs → submit |
| 3 | **BAV fail** | Account ending `1515` | Bank step shows failure; cannot proceed to SIP |
| 4 | **Pincode search** | PIN `400001`, click **Search PIN** | State Maharashtra, city dropdown populated |
| 5 | **Doc immediate save** | Upload each file on step 7 | Green **Saved in Platizio** badge per doc |
| 6 | **Submit without docs** | Click submit with 1 doc missing | Error dialog lists missing types |
| 7 | **Resume to docs** | Resume Anita Verma onboarding | Lands on step 7 with checklist |
| 8 | **Replace document** | Replace PAN after saved | New file overwrites row in DB |

**Sample files:** any PDF/JPG/PNG under 5 MB (see `docs/investor-document-upload-guide.md`).

---

## Cybrilla sandbox matrix (external — run manually)

Pre-verification matrix against Cybrilla POA (uses `.env` credentials):

```powershell
powershell -ExecutionPolicy Bypass -File .\test-cybrilla-sandbox-matrix.ps1
```

Reference rows: `docs/sandbox-test-matrix-full.csv`, `docs/aadhaar-kyc-sandbox-test-matrix.csv`.

---

## Ready for tomorrow (SIP / mandate)

Investor is **ready for transactions** when resume returns:

- `kycStatus` = COMPLETED  
- `bankVerificationStatus` = VERIFIED  
- `documentsComplete` = true  
- `nextStep` = `READY_FOR_TRANSACTIONS`  
- `readyForTransactions` = true  

Next work: MF investment account ensure, mandate creation, SIP order — per Cybrilla Boss `onboarding-and-orders.md`.

---

## Restart checklist

1. Backend: `.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local` (port **8081**)
2. Frontend: `npm run dev` in sample app — hard refresh (Ctrl+Shift+R)
3. Re-run: `.\test-onboarding-platizio-api.ps1`
