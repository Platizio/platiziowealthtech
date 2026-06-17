# Deploying the Platizio WealthTech Backend to Render (Docker)

| | |
|---|---|
| **Service URL** | https://platiziowealthtechh.onrender.com |
| **Service ID** | `srv-d8p5tdi8qa3s73binh50` |
| **Repo / branch** | `Platizio/platiziowealthtech` → `Back_end` |
| **Root Directory** | `platiziowealthtech-Back_end` |
| **Build** | Docker (`./Dockerfile`) |
| **Health check** | `/actuator/health` |
| **Runtime port** | injected by Render via `PORT` (do **not** set it) |

The backend is a Spring Boot 3.3.5 app (Java 21) using **PostgreSQL + Flyway**
(`ddl-auto=validate`). It therefore needs a real Postgres database; Flyway runs
the migrations on first startup.

---

## 1. Render service settings

On the service (**Settings** tab) confirm:

- **Language / Runtime:** Docker
- **Root Directory:** `platiziowealthtech-Back_end`
- **Dockerfile Path:** `./Dockerfile` (relative to Root Directory)
- **Branch:** `Back_end`
- **Health Check Path:** `/actuator/health`

The Dockerfile already binds the app to Render's `PORT`, so no port config is
needed. `EXPOSE 8081` is informational only.

## 2. Create the database first

Create a **Render PostgreSQL** instance (same region as the web service).
After it provisions, open its **Info** page and copy the **Internal Database URL**:

```
postgresql://USER:PASSWORD@dpg-xxxxxxxx-a:5432/DBNAME
```

Translate it into the three variables the app reads:

| App variable  | Value from the internal URL |
|---------------|-----------------------------|
| `DB_URL`      | `jdbc:postgresql://dpg-xxxxxxxx-a:5432/DBNAME` (add the `jdbc:` prefix, drop the `USER:PASSWORD@`) |
| `DB_USERNAME` | `USER` |
| `DB_PASSWORD` | `PASSWORD` |

## 3. Set environment variables

The backend reads **every** variable below. Set them on the service's
**Environment** tab. Render lets you paste many at once via
**Add Environment Variable → Add from .env** — use the copy-paste block in §5.

> **Critical — the deploy will misbehave without these:**
> - `SPRING_PROFILES_ACTIVE=production` — anything other than `local`. The
>   `local` profile enables demo data seeders, weak default secrets, returns OTP
>   codes in API responses, and **un-disables Flyway clean**. Never run `local` in the cloud.
> - `DB_PASSWORD`, `JWT_SECRET`, `AUTH_COOKIE_SECURE` — these have **no default**
>   in the production profile; the app **fails to start** if any is missing.
> - `AUTH_COOKIE_SECURE=true` + `AUTH_COOKIE_SAME_SITE=None` — required for the
>   browser to keep the auth cookie across the FE↔BE origins over HTTPS.
> - `CORS_ALLOWED_ORIGINS` — must contain the deployed frontend origin or the
>   browser blocks every API call.

### Required (app will not start / core auth fails without them)

| Variable | Value | Notes |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `production` | Must NOT be `local`. |
| `DB_URL` | `jdbc:postgresql://<host>:5432/<db>` | From the Render Postgres internal URL (§2). |
| `DB_USERNAME` | `<user>` | From Render Postgres. |
| `DB_PASSWORD` | `<password>` | **Secret.** From Render Postgres. No default. |
| `JWT_SECRET` | `<48+ random bytes>` | **Secret.** `openssl rand -base64 48`. No default. |
| `AUTH_COOKIE_SECURE` | `true` | No default in prod profile. |
| `AUTH_COOKIE_SAME_SITE` | `None` | Cross-site cookie between FE and BE. |
| `CORS_ALLOWED_ORIGINS` | `https://<FRONTEND_URL>` | Comma-separated; no trailing slash. |

### Required for the Cybrilla / Finprim integration (sandbox)

| Variable | Value | Notes |
|---|---|---|
| `CYBRILLA_ENVIRONMENT` | `sandbox` | |
| `CYBRILLA_REAL_CLIENT_ENABLED` | `true` | |
| `CYBRILLA_PRE_VERIFICATION_BASE_URL` | `https://api.sandbox.cybrilla.com` | |
| `CYBRILLA_PRE_VERIFICATION_TOKEN_URL` | `https://s.finprim.com/v2/auth/cybrillarta/token` | |
| `CYBRILLA_PRE_VERIFICATION_CLIENT_ID` | `<from .env>` | **Secret.** |
| `CYBRILLA_PRE_VERIFICATION_CLIENT_SECRET` | `<from .env>` | **Secret.** |
| `CYBRILLA_TOKEN_REFRESH_BUFFER_SECONDS` | `120` | |
| `CYBRILLA_WEBHOOK_SECRET` | `<your secret>` | **Secret.** Required outside `local` for webhooks (KYC/order status) to be accepted; otherwise webhook calls return 403. Boot still succeeds without it. |
| `CYBRILLA_PRODUCT_CATALOGUE_SOURCE` | `cybrilla` | |
| `CYBRILLA_PRODUCT_CATALOGUE_ENDPOINT` | `poa-mf` | |
| `CYBRILLA_KYC_FORM_CALLBACK_BASE_URL` | `https://<FRONTEND_URL>` | Investor returns here after Digilocker/eSign. |
| `CYBRILLA_IDENTITY_DOCUMENT_POSTBACK_PATH` | `/distributor/investor-onboarding` | |
| `CYBRILLA_ESIGN_POSTBACK_PATH` | `/distributor/investor-onboarding` | |
| `FINPRIM_BASE_URL` | `https://s.finprim.com` | |
| `FINPRIM_TENANT_NAME` | `platizio` | |
| `FINPRIM_TENANT_ID` | `platizio` | Sent as `x-tenant-id`. |
| `FINPRIM_TENANT_TOKEN_URL` | `https://s.finprim.com/v2/auth/platizio/token` | |
| `FINPRIM_TENANT_CLIENT_ID` | `<from .env>` | **Secret.** |
| `FINPRIM_TENANT_CLIENT_SECRET` | `<from .env>` | **Secret.** |
| `FINPRIM_TOKEN_REFRESH_BUFFER_SECONDS` | `120` | |
| `PAYMENT_POSTBACK_URL` | `https://platiziowealthtechh.onrender.com/investor-actions/{token}/payment-complete` | Keep the literal `{token}` — it is substituted per order. |

### Recommended (sane prod values; have defaults)

| Variable | Value | Notes |
|---|---|---|
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=70.0` | Keeps the JVM heap inside the 512 MB free plan. |
| `AUTH_COOKIE_NAME` | `access_token` | |
| `AUTH_REFRESH_COOKIE_NAME` | `refresh_token` | |
| `AUTH_REFRESH_TOKEN_EXPIRATION_MS` | `604800000` | 7 days. |
| `EXTERNAL_AUTH_TOKEN_CACHE_ENABLED` | `true` | |
| `EXTERNAL_AUTH_AUTO_REFRESH_ENABLED` | `true` | |
| `EXTERNAL_AUTH_WARM_ON_STARTUP` | `true` | |
| `EXTERNAL_AUTH_AUTO_REFRESH_CHECK_INTERVAL_MS` | `1800000` | |
| `EXTERNAL_AUTH_AUTO_REFRESH_STARTUP_DELAY_MS` | `1800000` | |
| `EXTERNAL_AUTH_DEBUG_ENABLED` | `false` | |
| `EXTERNAL_AUTH_LOG_RAW_TOKENS` | `false` | **Security:** keep `false` (local `.env` has it `true`). |
| `ARN_VALIDATION_REAL_CLIENT_ENABLED` | `false` | Mock ARN validation until a real KYD provider is wired. |
| `KYC_SYNC_ENABLED` | `true` | |
| `KYC_SYNC_INTERVAL_MS` | `180000` | |
| `KYC_FORM_SYNC_ENABLED` | `false` | Webhooks are primary; enable only if webhook delivery is unreliable. |

### Email OTP / SMTP (configure for real login & signup)

In the production profile the OTP is **not** returned in the API response. If
`MAIL_HOST` is blank the code is only written to the server log, so real users
can't log in or sign up. Point these at any SMTP provider (Gmail app-password,
Brevo, Mailtrap, …) to send live emails.

| Variable | Value |
|---|---|
| `MAIL_HOST` | `smtp.gmail.com` (or blank to log-only) |
| `MAIL_PORT` | `587` |
| `MAIL_USERNAME` | `<smtp user>` |
| `MAIL_PASSWORD` | `<smtp password / app password>` (**secret**) |
| `MAIL_SMTP_AUTH` | `true` |
| `MAIL_SMTP_STARTTLS` | `true` |
| `MAIL_FROM` | `no-reply@platizio.local` (or a real verified sender) |
| `MAIL_FROM_NAME` | `Platizio` |
| `OTP_LENGTH` | `6` |
| `OTP_EXPIRATION_MINUTES` | `5` |
| `OTP_MAX_ATTEMPTS` | `5` |
| `OTP_RESEND_COOLDOWN_SECONDS` | `30` |

### Optional fine-tuning (defaults are fine — list for completeness)

`CYBRILLA_ENFORCE_SANDBOX_PAN_PATTERNS` (auto), `CYBRILLA_BOOTSTRAP_CATALOGUE_ON_STARTUP=false`,
`CYBRILLA_CATALOGUE_REFRESH_MIN_INTERVAL_MINUTES=25`, `CYBRILLA_INVESTOR_DATA_SOURCE=cybrilla`,
`CYBRILLA_INVESTOR_SYNC_MIN_INTERVAL_MINUTES=5`, `CYBRILLA_MANDATE_PROVIDER_NAME=CYBRILLAPOA`,
`CYBRILLA_SANDBOX_SIMULATE_MANDATE_APPROVAL=true`, `CYBRILLA_SANDBOX_SIMULATE_PAYMENT=true`,
`CYBRILLA_KYC_FORM_MOCK_FALLBACK=false`, `KYC_SYNC_BATCH_SIZE=50`,
`KYC_SYNC_FAILURE_BACKOFF_MS=900000`, `KYC_PRE_VERIFICATION_POLL_MAX_ATTEMPTS=15`,
`KYC_PRE_VERIFICATION_POLL_INTERVAL_MS=2000`, `KYC_FORM_SYNC_INTERVAL_MS=180000`,
`KYC_FORM_SYNC_BATCH_SIZE=50`, `BANK_SYNC_FAILURE_BACKOFF_MS=900000`,
`AUTH_BLOCKED_TOKEN_PURGE_INTERVAL_MS=3600000`, `AUTH_BLOCKED_TOKEN_PURGE_INITIAL_DELAY_MS` (random),
`EXTERNAL_AUTH_TOKEN_CACHE_FILE` (defaults under `$HOME/.platizio-wealthtech/`),
`ARN_VALIDATION_BASE_URL`, `ARN_VALIDATION_LOOKUP_PATH=/arn/{arn}`, `ARN_VALIDATION_API_KEY`,
`ARN_VALIDATION_SOURCE=AMFI`, `SERVER_PORT` (ignored on Render — `PORT` wins).

> `SERVER_PORT` / `PORT`: Render injects `PORT`; the app reads `${PORT:${SERVER_PORT:8081}}`.
> Do not set either manually.

---

## 4. Deploy & verify

1. Save the env vars → Render redeploys automatically (or **Manual Deploy → Deploy latest commit**).
2. Watch the logs for `Started WealthtechApplication` and Flyway `Successfully applied` migrations.
3. Verify health:
   ```
   curl https://platiziowealthtechh.onrender.com/actuator/health
   ```
   Expect `{"status":"UP"}`. If `DOWN`, the DB connection is the usual cause —
   re-check `DB_URL` (JDBC prefix, internal host) and `DB_PASSWORD`.
4. Once the frontend is live, set `CORS_ALLOWED_ORIGINS`,
   `CYBRILLA_KYC_FORM_CALLBACK_BASE_URL` to its URL and redeploy.

---

## 5. Copy-paste block (Render → Add from .env)

Replace the `<...>` placeholders, paste into Render's bulk env editor. **Do not
commit real secrets** — they live only in Render.

```dotenv
SPRING_PROFILES_ACTIVE=production
JAVA_OPTS=-XX:MaxRAMPercentage=70.0
DB_URL=jdbc:postgresql://<internal-host>:5432/<db>
DB_USERNAME=<db-user>
DB_PASSWORD=<db-password>
JWT_SECRET=<openssl rand -base64 48>
AUTH_COOKIE_SECURE=true
AUTH_COOKIE_SAME_SITE=None
AUTH_COOKIE_NAME=access_token
AUTH_REFRESH_COOKIE_NAME=refresh_token
AUTH_REFRESH_TOKEN_EXPIRATION_MS=604800000
CORS_ALLOWED_ORIGINS=https://<FRONTEND_URL>
CYBRILLA_ENVIRONMENT=sandbox
CYBRILLA_REAL_CLIENT_ENABLED=true
CYBRILLA_PRE_VERIFICATION_BASE_URL=https://api.sandbox.cybrilla.com
CYBRILLA_PRE_VERIFICATION_TOKEN_URL=https://s.finprim.com/v2/auth/cybrillarta/token
CYBRILLA_PRE_VERIFICATION_CLIENT_ID=<from .env>
CYBRILLA_PRE_VERIFICATION_CLIENT_SECRET=<from .env>
CYBRILLA_TOKEN_REFRESH_BUFFER_SECONDS=120
CYBRILLA_WEBHOOK_SECRET=<your-webhook-secret>
CYBRILLA_PRODUCT_CATALOGUE_SOURCE=cybrilla
CYBRILLA_PRODUCT_CATALOGUE_ENDPOINT=poa-mf
CYBRILLA_KYC_FORM_CALLBACK_BASE_URL=https://<FRONTEND_URL>
CYBRILLA_IDENTITY_DOCUMENT_POSTBACK_PATH=/distributor/investor-onboarding
CYBRILLA_ESIGN_POSTBACK_PATH=/distributor/investor-onboarding
FINPRIM_BASE_URL=https://s.finprim.com
FINPRIM_TENANT_NAME=platizio
FINPRIM_TENANT_ID=platizio
FINPRIM_TENANT_TOKEN_URL=https://s.finprim.com/v2/auth/platizio/token
FINPRIM_TENANT_CLIENT_ID=<from .env>
FINPRIM_TENANT_CLIENT_SECRET=<from .env>
FINPRIM_TOKEN_REFRESH_BUFFER_SECONDS=120
EXTERNAL_AUTH_TOKEN_CACHE_ENABLED=true
EXTERNAL_AUTH_AUTO_REFRESH_ENABLED=true
EXTERNAL_AUTH_WARM_ON_STARTUP=true
EXTERNAL_AUTH_AUTO_REFRESH_CHECK_INTERVAL_MS=1800000
EXTERNAL_AUTH_AUTO_REFRESH_STARTUP_DELAY_MS=1800000
EXTERNAL_AUTH_DEBUG_ENABLED=false
EXTERNAL_AUTH_LOG_RAW_TOKENS=false
ARN_VALIDATION_REAL_CLIENT_ENABLED=false
PAYMENT_POSTBACK_URL=https://platiziowealthtechh.onrender.com/investor-actions/{token}/payment-complete
KYC_SYNC_ENABLED=true
KYC_SYNC_INTERVAL_MS=180000
KYC_FORM_SYNC_ENABLED=false
MAIL_HOST=
MAIL_PORT=587
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_SMTP_AUTH=true
MAIL_SMTP_STARTTLS=true
MAIL_FROM=no-reply@platizio.local
MAIL_FROM_NAME=Platizio
OTP_LENGTH=6
OTP_EXPIRATION_MINUTES=5
OTP_MAX_ATTEMPTS=5
OTP_RESEND_COOLDOWN_SECONDS=30
```
