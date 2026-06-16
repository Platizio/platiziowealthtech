# Platizio Project Map (Achilles)

## Paths

```
Backend:  C:\Users\HP\Downloads\platiziowealthtech-Back_end\platiziowealthtech-Back_end
Frontend: C:\Users\HP\Downloads\platiziowealthtech-Front_End for now\platiziowealthtech-Front_End_sample
```

## Runtime

| Setting | Value |
|---------|-------|
| Backend port | `8081` (`SERVER_PORT` in `.env`) |
| API base (frontend) | `http://localhost:8081/api/v1` |
| DB | PostgreSQL `localhost:5432/postgres` |
| Active profile | `local` (default) |

## Backend Layout

```
src/main/java/com/platizio/wealthtech/
  controller/     REST endpoints
  service/        Business logic
  repository/     JPA
  domain/         Entities
  dto/            API contracts
  integration/    RealCybrillaClient, auth
  config/         Security, RestClient
src/main/resources/
  application.yml
  db/migration/   Flyway V1..V*
.env              Secrets and Cybrilla URLs (never commit production secrets)
start-backend.ps1 Safe start — skips if 8081 already has Platizio
```

## Notable REST Surfaces

| Flow | Endpoints |
|------|-----------|
| KYC orchestration | `GET/POST .../investors/{id}/kyc-flow/status`, `.../kyc-flow/advance` |
| POA pre-verification | `POST .../investors/{id}/kyc-checks` |
| KYC application | `POST .../investors/{id}/kyc-requests` |
| Aadhaar / eSign | `POST .../identity-documents`, `.../esign/start` |
| Products (live Cybrilla) | `GET .../products/schemes` (default live; `?local=true` for DB) |
| Direct Cybrilla passthrough | `GET/POST .../cybrilla/...` |

## Frontend Layout

```
src/
  config/api.ts           apiFetch, API_BASE_URL
  views/                  Pages (InvestorOnboarding, ProductMgmt, Ledger, ...)
  components/             KycFlowPanel, ...
  utils/                  kycFlow.ts, kycPreVerification.ts
```

## Cybrilla Skill Chain

1. **achilles** — full-stack delivery, this skill
2. **cybrilla-boss** — Cybrilla API authority (`.cursor/agents/cybrilla-boss.md`)
3. **backend-expert / frontend-expert** — domain specialists under cybrilla-boss

## Page build (fast)

- Playbook: [page-build-playbook.md](page-build-playbook.md)
- Page → API map: [../../cybrilla-boss/references/page-cybrilla-map.md](../../cybrilla-boss/references/page-cybrilla-map.md)
- Invoke: `@.cursor/agents/achilles.md @.cursor/agents/cybrilla-boss.md build [PAGE]`
