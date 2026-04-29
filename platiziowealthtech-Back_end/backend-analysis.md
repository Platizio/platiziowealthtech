# Backend analysis from the PRD

## 1. Core interpretation

This PRD is a workflow-heavy backend. The most important job is not rendering screens; it is managing state transitions cleanly.

## 2. Main bounded modules

1. Distributor lifecycle
2. Investor lifecycle
3. KYC orchestration
4. Bank verification orchestration
5. Product catalog sync
6. Transaction orchestration
7. Redemption orchestration
8. Portfolio reporting
9. Notifications
10. Lead lifecycle and interaction log
11. Audit trail

## 3. Most important backend rule

The frontend should never decide whether an investor is eligible to transact. The backend should decide this from:
- distributor status
- investor onboarding state
- KYC status
- bank verification status

## 4. Important PRD-to-backend mappings

- No investor panel -> investor actions will happen through external links and webhooks
- No admin panel -> approval still exists, so internal secure endpoints are needed
- One API stack only -> all external wealth operations should be routed through one integration adapter
- Minimal operational complexity -> simple state machines, not over-engineered workflow engines

## 5. Recommended technical approach

- Spring Boot + PostgreSQL
- JPA for MVP speed
- Flyway for schema control
- Service layer for workflow rules
- Integration adapter for Cybrilla
- Webhook endpoints in next step
- Audit event table for every material action

## 6. Suggested phase breakdown

### Phase A
- distributor signup
- distributor approval
- investor creation
- KYC and bank status tracking

### Phase B
- product sync
- order creation
- order status updates
- notifications

### Phase C
- redemption flow
- lead tracking
- reporting placeholders
- webhook ingestion

## 7. Biggest gaps still to be implemented

- authentication and role model
- Cybrilla real API integration
- webhook security and idempotency
- document upload storage
- earnings engine
- portfolio reporting engine
- retry handling for external failures
