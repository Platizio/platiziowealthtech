# Cybrilla credentials & network report (Platizio)

Generated from `.env` review and connectivity checks on the developer machine.

---

## 1. What you have in `.env` (masked)

| Variable | Value pattern | Assessment |
|----------|---------------|------------|
| `CYBRILLA_PRE_VERIFICATION_CLIENT_ID` | `mfdptnr_platizio_test_...` | **Sandbox** POA client |
| `FINPRIM_TENANT_CLIENT_ID` | `platizio_test_...` | **Sandbox** tenant client |
| `FINPRIM_TENANT_NAME` | `platizio` | OK |
| `FINPRIM_TENANT_NAME` | `platizio` | Sent as **`x-tenant-id: platizio`** on all FP `/v2/...` calls |
| `FINPRIM_TENANT_ID` | *(empty)* | Leave empty; tenant **name** is the header value |
| `FINPRIM_TENANT_TOKEN_URL` | `.../v2/auth/tenant/token` | Auto-rewritten to `.../v2/auth/platizio/token` |
| Client secrets | Present | OK — do not share in email/chat |

**Conclusion:** These are **sandbox / integration test** credentials, not production go-live credentials. Cybrilla’s [going-live checklist](https://docs.fintechprimitives.com/fp-cybrillapoa-gateway/going-live/) states **production credentials are issued only after** ONDC signup, POA eSign, product demo, and RTA mailback setup.

There is **no public sample production client_id** on the internet — only the registration process via **customerservice@cybrilla.com**.

---

## 2. Configuration mismatch (fixed in `.env`)

Previously:

- `CYBRILLA_ENVIRONMENT=production`
- Production URLs (`api.fintechprimitives.com`, `api.cybrilla.com`)
- Sandbox client IDs (`*_test_*`)

That combination cannot work: sandbox secrets authenticate against **sandbox** token URLs (`s.finprim.com`), not production realms.

`.env` is now aligned to **sandbox + localhost** until Cybrilla sends production clients.

---

## 3. Network diagnosis

### Tests run today

| Target | TCP 443 | HTTP result |
|--------|---------|-------------|
| `api.fintechprimitives.com` | **Reachable** | Responds in ~1s |
| `s.finprim.com` | **Reachable** | Responds in ~1s |
| `api.cybrilla.com` | **Reachable** | — |
| `api.sandbox.cybrilla.com` | **Reachable** | — |

Anonymous token probe (no real secrets):

- **Production** `POST https://api.fintechprimitives.com/v2/auth/platizio/token` → `{"error":"Realm does not exist"}`
- **Sandbox** `POST https://s.finprim.com/v2/auth/platizio/token` → `invalid_client` (expected with dummy credentials)

### What this means

1. **Right now, the network is not permanently blocked.** TCP and HTTPS to Cybrilla/FP work from this machine.
2. **Earlier backend errors** (`HTTP connect timed out` in Java logs) were likely **intermittent** — Wi‑Fi/ISP blip, antivirus inspecting Java traffic, or JVM HTTP client timing out before TCP completed.
3. **`Realm does not exist`** on production means tenant **`platizio` is not provisioned on production OAuth** yet (or sandbox credentials were used against production URL). This is an **onboarding/credentials** issue, not a firewall issue.

### If timeouts return

Ask Cybrilla whether production requires:

- **IP allowlisting** (their security page mentions restricted server access)
- **HMAC authentication** for production (mandatory per Cybrilla security docs)
- **VPN or fixed egress IP** for API calls

---

## 4. Localhost — do you need `CYBRILLA_KYC_FORM_CALLBACK_BASE_URL`?

**What it is for:** After Aadhaar Digilocker or eSign on Cybrilla’s site, the investor’s browser is **redirected back** to your app at a URL you provide (`postback_url`).

**On localhost:**

- The React app already builds postback URLs from `window.location.origin`, e.g.  
  `http://localhost:5173/distributor/investor-onboarding?investorId=...&kycReturn=aadhaar`
- So for normal onboarding clicks, **you do not need a public URL**.
- The backend `.env` callback base is only a **fallback** when the API is called without `postbackUrl` (e.g. `kyc-flow/advance`). Set to `http://localhost:5173` for local dev.

**You do NOT need this for:** token generation, pre-verification, or KYC status polling.

**You WILL need a public HTTPS URL in production** so investors returning from Digilocker/eSign land on your live portal.

---

## 5. Live verification (9 June 2026)

After clearing token cache and restarting backend:

| Test | Result |
|------|--------|
| FP token `s.finprim.com/v2/auth/platizio/token` | OK (`expires_in=1800`) |
| POA token `s.finprim.com/v2/auth/cybrillarta/token` | OK (`expires_in=1800`) |
| Pre-verification `BBBPB3753B` | `kyc_unavailable` |
| Pre-verification `AAAPA3751A` | `readiness=verified` |
| Backend startup `finprim_base_url` | `https://s.finprim.com` |

## 6. After changing `.env`

1. Delete stale tokens: `C:\Users\HP\.platizio-wealthtech\external-auth-token-cache.json`
2. Restart backend: `.\mvnw.cmd spring-boot:run`
3. Use **sandbox PANs** from `docs/cybrilla-kyc-test-matrix.csv` (e.g. `BBBPB3753B` for kyc_unavailable)

---

## 6. Email to Cybrilla

See **`cybrilla-support-email-draft.md`** in this folder (copy-paste ready).
