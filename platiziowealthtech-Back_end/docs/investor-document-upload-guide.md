# Investor document upload — Platizio backup + Cybrilla mapping

This guide covers **step 7 (Documents)** in Investor Onboarding: PAN card, address proof, and signature specimen.

## What goes where

| Document | Platizio UI step | Stored in Platizio DB | Sent to Cybrilla automatically? |
|----------|------------------|----------------------|--------------------------------|
| **PAN card** (scan) | Step 7 | `investor_documents` (`document_type=PAN`, `bytea` content) | No — compliance copy for distributor records |
| **Address proof** (scan) | Step 7 | `investor_documents` (`document_type=ADDRESS`) | No for manual upload — **Path B KYC** attaches address via Digilocker Aadhaar fetch on step 4 |
| **Signature** (scan) | Step 7 | `investor_documents` (`document_type=SIGNATURE`) | No on step 7 — Cybrilla **KYC modify form** (`kyc_form`) has a separate signature upload API when modifying existing KYC |

**Platizio rule:** PostgreSQL is the **backup / source of truth** for files the distributor collects. Cybrilla receives investor profile, KYC, bank, and orders through backend APIs only.

### Database table

```sql
investor_documents (
  investor_id, distributor_id, document_type,  -- PAN | ADDRESS | SIGNATURE | KYC
  file_name, content_type, size_bytes,
  content bytea,   -- full file bytes
  uploaded_by, created_at, updated_at
)
```

Unique constraint: one row per `(investor_id, document_type)` — re-upload replaces the file.

### API endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/v1/investors/{id}/documents` | List saved document metadata (no binary in JSON) |
| `PUT` | `/api/v1/investors/{id}/documents` | Upload/replace (`documentType` + `file` multipart) |

Allowed types: `PAN`, `ADDRESS`, `SIGNATURE`, `KYC`  
Allowed files: PDF, JPG, PNG — max **5 MB**

---

## Cybrilla documentation (official)

| Topic | URL |
|-------|-----|
| FP KYC request (Aadhaar / Digilocker proofs) | https://docs.fintechprimitives.com/identity/kyc-request |
| FP identity documents API | https://fintechprimitives.com/docs/api/ (search `identity_documents`) |
| POA pre-verification (PAN/name/DOB/bank) | https://poa.cybrilla.com/docs/additional-apis/pre-verifications |
| Sandbox simulation (PAN, bank, orders) | https://docs.fintechprimitives.com/fp-cybrillapoa-gateway/sandbox-simulation |
| Bank verification | https://docs.fintechprimitives.com/identity/verification/perform-bank-account-verification/ |
| Cybrilla support draft (internal) | `docs/cybrilla-support-email-draft.md` |

---

## Sample test data (full onboarding)

Use with **Investor Onboarding** after KYC + bank steps.

### Identity (step 1)

| Field | Fast path (KYC ready) | Full KYC path |
|-------|----------------------|---------------|
| Name | Rajesh Kumar | Priya Sharma |
| PAN | `AAAPA3751A` | `BBBPB3753B` |
| DOB | `1990-05-15` | `1990-05-15` |
| Mobile | `9876543210` | `9876543211` |
| Email | `demo.fast@platizio.test` | `demo.kyc@platizio.test` |

### Bank (step 5)

| Field | Value |
|-------|--------|
| Account | `1234567891193` |
| IFSC | `HDFC0001234` |
| Type | Savings |

### Documents (step 7) — create local test files

Cybrilla does **not** provide sample PAN/signature images. Use any small PDF or JPG under 5 MB.

**Option A — use any file on your PC**

- `pan-card.pdf` — any PDF or photo of a sample PAN layout
- `address-proof.pdf` — Aadhaar/utility bill scan (or blank PDF for sandbox)
- `signature.png` — signature on white paper (photo or scan)

**Option B — generate blank test files (PowerShell)**

```powershell
# Run from any folder; creates docs/sample-assets/test-files/
$dir = "docs/sample-assets/test-files"
New-Item -ItemType Directory -Force -Path $dir | Out-Null

# Minimal valid PNG (1x1 pixel)
[IO.File]::WriteAllBytes("$dir/signature.png", [Convert]::FromBase64String(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))

# Tiny PDF
@"
%PDF-1.1
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/MediaBox[0 0 200 200]/Parent 2 0 R>>endobj
xref
0 4
0000000000 65535 f
0000000010 00000 n
0000000059 00000 n
0000000112 00000 n
trailer<</Size 4/Root 1 0 R>>
startxref
190
%%EOF
"@ | Set-Content -NoNewline "$dir/pan-card.pdf" -Encoding ascii

Copy-Item "$dir/pan-card.pdf" "$dir/address-proof.pdf"
```

Upload mapping in UI:

| File | documentType |
|------|----------------|
| `pan-card.pdf` | PAN |
| `address-proof.pdf` | ADDRESS |
| `signature.png` | SIGNATURE |

---

## Expected UX (step 7)

1. Investor draft must exist (steps 1–3 create it; KYC step 4 uses same id).
2. Select file → **uploads immediately** to Platizio DB.
3. Green **Saved in Platizio** badge when `GET /documents` returns the type.
4. **Submit Application** only proceeds when all three types are saved.
5. On resume, previously saved documents load from `GET /investors/{id}/documents`.

---

## Cybrilla vs Platizio — address proof note

- **Path A** (`AAAPA3751A`): KYC already compliant — step 7 address proof is **local compliance only**.
- **Path B** (`BBBPB3753B`): After Digilocker Aadhaar fetch, Cybrilla attaches `identity_proof` and `address.proof` to the `kyc_request`. Step 7 address upload is still stored locally as distributor backup.

---

## Support contacts

See `docs/cybrilla-support-email-draft.md` and `docs/cybrilla-credentials-and-network-report.md` for sandbox credentials, network checks, and support email template.
