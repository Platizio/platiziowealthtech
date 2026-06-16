# Cybrilla connectivity incident log

**Tenant:** Platizio (sandbox `*_test_*` credentials)  
**Region:** India (developer machine, Windows)  
**Purpose:** Evidence for Cybrilla support — intermittent failures when calling FP/Cybrilla APIs.

---

## Summary

| Symptom | Frequency | Likely cause |
|---------|-----------|--------------|
| `HTTP connect timed out` on token URL | Intermittent | Stale JVM env pointing at production; network/ISP; no retry on connect timeout |
| `Realm does not exist` (404) | When hitting production URL with sandbox creds | Wrong base URL (`api.fintechprimitives.com` vs `s.finprim.com`) |
| Profile sync fails → bank BAV backs off | After token/connect failures | Downstream effect of auth/connectivity |
| `given pan is not valid for individual investor` | Demo data error | Non-simulator PAN used against FP tenant rules |
| `name is already set and cannot be modified` | Re-sync on existing FP profile | Idempotent update attempted on immutable field |

---

## Issue timeline (fill timestamps when reproducing)

| # | When (IST) | Trigger | Endpoint / host | Error | Recovery |
|---|------------|---------|-----------------|-------|----------|
| 1 | _e.g. 2026-06-08 10:15_ | Backend startup / scheduler | `POST https://api.fintechprimitives.com/v2/auth/platizio/token` | HTTP connect timed out | Restart with `s.finprim.com` in `.env` |
| 2 | _e.g. 2026-06-08 10:20_ | `curl` test with sandbox client_id | `POST https://api.fintechprimitives.com/v2/auth/platizio/token` | `{"error":"Realm does not exist"}` | Use sandbox URL only |
| 3 | _e.g. 2026-06-08 11:00_ | Investor onboarding KYC check | POA `api.sandbox.cybrilla.com` | Timeout / 502 mapped | Retry after token refresh |
| 4 | _e.g. 2026-06-08 11:30_ | Bank account refresh | FP `s.finprim.com` BAV | Backoff — profile not synced | Fix token first |
| 5 | _2026-06-08 (today)_ | `curl` connectivity probe | `s.finprim.com` | 401 in ~70ms connect | **OK** — host reachable |
| 6 | _2026-06-08 (today)_ | `curl` connectivity probe | `api.fintechprimitives.com` | 404 in ~28ms connect | **OK** — host reachable but wrong realm |

---

## Configuration state (expected for sandbox)

```env
CYBRILLA_ENVIRONMENT=sandbox
FINPRIM_BASE_URL=https://s.finprim.com
CYBRILLA_PRE_VERIFICATION_BASE_URL=https://api.sandbox.cybrilla.com
CYBRILLA_PRE_VERIFICATION_TOKEN_URL=https://s.finprim.com/v2/auth/cybrillarta/token
```

**Token audiences:**
- POA pre-verification: `cybrillarta` client on `s.finprim.com`
- FP tenant APIs: `platizio` client on `s.finprim.com`

---

## Diagnostic commands (run during outage)

```powershell
# TCP + HTTP latency
curl.exe -s -o NUL -w "s.finprim: %{http_code} connect=%{time_connect}s\n" --connect-timeout 10 -m 15 -X POST "https://s.finprim.com/v2/auth/platizio/token" -H "Content-Type: application/x-www-form-urlencoded" -d "grant_type=client_credentials&client_id=TEST&client_secret=TEST"

curl.exe -s -o NUL -w "api.fintechprimitives: %{http_code} connect=%{time_connect}s\n" --connect-timeout 10 -m 15 -X POST "https://api.fintechprimitives.com/v2/auth/platizio/token" -H "Content-Type: application/x-www-form-urlencoded" -d "grant_type=client_credentials&client_id=TEST&client_secret=TEST"

# Clear stale cached tokens after .env change
Remove-Item "$env:USERPROFILE\.platizio-wealthtech\external-auth-token-cache.json" -ErrorAction SilentlyContinue
```

---

## Questions for Cybrilla

1. Is `api.fintechprimitives.com` ever required for **sandbox** `platizio_test_*` tenants, or must all traffic use `s.finprim.com`?
2. Are there known maintenance windows or rate limits on sandbox token endpoints?
3. Recommended connect/read timeouts and retry policy for Java `HttpClient` from India?
4. Should we whitelist IPs for sandbox, or is access open globally?
5. For `HTTP connect timed out` (not 401/403), is this usually client network or provider-side?

---

## Actions taken on Platizio side

- Dual OAuth token service with file cache and 401 retry
- `GET /api/v1/cybrilla/integration-info` for environment verification
- Sandbox PAN pattern enforcement flag
- Mapped 502 for upstream timeouts in API responses
- Documentation: credentials report, support email draft, this incident log
