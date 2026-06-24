# Platizio Wealthtech — Phase 2 Plan (Transaction 2FA Engine + Withdrawal)

> Governed by SRS `PLZ-SRS-INV-COMP-001 v1.0`. Builds on Phase 1 (investor portal, done 2026-06-22).
> Derived from a 5-reader code+SRS understanding pass (2026-06-22). Latest migration on disk is **V58** (no V59 was ever committed). Phase 2 uses **V59** (`add_transaction_approval_challenges`) and **V60** (`add_email_otp_reference_id`).

## Locked decisions (user, 2026-06-22)
1. **Legacy un-authenticated investor-action token-confirm is DISABLED from mutating the provider.** The secure link now deep-links the investor to log in and approve in the portal Approval Center (2FA everywhere). `confirmPurchase(token)` / mandate-via-token hard-fail unless a `CONSUMED` challenge exists.
2. **Self-withdrawal executes a REAL Cybrilla redemption** once 2FA + consent pass (true self-service via the gated `submitRedemptionToProvider`). The separate "request to distributor" button remains a non-2FA alternative.
3. *(defaulted)* OTP channel = the investor's **verified email**; mobile dormant. **Multiple concurrent approval challenges per account are allowed** (the Approval Center is a list). Each approval OTP is **bound to its challenge** via `email_otps.reference_id` (V60) — `OtpService.requestOtp/verify` gained reference-scoped overloads so a sibling challenge's code can never be cross-consumed. *(This supersedes the original "one live challenge per account" idea, which the adversarial engine review showed was both unimplemented and architecturally wrong for a list-based Approval Center.)*
4. *(defaulted)* Investor-scoped holdings via a new `PortfolioService.getInvestorHoldings(investorId)`; surface `dataQuality=STALE/UNAVAILABLE` rather than blocking (FR-HLD-005). Never fall back to order amount.
5. *(defaulted)* Withdrawal supports amount **and** units, neither preselected; full-redemption explicit. Audit context (ip/ua/channel/challengeId) packed into `AuditService` `detailsJson` (no audit-table migration).
6. **Withdrawal compliance warning** = a placeholder constant the user will supply later (like the Phase-1 T&C text); the *rendered* text is stored in the challenge as immutable evidence.

## Gate principle
No Cybrilla/FP write fires unless a `TransactionApprovalChallenge` for that exact transaction is `CONSUMED` **and** its `snapshotSha256` still equals a freshly-recomputed snapshot hash. One guard: `transactionApprovalService.assertConsumedFor(transactionId, recomputedHash)` (mirrors Phase-1 `OnboardingSubmissionService.assertFinalizable`), planted immediately before each provider write.

- **Gate A (riskiest)** — `InvestorActionService` lumpsum branch, before `cybrillaClient.updateMfPurchaseConsent(...)` (~line 532). Also retires the legacy token-confirm path (decision #1).
- **Gate B** — `InvestorActionService.startSipMandateAuthorization()`, before `cybrillaClient.createMandate(...)` (~line 270).
- **Gate C** — `OrderService.createRedemption(...)` split into `createRedemptionDraft(...)` (status `PENDING_INVESTOR_ACTION`, no provider call) + `submitRedemptionToProvider(redemptionId)` (asserts consumed, then `cybrillaClient.createRedemption`, before ~line 648).
- **Not gated:** `OrderService.createOrder` line ~391 (lumpsum draft creates FP order in `created/pending`, no money moves; SIP create is deferred). Rely on A/B/C.

## Data model — Flyway V59 + V60
`V59__add_transaction_approval_challenges.sql`: `transaction_approval_challenges` (id, timestamps, transaction_id, transaction_type PURCHASE|SIP|REDEMPTION, investor_id, investor_account_id, status, snapshot_json, snapshot_sha256, consent_template_version, consent_rendered_text, consent_record_id FK→consent_records, channel EMAIL|MOBILE, masked_destination, otp_purpose, expires_at, approved_at, consumed_at, superseded_by, ip_address, user_agent, session_id, correlation_id, delivery_attempts). Partial-unique index: at most one live (`PENDING|CHALLENGE_SENT|APPROVED`) challenge per transaction.

Status machine `TransactionApprovalStatus`: `PENDING → CHALLENGE_SENT → APPROVED → CONSUMED`; `PENDING|CHALLENGE_SENT → EXPIRED|REJECTED|SUPERSEDED` (terminal-fail; only a new challenge re-authorizes). `CONSUMED` authorizes exactly one provider action.

- `V60__add_email_otp_reference_id.sql`: nullable `reference_id uuid` on `email_otps` (+ `(email, purpose, reference_id)` index). Binds an approval OTP to one challenge so concurrent challenges can't cross-consume. `OtpService` gains reference-scoped `requestOtp(email, purpose, refId)` / `verify(email, purpose, code, refId)`; login/signup pass `null` (unchanged).
- Consent rows reuse existing `consent_records` (V57) via `ConsentRecordService.record(...)`, keys `purchase_approval|sip_approval|redemption_approval`; challenge `consent_record_id` links the evidence.
- **No** `payment_attempts` table (payment state already on `transaction_orders` + `audit_events`).
- **No** new `OrderStatus`/`RedemptionStatus` values (`PENDING_INVESTOR_ACTION` reused on both).

## TransactionApprovalService API (pin exactly across all agents)
```
createChallenge(UUID transactionId, TransactionType type, UUID investorAccountId) -> challenge (freezes snapshot+hash, status PENDING)
String renderPurchaseSnapshot/​renderSipSnapshot/​renderRedemptionSnapshot(...)   // LinkedHashMap → stable JSON (mirror OnboardingSubmissionService)
String renderConsentText(challenge)
OtpRequestResponse requestApprovalOtp(UUID challengeId, UUID actorAccountId, boolean isDistributorResend)  // PENDING→CHALLENGE_SENT, delivery_attempts++, never returns code to distributor
TransactionApprovalChallenge approve(UUID challengeId, UUID investorAccountId, String otpCode, boolean consentAccepted, String ip, String ua, String sessionId)  // verifies ownership+consent+OTP, records consent, →APPROVED
void assertConsumedFor(UUID transactionId, String recomputedSnapshotSha256)   // throws unless CONSUMED & hash matches
void markConsumed(UUID transactionId)   // APPROVED→CONSUMED at provider success boundary (idempotent)
void supersedeOnEdit(UUID transactionId, UUID distributorActorId)
List<...> listPendingForInvestor(UUID investorAccountId)
```

## Endpoint contract (pin exactly)
Investor (ROLE_INVESTOR, `/api/v1/investor`):
- `GET  /investor/approvals` · `GET /investor/approvals/{id}` · `POST /investor/approvals/{id}/otp/request` · `POST /investor/approvals/{id}/approve {consentAccepted, code}`
- `GET  /investor/holdings`
- `POST /investor/withdrawals/request-to-distributor {orderId|folio, mode, value}` (no 2FA, draft only)
- `POST /investor/withdrawals/self {orderId|folio, mode, value}` → returns `{challengeId}` → drives the same `/approvals/{id}` 2FA flow → on CONSUMED, real redemption submit
Distributor (`/api/v1/orders`):
- `POST /orders/{id}/request-investor-approval` (createChallenge; never returns OTP)
- `POST /orders/{id}/resend-approval-link` (requestApprovalOtp isDistributorResend=true; never returns OTP)

## Build order (each step green before the next)
1. **Engine** — V60 migration, `TransactionApprovalChallenge`/`TransactionApprovalStatus`/repo, `OtpPurpose.TRANSACTION_APPROVAL` + email-body copy, `TransactionApprovalService` + full unit tests (happy/bad-OTP/expired/consent-not-accepted/ownership-mismatch/supersede/assertConsumedFor pass+fail+mismatch). Keystone.
2. **Gates** — wire A/B in `InvestorActionService` (+ disable legacy token-confirm), C in `OrderService` (split redemption). Update the 2 `InvestorActionServiceTest` + 4 `OrderServiceTest` constructor sites + rewrite the redemption test.
3. **Endpoints + security** — distributor request/resend on `OrderController`; investor approvals + holdings + withdrawals on `InvestorPortalController`; `InvestorAccountRepository.findByInvestorId`; `PortfolioService.getInvestorHoldings`; FR-2FA-007 distributor-403-on-approve test.
4. **Frontend** — `TransactionApprovalPanel` (snapshot + consent checkbox + 6-box OTP + approve), `InvestorApprovalCenter`, `InvestorWithdrawal` (FR-RED disclosures + warning placeholder), distributor request/resend buttons (never show OTP). Reuse Phase-1 patterns + navy theme.

## Verification
Full unit suite green (Phase 1 ended at 321; count grows). FE `tsc` + `vite build` clean. Live demo-profile boot E2E: distributor creates purchase → submit-for-approval → secure link does NOT confirm without OTP (Gate A hard-fail) → investor approves in Approval Center (real email OTP) → assert Cybrilla call fires only after CONSUMED; repeat for SIP (mandate) + self-withdraw (redemption); assert distributor 403 on approve + no OTP in resend response + edit-after-PENDING supersedes + expired blocks the provider call.
