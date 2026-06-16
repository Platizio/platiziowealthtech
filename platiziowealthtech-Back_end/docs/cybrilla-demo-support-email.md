# Email to Cybrilla — demo week + connectivity issues

**To:** support@cybrilla.com (or your assigned sandbox contact)  
**Cc:** Your Cybrilla account manager  
**Subject:** Platizio sandbox demo — intermittent connectivity + environment confirmation (tenant: platizio_test)

---

Dear Cybrilla Support Team,

We are preparing a **sandbox product demo** for Platizio (mutual fund distributor onboarding + KYC + first purchase) **this week**. Our integration follows your POA pre-verification and FP tenant APIs with backend-only OAuth.

We need your help confirming our **sandbox environment configuration** and investigating **recurring connectivity issues** that block our demo rehearsals.

## 1. Tenant and environment

| Item | Our value |
|------|-----------|
| Environment | Sandbox |
| FP tenant client | `platizio_test_*` (client_credentials) |
| POA client | `mfdptnr_platizio_test_*` |
| FP base URL (configured) | `https://s.finprim.com` |
| POA base URL | `https://api.sandbox.cybrilla.com` |
| Callback base (local demo) | `http://localhost:5173` |

**Please confirm:** All sandbox API and token traffic for our tenant should use **`s.finprim.com`** only, and **`api.fintechprimitives.com`** is production-only for our realm.

## 2. Connectivity issues observed

We see **intermittent** failures, not consistent 401/403 auth errors:

| # | Symptom | When it occurs | Endpoint |
|---|---------|----------------|----------|
| 1 | `HTTP connect timed out` | Backend startup, scheduled token refresh, user-triggered KYC/bank flows | `POST .../v2/auth/platizio/token` |
| 2 | `{"error":"Realm does not exist"}` | When our process incorrectly called production host with sandbox credentials | `https://api.fintechprimitives.com/v2/auth/platizio/token` |
| 3 | Downstream 502 / “provider unavailable” | After token timeout; investor profile sync fails | FP profile / BAV APIs on `s.finprim.com` |
| 4 | Bank verification backoff | After failed profile sync | Pre-verification / BAV refresh |

**Recent probe (same machine):**
- `s.finprim.com` token endpoint: TCP connect ~70ms, HTTP 401 (expected with dummy creds) — **reachable**
- `api.fintechprimitives.com` token endpoint: TCP connect ~28ms, HTTP 404 “Realm does not exist” — **reachable but wrong realm for our sandbox creds**

So outages appear as **Java HTTP connect timeout** to the token URL, not DNS failure. We have cleared our local token cache and aligned `.env` to sandbox URLs; we need confirmation we are not missing a Cybrilla-side allowlist, maintenance window, or regional routing issue.

## 3. Demo flows we are testing

We use your documented sandbox simulators:

**KYC readiness (FP gateway PAN):**
- `XXXPX3751X` → KYC compliant (e.g. `AAAPA3751A`)
- `XXXPX3753X` → **kyc_unavailable** (e.g. `BBBPB3753B`) — our primary demo path

**POA PAN validation:**
- `XXXPXNNNNX` valid | `XXXPINNNNX` invalid | `XXXPANNNNX` aadhaar_not_linked
- Name `Lord Voldemort` → mismatch | DOB `2000-01-01` → mismatch

**Bank (last 4 digits):** `1193` pass, `1515` low confidence fail, `1285`/`1600`/`3157` per FP BAV docs

**Orders:** amount ending `0` success, `1` failure; ABSL / Ipru schemes

## 4. Specific requests

1. Confirm correct **token URLs** for our sandbox POA and FP clients (full URLs please).
2. Advise on **timeouts and retries** you recommend for server-side integrations from India.
3. Whether **IP allowlisting** is required for sandbox.
4. Any **scheduled maintenance** on `s.finprim.com` or `api.sandbox.cybrilla.com` this week.
5. For Digilocker/eSign in sandbox during a live demo: recommended **simulate** path if redirect cannot be completed in-room.
6. Production go-live: remaining checklist items for moving from `*_test_*` to production credentials.

## 5. Attachments / logs we can share

- Application logs with timestamps for connect timeouts
- `GET /api/v1/cybrilla/integration-info` response (no secrets)
- Our test matrix CSV (`sandbox-test-matrix-full.csv`)

We would appreciate a short call or written confirmation before our demo date so we can run **Path B** (kyc_unavailable → full KYC → bank → purchase) reliably.

Thank you,

[Your name]  
Platizio Wealthtech  
[Email] | [Phone]
