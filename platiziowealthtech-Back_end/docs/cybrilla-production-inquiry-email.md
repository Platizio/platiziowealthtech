# Cybrilla / Fintech Primitives — Production Onboarding Inquiry

**Official contacts (from FP API docs):**
- Email: **customerservice@cybrilla.com**
- Production API base: `https://api.fintechprimitives.com`
- POA production base: `https://api.cybrilla.com`
- Sandbox API base: `https://s.finprim.com`

**Important:** Cybrilla does **not** publish production test PANs or investor data on the public internet. Production KYC uses **real investor PANs** verified against ITD/KRA. Sandbox uses **simulator PAN patterns** only (documented below).

---

## Email template (copy, customize, send)

**To:** customerservice@cybrilla.com  
**Cc:** your Cybrilla account manager (if assigned)  
**Subject:** Production credentials & go-live checklist — Platizio tenant (`platizio`)

---

Dear Cybrilla / Fintech Primitives team,

We are integrating the **FP-Cybrilla POA gateway** for mutual fund distribution under tenant name **`platizio`**. Our backend (Platizio) already implements:

- Dual OAuth: POA pre-verification (`/v2/auth/cybrillarta/token`) and tenant token (`/v2/auth/platizio/token`)
- POA pre-verification (`/poa/pre_verifications`)
- FP investor profile, address, email, phone, bank account, MF investment account
- Fresh KYC: `/v2/kyc_requests` → `/v2/identity_documents` (Aadhaar Digilocker) → `/v2/esigns`
- MF purchases, consent, and netbanking payment flow

We are ready to move from sandbox to **production** and request your help with the items below.

### 1. Production OAuth credentials

Please confirm or provision:

| Credential | Sandbox (current) | Production (requested) |
|------------|-------------------|------------------------|
| POA pre-verification `client_id` | `mfdptnr_platizio_test_*` | Production POA client (no `_test_`) |
| POA pre-verification `client_secret` | (on file) | Production secret |
| FP tenant `client_id` | `platizio_test_*` | Production tenant client |
| FP tenant `client_secret` | (on file) | Production secret |
| `tenant_name` | `platizio` | Confirm unchanged |
| `x-tenant-id` header value | (empty) | Provide if required for our tenant |

**Token URLs we will use in production:**
- `POST https://api.fintechprimitives.com/v2/auth/cybrillarta/token`
- `POST https://api.fintechprimitives.com/v2/auth/platizio/token`

### 2. Network / IP allowlisting

Our development environment experienced **HTTP connect timeouts** to `api.fintechprimitives.com`. Please confirm:

- Is IP allowlisting required for production API access?
- If yes, which IP ranges should we register?
- Are there firewall or VPN requirements for Indian tenants?

### 3. Webhooks

Please share:

- Webhook URL format and supported event types (`pre_verification.*`, `kyc_request.*`, `identity_document.*`, `esign.*`, bank pre-verification)
- How to obtain and rotate `X-Cybrilla-Webhook-Secret`
- Recommended retry behaviour and idempotency guidance

Our webhook endpoint (to be registered):

```
POST https://<OUR_PRODUCTION_BACKEND>/api/v1/cybrilla/webhooks
Header: X-Cybrilla-Webhook-Secret: <shared secret>
```

### 4. Production testing guidance

We understand sandbox simulator rules (from your docs):

| Scenario | Sandbox PAN pattern |
|----------|---------------------|
| KYC already compliant | `XXXPX3751X` (e.g. `AAAPA3751A`) |
| KYC unavailable (fresh KYC) | `XXXPX3753X` (e.g. `BBBPB3753B`) |
| Bank BAV success | account ending `1193` |
| Bank BAV failure | account ending `1515` |
| Order success / fail | amount ending `0` / `1` |

**For production**, please confirm:

1. Is there a **UAT / pre-production** environment with real APIs but non-live settlement?
2. If not, what is the recommended **pilot** process (e.g. internal employees, capped amounts)?
3. Are there **approved test PANs** for production smoke tests, or must we use real employee PANs only?
4. Does our AMFI ARN licence affect KYC Check (`POST /api/kyc/check`) — status only vs full `entity_details`?

### 5. KYC / Digilocker / eSign

Please confirm production behaviour for:

- `POST /v2/identity_documents` with `type=aadhaar` — Digilocker redirect and postback URL requirements
- `POST /v2/esigns` — postback URL and supported browsers
- Typical SLA for KYC request approval after eSign
- Whether `requirements.fields_needed` commonly includes `signature` and how you recommend uploading it

### 6. Go-live checklist

Please share your standard **production go-live checklist** for POA gateway tenants, including:

- HMAC / additional production auth (if mandatory per your security page)
- Scheme catalogue availability on `GET /v2/mf_scheme_plans/cybrillapoa`
- Payment postback URL registration
- Support escalation contact for production incidents

### Our details

- **Company / brand:** Platizio Wealthtech
- **Tenant name:** platizio
- **Use case:** Distributor portal — investor onboarding, KYC, bank verification, lump-sum MF purchase via Cybrilla POA
- **Target:** First successful production transaction within 1 week of credential provisioning

Thank you. We can schedule a short call if easier.

Best regards,  
[Your name]  
[Your role]  
[Company]  
[Phone]  
[Email]

---

## What to do while waiting for Cybrilla

1. **Sandbox E2E** — use `docs/cybrilla-kyc-test-matrix.csv` with `CYBRILLA_ENVIRONMENT=sandbox` and `s.finprim.com`.
2. **Fix local network** — verify `curl` to both token URLs succeeds.
3. **Configure postbacks** — set `CYBRILLA_KYC_FORM_CALLBACK_BASE_URL` to your deployed frontend origin.
4. **Set webhook secret** — `CYBRILLA_WEBHOOK_SECRET` before non-local deployment.
5. **Restart backend** after credential swap; delete `~/.platizio-wealthtech/external-auth-token-cache.json`.
