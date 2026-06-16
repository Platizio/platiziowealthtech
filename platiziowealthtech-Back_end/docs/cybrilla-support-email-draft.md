# Email to Cybrilla — connectivity, workflow, sandbox test data

**To:** customerservice@cybrilla.com (or your assigned sandbox contact)  
**Cc:** [Your Cybrilla account manager]  
**Subject:** Platizio (`platizio` tenant) — sandbox demo prep: connectivity incidents, workflow validation, test data confirmation

---

Dear Cybrilla / Fintech Primitives Support,

We are building **Platizio**, a mutual-fund distributor platform using your **FP-Cybrilla POA gateway**. All Cybrilla/Finprim calls are made **server-side** from our Spring Boot backend (dual OAuth client credentials). The browser never sees tokens or secrets.

We are preparing a **sandbox product demo this week** and need your help on three areas: **intermittent connectivity**, **workflow confirmation**, and **sandbox test data**.

---

## 1. Our sandbox configuration (confirmed working today — 9 June 2026)

After aligning `.env` and restarting our backend, we verified:

| Setting | Our value | Result today |
|---------|-----------|--------------|
| FP base URL | `https://s.finprim.com` | OK |
| POA base URL | `https://api.sandbox.cybrilla.com` | OK |
| FP tenant token | `POST https://s.finprim.com/v2/auth/platizio/token` | **200**, `expires_in=1800` |
| POA token | `POST https://s.finprim.com/v2/auth/cybrillarta/token` | **200**, `expires_in=1800` |
| `x-tenant-id` header | `platizio` on FP `/v2/...` and `/api/oms/...` | Set per your onboarding email |
| POA calls | Bearer only, no `x-tenant-id` | As documented |

**POA pre-verification (live sandbox, 9 June 2026):**

| Test PAN | Name | DOB | Pre-verification result |
|----------|------|-----|-------------------------|
| `BBBPB3753B` | Priya Sharma | 1990-05-15 | `status=completed`, `readiness.code=kyc_unavailable`, `pan.status=verified` |
| `AAAPA3751A` | Rajesh Kumar | 1990-05-15 | `status=completed`, `readiness.status=verified` |

So **auth and KYC readiness work** when we use sandbox URLs. Our remaining pain is **intermittent failures when the wrong host was used** (see below).

---

## 2. Connectivity issues — incident log

We experienced recurring failures that blocked investor profile sync, bank verification, and KYC orchestration. Below is when each symptom appeared and what we observed.

| # | Date / time (IST) | Trigger | Host / endpoint | Symptom | Our action |
|---|-------------------|---------|-----------------|--------|------------|
| 1 | 8–9 Jun, ~10:15–11:37 | Scheduled sync + KYC retries | `POST https://api.fintechprimitives.com/v2/auth/platizio/token` | `HTTP connect timed out` (Java `HttpConnectTimeoutException`) | Found stale JVM still on production URL |
| 2 | 8 Jun | Manual curl with sandbox creds | `POST https://api.fintechprimitives.com/v2/auth/platizio/token` | `{"error":"Realm does not exist"}` (HTTP 404) | Confirmed sandbox creds must not hit production |
| 3 | 8–9 Jun | After token failure | FP `/v2/investor_profiles` | `Unable to authenticate with Fintech Primitives` | Downstream of #1 |
| 4 | 8–9 Jun | After profile sync fail | Bank BAV refresh | `backing_off` retry 15 min — profile still pending | Downstream of #3 |
| 5 | **9 Jun, ~11:40** | Backend restart + cache clear | `POST https://s.finprim.com/v2/auth/platizio/token` | **Success** (~70ms connect) | Fixed |
| 6 | **9 Jun, ~11:42** | POA pre-verification | `api.sandbox.cybrilla.com/poa/pre_verifications` | **Success** — `kyc_unavailable` + `verified` cases | Fixed |

**Pattern:** Failures clustered around **production host** (`api.fintechprimitives.com`) and **stale backend process** after `.env` changes. When using `s.finprim.com` with sandbox `*_test_*` credentials, connectivity is stable.

**Questions:**

1. Can you confirm sandbox tenants must **never** call `api.fintechprimitives.com`?
2. Are there **IP allowlists**, **rate limits**, or **maintenance windows** on `s.finprim.com` / `api.sandbox.cybrilla.com` we should know about?
3. What **connect/read timeouts and retry policy** do you recommend for Java `HttpClient` from India (residential ISP)?
4. For production go-live: is tenant `platizio` provisioned on production OAuth yet, or only after the going-live checklist?

---

## 3. Workflow we are implementing — please confirm

We orchestrate investor onboarding as follows. Please confirm this is the **recommended sandbox + production path** for an AMFI-registered distributor.

```text
1. Create local investor (Platizio DB)
2. Sync FP investor profile     → POST /v2/investor_profiles (+ address, email, phone)
3. POA pre-verification         → POST /poa/pre_verifications
   (pan + name + date_of_birth + investor_identifier in one call)
4. Branch on readiness.code:
   • verified          → skip fresh KYC → bank → MFIA → order
   • kyc_unavailable   → full KYC path below
   • kyc_incomplete    → update/complete KYC
5. Full KYC (when kyc_unavailable):
   a. POST /v2/kyc_requests
   b. POST /v2/identity_documents (type=aadhaar) → Digilocker redirect
   c. Investor returns via postback URL
   d. POST /v2/esigns → eSign redirect
   e. Poll/webhook until KYC status = completed
6. Bank                         → POST /v2/bank_accounts + POA BAV (account suffix rules)
7. MF investment account        → POST /v2/mf_investment_accounts
8. Purchase                     → POST /v2/mf_purchases → payment redirect
```

**Specific workflow questions:**

1. For **`kyc_unavailable`**, is our sequence (KYC request → Aadhaar Digilocker → eSign) correct, or should we call **`POST /api/kyc/check`** first?
2. For **Digilocker/eSign in a live demo room**, what is the recommended **sandbox simulate** path if the investor cannot complete redirect in time?
3. Should **`x-tenant-id: platizio`** be sent on **all** FP `/v2/...` calls, including `investor_profiles`, `kyc_requests`, `identity_documents`, `esigns`, `bank_accounts`, `mf_purchases`?
4. Are **webhooks required** for sandbox demo, or is polling acceptable for `pre_verification.completed`, `kyc_request.*`, `identity_document.*`, `esign.*`?
5. For **localhost** development (`http://localhost:5173` postback), is that acceptable for sandbox Digilocker/eSign callbacks, or do you require HTTPS staging URL?

---

## 4. Sandbox test data — please confirm our matrix

We are using your documented simulator patterns. Please confirm these are **current and complete** for our tenant.

### KYC readiness (FP gateway PAN — 4th char `P`)

| Pattern | Example PAN | Expected `readiness.code` | Our test result (9 Jun) |
|---------|-------------|---------------------------|-------------------------|
| `XXXPX3751X` | `AAAPA3751A` | `verified` (skip fresh KYC) | **Confirmed** |
| `XXXPX3753X` | `BBBPB3753B` | `kyc_unavailable` | **Confirmed** |

### POA PAN / name / DOB validation

| Case | PAN | Name | DOB | Expected |
|------|-----|------|-----|----------|
| Valid PAN | `XXXPXNNNNX` e.g. `GYAPS3751D` | any normal | not `2000-01-01` | `pan.status=verified` |
| Invalid PAN | `XXXPINNNNX` e.g. `DDDPI1234D` | any | any | `pan.code=invalid` |
| Aadhaar not linked | `XXXPANNNNX` e.g. `EEEPE1234E` | any | any | `pan.code=aadhaar_not_linked` |
| Name mismatch | valid `XXXPXNNNNX` | `Lord Voldemort` | any | `name.code=mismatch` |
| DOB mismatch | valid `XXXPXNNNNX` | any | `2000-01-01` | `date_of_birth.code=mismatch` |

### Bank verification (last 4 digits of account number)

| Suffix | Expected |
|--------|----------|
| `1193` | Pass — use for demo |
| `1285` | Verified, high confidence |
| `1515` | Failed, low confidence |
| `1600` | Failed, zero confidence |
| `3157` | Digital verification failure |

### Order simulation

| Amount ending | Expected |
|---------------|----------|
| `0` (e.g. ₹5000) | Success |
| `1` (e.g. ₹5001) | Failure |

**Questions:**

1. Are **ABSL and Ipru AMC** schemes the right sandbox catalogue for demo purchases?
2. Is there an official **CSV/Excel** of all sandbox simulator values we can import for QA?
3. Any **additional readiness codes** or PAN patterns we should test before production?
4. For **Digilocker sandbox**, are there fixed OTP / simulate steps documented for demo presenters?

We have attached our internal test matrix: `docs/sandbox-test-matrix-full.csv` (available on request).

---

## 5. Demo timeline

| Date | Goal |
|------|------|
| This week | Sandbox demo: full path `kyc_unavailable` → KYC → bank → first purchase |
| After demo | Production credentials + webhook registration on staging HTTPS |

A **30-minute call** to validate workflow + test data would help before our demo date.

---

## 6. What we can share privately

- Redacted backend logs (`external_auth_request`, connect timeout timestamps)
- `GET /api/v1/cybrilla/integration-info` response (no secrets)
- Screen recording of sandbox KYC flow
- Our test matrix CSV

---

Thank you for your support.

Best regards,  
[Your name]  
[Role] — Platizio Wealthtech  
[Phone]  
[Email]  
ARN / entity: [your ARN]
