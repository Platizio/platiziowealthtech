# Platizio Wealthtech — 11 To-Do Compliance & Feature Audit (`flaws.md`)

> Evidence-based audit of the 11 requested items against the live codebase. Produced by a 26-agent
> workflow: 4 provider-capability research agents (Cybrilla POA + Fintech Primitives docs) → 11 read-only
> code auditors (one per to-do) → 11 **adversarial verifiers** that re-opened every cited file and
> confirmed/refuted each claim. Every flaw below carries a verified `file:line`. Pairs with
> [`context.md`](context.md), [`bugs.md`](bugs.md), [`fix.md`](fix.md).
> Audited: 2026-06-16. Backend root: `Back_end/platiziowealthtech-Back_end/`. Frontend root: `Front_end/`.

## Legend
- **Status:** `BUILT` = complete, correct, end-to-end · `PARTIAL` = exists but incomplete/non-compliant · `NOT BUILT` = absent.
- **~5h feasible:** `Done` · `Yes` (full, prod-grade in ≤5h) · `Partial` (a meaningful MVP in 5h; full scope beyond) · `No`.
- **Severity** is on a **SEBI/AMFI-regulated MF-distribution** lens: a fabricated regulatory declaration or a missing statutory control is Critical, not cosmetic.

---

## 1. Executive summary

| # | To-do | Status | Severity | ~5h feasible | One-line verdict |
|---|-------|--------|----------|--------------|------------------|
| 1 | Email verification via self-declaration | **NOT BUILT** | High | **Yes** | Email is a bare free-text input; no attestation, no persisted declared-flag; FP `belongs_to` hardcoded `"self"`. |
| 2 | Usage check of the pre-verification API | **BUILT** | Low | **Done** | POA `/poa/pre_verifications` is wired end-to-end and unit-tested. Minor parity + one unguarded proxy surface. |
| 3 | Mobile verification via self-declaration | **NOT BUILT** | High | **Yes** | Same as email; plus `mobileNumber` has no format validation (BUG-033) and OTP channel is email-only. |
| 4 | Nomination capability + nominee flow | **NOT BUILT** | **Critical** | **Partial** | A 2-field UI widget that is dropped on submit. No entity, table, FP `related_parties`, allocation, or SEBI opt-out. |
| 5 | 2FA for **Purchase** + consent at OTP entry | **NOT BUILT** | **Critical** | **Partial** | No OTP anywhere; consent auto-fabricated from the stored profile; UI falsely claims "SEBI Compliant". |
| 6 | 2FA for **Redemption** + consent at OTP entry | **NOT BUILT** | **Critical** | **Partial** | Only a client-side `window.confirm`; no consent object even sent to FP; no OTP. |
| 7 | 2FA for **SIP** transactions | **NOT BUILT** | **Critical** | **Partial** | Bank NACH `token_url` is leaned on as "consent" — that is mandate auth, **not** the SEBI per-txn OTP. |
| 8 | Acceptance of Terms & Conditions | **NOT BUILT** | High | **Yes** | Onboarding T&C checkboxes are discarded client-side; nothing persisted; no version/timestamp; placeholder legal text. |
| 9 | Redemption screen: folio / scheme / units | **PARTIAL** | High | **Partial** | Scheme name shown; units are a **non-authoritative local** value; **folio number entirely missing**; no max-redeemable gate. |
| 10 | Holdings calculation method | **PARTIAL** | High | **Partial** | Holdings/AUM summed from **local order amounts**, not the FP/RTA holdings report. No NAV, actual units, or redemption netting. |
| 11 | No auto-populated defaults for all customers | **PARTIAL** | **Critical** | **Yes** | **The answer is currently NO** — ≥7 regulated fields are blanket-defaulted for *every* customer (tax status, nationality, PEP, gender, occupation, income, source of wealth). |

**Headline:** 8 of 11 are not built or non-compliant. The three most dangerous from a regulatory standpoint are
**#11 (fabricated regulatory declarations sent to the RTA), #4 (no nomination/opt-out), and #5–7 (no
two-factor authentication on money movement)** — each is a SEBI/AMFI mandate. None of these can be "demo-waved";
they put real RTA submissions and statutory controls at risk.

**Cross-cutting root cause:** the platform conflates three distinct things that the regulator treats
separately — **(a) FP provider consent** (auto-built on the investor-action page), **(b) distributor 2FA OTP**
(does not exist), and **(c) platform T&C acceptance** (discarded). Building (b) and (c) properly, and feeding
investor-declared values instead of defaults into (a), resolves the bulk of the compliance gap.

---

## 2. Detailed flaws → solutions (per to-do)

Severity per row. `Effort`: S `<1h` · M `1–3h` · L `3–8h` · XL `>8h`.

### To-do 1 — Email verification through self-declaration · NOT BUILT

The provider does **not** verify email (POA pre-verification has no email type), so "self-declaration" =
the investor attests ownership (declared flag + attestation text), persisted and forwarded to FP as
`belongs_to`. None of that exists.

| Sev | Flaw (verified) | Location | Solution | Effort |
|-----|-----------------|----------|----------|--------|
| High | Email is a bare `<input type="email">` with only format validation — no attestation checkbox/declared flag/timestamp | `Front_end/src/views/InvestorOnboarding.tsx:2478` (gate `:760`) | Add an explicit "I confirm this email belongs to the investor / is the investor's" attestation control + ownership selector at capture. | M |
| High | FP `email_addresses.belongs_to` is hardcoded literal `"self"` regardless of who owns the contact | `…/integration/RealCybrillaClient.java:2773` | Thread the captured ownership into `emailPayload.belongs_to` instead of the literal. | S |
| Med | "Contact Ownership" is written only as a free-text token in `onboardingNotes` (`contact_owner=…`), never structured or forwarded — dead data | `InvestorOnboarding.tsx:622` (select `:2915`); no field in `InvestorCreateRequest.java` | Add a first-class `contactOwner`/`emailBelongsTo` field to the DTO and thread it through `InvestorService`. | S |
| High | No persistence: `Investor` has `email` but no `emailDeclared`/`emailDeclaredAt`/`emailBelongsTo`; no audit event | `…/domain/Investor.java:25` (no such columns; only V6 `email_otps`, login-scoped) | Flyway migration adding declaration columns; emit `EMAIL_SELF_DECLARED` audit event with timestamp + actor. | M |

**~5h:** **Yes.** Additive UI + one Flyway migration + DTO/service threading + the `belongs_to` fix.

---

### To-do 2 — Usage check of the pre-verification API · BUILT ✅

The POA pre-verification flow **is wired end-to-end and unit-tested** — the only "BUILT" item. (The first
auditor wrongly called the standalone endpoints "dead code"; the verifier refuted that with the live call
sites and tests.)

**Working evidence:** controllers `InvestorController.java:285/293` (`POST`/`GET /pre-verifications`) →
`InvestorKycService.createPreVerification/fetchPreVerification` (`:113/:122`) → `RealCybrillaClient.java:708-726`
(`POST /poa/pre_verifications`). Used in onboarding at `InvestorKycService.java:186/299/359` and
`InvestorService.java:1170`; unit-tested at `InvestorKycServiceTest.java:51,80`. Three lookup types are
exercised: readiness (`investor_identifier`), PAN/name/DOB validation, and bank BAV.

| Sev | Flaw (verified) | Location | Solution | Effort |
|-----|-----------------|----------|----------|--------|
| Low | The standalone payload omits `investor_identifier` that the onboarding payload sends (cosmetic — the value is just the PAN already present) | `InvestorKycService.java:1381-1390` vs `RealCybrillaClient.java:2804-2815` | Add `investor_identifier` for parity so the two payloads can't drift. | S |
| Med (security) | A **second, unguarded** surface for the same endpoint accepts a raw arbitrary `Map<String,Object>` and proxies it to the provider with no validation and no role gate | `…/controller/CybrillaDirectController.java:31-41` (`:32-33` raw body) | Restrict `CybrillaDirectController` to `ADMIN` + validate the body (this is **BUG-028**); keep the guarded `InvestorController` path for normal use. | M |

**~5h:** **Done** (working). The two items are optional hardening, ~1h.

---

### To-do 3 — Mobile number verification through self-declaration · NOT BUILT

Mirror of #1 (provider doesn't verify mobile) — plus a validation gap and an email-only OTP channel.

| Sev | Flaw (verified) | Location | Solution | Effort |
|-----|-----------------|----------|----------|--------|
| High | No self-declaration capture for mobile (no declared flag/attestation/timestamp); free-text field only | `InvestorOnboarding.tsx:2476`; `Investor.java:21` (no declared columns) | Same declaration pattern as #1: attestation control + `mobileDeclared`/`mobileDeclaredAt` columns + audit. | M |
| Med | **BUG-033 (broader than logged):** `mobileNumber` is bare `@NotBlank` with no `@Pattern`/`@Size` — on the **create AND update** DTOs and 4 others; no shared `MobileFormat` constant | `InvestorCreateRequest.java:16`, `InvestorUpdateRequest.java:12`, `AuthSignupRequest.java:12`, `DistributorSignupRequest.java:12`, `LeadCreateRequest.java:9`, `DistributorUpdateRequest.java:8` | Create a shared `MobileFormat` (mirroring `PanFormat.java`) and apply `@Pattern("^[6-9]\\d{9}$")` to all mobile DTOs. | S |
| Med | FP `phone_numbers.belongs_to` hardcoded `"self"`; `PhoneParts.from()` always returns ISD `91` → no NRI/international support | `RealCybrillaClient.java:2783`, `:3168-3174` | Thread ownership into `phonePayload.belongs_to`; capture/respect ISD for NRI. | M |
| Med | OTP infra is **email-only** — no SMS channel exists, so a mobile OTP cannot be delivered today | `OtpService.java:164-167` (`deliver()`→`emailService.sendHtml`); `OtpPurpose` = `{LOGIN,SIGNUP}` | Add an SMS provider to `OtpService.deliver()` (needed for #5–7 too). | M |

**~5h:** **Yes** for declaration + BUG-033. (Real mobile *OTP* delivery depends on adding the SMS channel.)

---

### To-do 4 — Nomination capability + nominee addition flow · NOT BUILT · **Critical**

SEBI mandates that every MF folio either declares nominees **or** records an explicit opt-out. FP supports this
via `related_parties` + `folio_defaults` (see Appendix). The app has none of it — only a vestigial widget.

| Sev | Flaw (verified) | Location | Solution | Effort |
|-----|-----------------|----------|----------|--------|
| Critical | Nominee captured in the UI (name + relationship only) is **dropped on submit** — never in the investor payload | `InvestorOnboarding.tsx:301` (`useState{name,relation}`), `:2944-2952`, omitted from `buildInvestorPayload` `:602-627` | Build the full nominee model end-to-end (below). | — |
| Critical | **No backend persistence** — no `Nominee`/`RelatedParty` entity, no table/migration, no controller/service/repo (0 grep hits across `src/main`) | backend-wide | Add `Nominee` entity + Flyway table (name, relationship, DOB, PAN/ID proof, `allocationPercentage`, guardian\_\* for minors, `optOut`, `optOutDeclaredAt`); DTO; controller/service. | L |
| Critical | FP `folio_defaults` is written with only 4 communication/payout keys — `related_parties` and `nomineeN`/`nomineeN_allocation_percentage` never sent | `RealCybrillaClient.java:397-404` | `POST /v2/related_parties` → store ids → `PATCH /v2/mf_investment_accounts` setting `folio_defaults.nominee1/2/3` + `nomineeN_allocation_percentage`. | L |
| High | No SEBI **opt-out** flow — the section is literally tagged "(optional)" and skippable; no opt-out declaration / video consent | `InvestorOnboarding.tsx:2931` | Replace optional-collapse with a required choice: *declare nominee(s)* or *opt out* (capture opt-out declaration; FP's revised-nomination change references video consent). | M |
| High | Captured fields radically incomplete (no DOB, PAN, %allocation, minor guardian, contact); relationship labels don't map to FP's enum | `InvestorOnboarding.tsx:2944-2952` (no `value` attrs) | Capture the full set; validate allocations sum to 100% and ≤3 nominees (account level); map relationship to FP values. | M |
| Med | No audit trail for nomination/opt-out despite the existing `AuditEvent` pattern | `…/domain/AuditEvent.java` exists; nothing emits for nomination | Emit `NOMINATION_DECLARED` / `NOMINATION_OPTED_OUT`. | S |

**~5h:** **Partial.** An MVP (entity + migration + opt-out-or-declare UI + capture + persistence + audit) is
achievable in ~5h. Full prod-grade FP `related_parties` + `folio_defaults` wiring, minor-guardian handling, and
allocation validation pushes the total to ~L–XL.

---

### To-do 5 — 2FA for Purchase orders, consent at OTP entry · NOT BUILT · **Critical**

SEBI 2FA (circular `SEBI/HO/IMD/IMD-IDOF1/P/CIR/2022/132`, effective 1 Apr 2023): an OTP must be sent to the
folio-registered email/phone and verified **before** the order is created; consent evidence must be stored. FP
provides the `consent` object but **does not** generate/send/verify the OTP — that is app-side. Today there is
no OTP at all.

| Sev | Flaw (verified) | Location | Solution | Effort |
|-----|-----------------|----------|----------|--------|
| Critical | Purchase reaches consent→confirm→payment with **zero OTP**; create gates only KYC + bank | `OrderService.java:343,346`; `InvestorActionService.submitPurchaseForPayment:511`, consent `:532` | Insert an OTP-verify gate before consent/confirm (engine below). | L |
| Critical | FP consent is auto-populated from stored profile data — no investor action, no consent declaration shown at OTP entry | `InvestorActionService.consentPayload:826-839` | Build the consent object only **after** the investor passes OTP; show the consent declaration text on the OTP screen. | M |
| Critical | UI advertises "SEBI Compliant" and "the investor's signed consent" while neither 2FA nor a signed-consent record exists — **affirmative misstatement** | `Front_end/src/views/InvestorTransaction.tsx:540`, `:922-924` | Remove/replace the claim until the real control ships; then show the actual consent + OTP-verified state. | S |
| High | `OtpPurpose` cannot represent a transaction OTP; OTP engine keyed by `(email,purpose)` only — **cannot bind a challenge to an order** | `OtpPurpose.java:8`; `EmailOtpRepository.java:16` | Add `OtpPurpose.PURCHASE_TXN`; extend `email_otps` with a `subject_id` (order id) column (migration) so a code binds to one order. | M |
| High | No persistence/audit of transaction consent + OTP evidence (no `TransactionConsent` entity; logs only `INVESTOR_ACTION_CONFIRMED`) | `InvestorActionService.java:118-124` | New `TransactionConsent` (orderId, contact, `consentTextVersion`, `otpVerifiedAt`, ip, channel); persist on verify; audit. | M |
| High | OTP is email-only; SEBI says send to the folio-registered email **or phone** | `OtpService.java:164-167` | Add SMS channel + resolve the **folio-registered** contact (FP `mf_folios`), not the profile contact. | M |

**Reusable asset:** `OtpService` is already a production-grade hashed-OTP engine (SHA-256, 5-min expiry,
max-attempts burn, resend cooldown, single-live-code) — it only needs a transaction purpose, order binding, and
an SMS channel. **~5h:** **Partial** — a complete purchase 2FA (engine + order binding + consent persistence +
OTP-entry screen) is achievable in ~5h; extending to redemption/SIP and SMS/folio-contact resolution is more.

---

### To-do 6 — 2FA for Redemption orders, consent at OTP entry · NOT BUILT · **Critical**

| Sev | Flaw (verified) | Location | Solution | Effort |
|-----|-----------------|----------|----------|--------|
| Critical | Redemption submits with **no OTP and no consent** — only a client-side `window.confirm` (trivially bypassed via direct API call) | `InvestorRedeem.tsx:121,128`; `OrderService.createRedemption:631` (ownership check only `:663-667`) | Same OTP-verify gate as #5 before `createRedemption`. | M |
| Critical | **No `consent` object sent to FP** on redemption (purchases send one; redemptions don't) | `RealCybrillaClient.redemptionPayload:2936-2945` | Add the `consent` object to `redemptionPayload`, populated post-OTP. | S |
| High | `RedemptionRecord` stores no consent/OTP fields (no `consentEmail`/`otpVerifiedAt`/`consentIp`) | `…/domain/RedemptionRecord.java:10-26` | Add consent/OTP audit columns; persist on submit. | M |
| Med | Redemption units copied verbatim from the order and never re-validated against FP holdings (overlaps #9) | `OrderService.java:646-647`; `RealCybrillaClient.java:2942` | Gate on FP `redeemable_units` (see #9). | M |

**~5h:** **Partial** — reuses the #5 engine; full coverage (consent-to-FP + audit columns + holdings gate)
fits if #5's engine already exists.

---

### To-do 7 — 2FA for SIP transactions · NOT BUILT · **Critical**

The dangerous misconception here: the **NACH mandate `token_url`** (bank OTP/secure-login) is being treated as
the investor's consent. That is **mandate authorization**, not the SEBI per-transaction 2FA, and does not
substitute for it.

| Sev | Flaw (verified) | Location | Solution | Effort |
|-----|-----------------|----------|----------|--------|
| Critical | SIP plan PATCHed to `confirmed` with a `consent` object **fabricated** from stored profile email/mobile and zero OTP | `InvestorActionService.confirmSipMandatePurchase:256` → `submitSipPlanAfterApprovedMandate:369` (PATCH `:384-387`), `consentPayload:826` | OTP-verify gate before plan confirm; build consent post-OTP. | M |
| High | Bank NACH `token_url` leaned on as the investor factor — it is mandate auth, not SEBI 2FA | `InvestorActionService.authorizeMandate:284-291` | Keep mandate auth **and** add the separate SEBI per-txn OTP; don't conflate them. | M |
| High | Server-rendered HTML tells the investor "Platizio will collect consent" — but no consent/OTP is collected for SIP | `…/controller/InvestorActionController.java:445` | Correct the copy; collect real consent + OTP. | S |
| High | No consent/OTP audit evidence for SIP; OTP can't bind to a plan/order (engine keyed by email+purpose) | `InvestorActionService.java:293-299,395-402`; `EmailOtpRepository.java:16` | Reuse #5's `TransactionConsent` + order/plan binding. | M |
| Med | Sandbox `simulateSandboxMandateApproval` can drive a plan to APPROVED + submit, bypassing even the bank factor | `InvestorActionService.java:221-246` | Ensure simulate paths are hard-disabled outside sandbox and never skip the SEBI OTP. | S |

**~5h:** **Partial** — reuses the shared engine; SIP's extra mandate interplay makes full coverage tighter.

> **Recommendation for #5–7:** build **one** transaction-2FA engine (new `OtpPurpose`, order-bound
> `email_otps`, consent declaration at OTP entry, `TransactionConsent` persistence, SMS + folio-contact
> resolution) and reuse it across purchase, redemption, and SIP. Deliver purchase end-to-end first; redemption
> and SIP then become thin wiring. Treat the existing "SEBI Compliant"/"signed consent" UI copy as a
> must-fix misstatement in the meantime.

---

### To-do 8 — Acceptance of Terms & Conditions · NOT BUILT

| Sev | Flaw (verified) | Location | Solution | Effort |
|-----|-----------------|----------|----------|--------|
| High | T&C checkboxes are **client-side gates only** — their values are never put in any payload, so nothing is persisted | `InvestorOnboarding.tsx:751` (`consentAcknowledged`), omitted from `buildInvestorPayload:602-627`; `Onboarding.tsx:387-390` button gate, `agree*` omitted from `:408-421` | Add consent fields to the DTOs; persist on submit. | M |
| High | No `TermsAcceptance` entity/table/DTO field, and no `termsVersion`/`acceptedAt` anywhere (0 grep across `src/main` + all migrations) | backend-wide | `TermsAcceptance` entity + migration (`subjectType`, `subjectId`, `documentKey`, `version`, `acceptedAt`, `ip`, `userAgent`); audit `TERMS_ACCEPTED`. | M |
| Med | Transaction UI claims "the investor's signed consent" with no backing record | `InvestorTransaction.tsx:924` | Back the claim with the persisted acceptance (ties to #5–7). | S |
| Low | Distributor agreement bodies are labelled **PLACEHOLDER**; "OTP-based signing / e-sign via Digio" promised "in a future release" | `Onboarding.tsx:164,179,193`, `:1046` | Replace placeholder legal text with counsel-approved content; version it. (Content task.) | M |

**~5h:** **Yes** for the engineering (entity + migration + persist existing checkboxes + version/timestamp +
audit). The legal-text replacement is a content/legal task, not engineering.

---

### To-do 9 — Redemption screen: folio number, scheme name, available units/balance · PARTIAL

What's there vs required: **scheme name ✅** (local catalog, falls back to "Unknown Scheme") · **units ⚠️**
(non-authoritative local value) · **folio number ❌** (entirely absent).

| Sev | Flaw (verified) | Location | Solution | Effort |
|-----|-----------------|----------|----------|--------|
| High | **Folio number is entirely missing** — no column, no DTO/entity field, no `/v2/mf_folios` integration | `InvestorRedeem.tsx:239-247` (headers: Fund/Type/Invested/Units/Purchased/Action); no `folioNumber` in `TransactionOrder.java`/`RedemptionRecord.java`/`PortfolioDto.java:42-62` | Integrate `GET /v2/mf_folios` (folio `number`); add a Folio column + DTO field. | M |
| High | "Available units" is a **non-authoritative local** value (`TransactionOrder.units` set once at creation, never reconciled; `null` for amount-based lumpsum; shipped to FP as-is) | `OrderService.java:360,647`; `RealCybrillaClient.java:2942`; `InvestorRedeem.tsx:97,259` | Source units from FP holdings report (`redeemable_units`); show `as_on`. | M |
| High | No pre-redemption gating — full redemption submitted on `window.confirm` with no max-redeemable check | `InvestorRedeem.tsx:119-128`; `OrderService.java:631-654` | Gate on `GET /api/oms/reports/holdings` `redeemable_units` (and `/v2/mf_redemptions/summary` for instant limits). | M |
| Med | Per-purchase order-row model — each purchase is a separate "full-redeemable" row; no per-scheme/folio consolidation | `InvestorRedeem.tsx:71-103` | Consolidate holdings by scheme+folio from the holdings report. | M |
| Med | `redemptionPayload` sends **both** `amount` and `units` to FP — an ambiguous instruction | `RealCybrillaClient.java:2941-2942` | Send exactly one basis (amount **or** units) per the gateway rules. | S |

**~5h:** **Partial** — displaying folio + authoritative `redeemable_units` is achievable in ~5h **if** the FP
`mf_folios`/holdings endpoints are reachable in sandbox; otherwise the authoritative-units half slips.

---

### To-do 10 — Method used to calculate and display holdings · PARTIAL

**How it works today (verified):** holdings/AUM/"invested value" are computed by **summing
`TransactionOrder.getAmount()`** for `SUCCESSFUL/ACTIVE/COMPLETED` orders with a known scheme. No units, NAV,
market value, gain, or redemption netting is involved, and **no FP/RTA holdings endpoint is called anywhere**.

| Sev | Flaw (verified) | Location | Solution | Effort |
|-----|-----------------|----------|----------|--------|
| High | Holdings/AUM summed from **order amounts**, not the FP/RTA Holdings Report — ignores NAV growth, actual allotted units, settlement/corporate actions, external holdings | `PortfolioService.java:152-178` (no units/NAV refs; 0 grep for any holdings endpoint) | Source holdings from `GET /api/oms/reports/holdings` (units, `redeemable_units`, `market_value`, `invested_value`, `nav`, `as_on`). | L |
| High | `order.units` is app/CSV-supplied and **never reconciled** from provider allotment; sync touches only `state` | `OrderService.java:360`; `syncLumpsumOrderFromProvider:200-232` | Reconcile units/NAV from the holdings report (or provider allotment) after settlement. | M |
| High | Not net of redemptions/switches — no `REDEMPTION`/`SWP`/`SWITCH_OUT` subtraction | `PortfolioService.java:84-182` | Net out redemptions/switches (or rely on the holdings report which already nets). | M |
| Med | DTO **names** mislabel an order-amount sum as `investedValue`/`totalAum` — the misrepresentation is baked into the API contract, not just the UI string ("Live holdings from completed orders") | `PortfolioDto.java:77`; `PortfolioService.java:165-180`; `Portfolio.tsx:169` | Relabel/derive from authoritative figures; surface `as_on`. | S |
| Med | Summary lists silently truncated to top 20/10 (no pagination); non-`ACTIVE` SIP orders dropped entirely | `PortfolioService.java:121-123,190,197` | Paginate (FP holdings caps at 100 folios/response); include settled SIP installments. | M |
| Med | Capital-gains FIFO derives per-unit prices by dividing order amount by app-supplied units — not RTA-confirmed | `CapitalGainsReportService.java:172,202` | Use FP `POST /v2/transactions/reports/capital_gains`. | L |

**~5h:** **Partial** — wiring the holdings report into `PortfolioService` for authoritative display is doable
in ~5h; full reconciliation (capital gains via FP, pagination, NAV history) is beyond.

---

### To-do 11 — No values auto-populated/defaulted for all customers · PARTIAL · **Critical**

**Direct answer: currently NO — the platform blanket-defaults at least seven regulated fields for _every_
customer**, and discards the FATCA declaration the investor actually makes. These defaults are then sent to FP
and on to the RTA, i.e. **fabricated regulatory declarations**. (The `V41–V51`/`DemoDataSeeder` defaults are
correctly scoped to the single "Anita" demo investor — not a blanket default.)

| Sev | Flaw (verified) | Location | Solution | Effort |
|-----|-----------------|----------|----------|--------|
| Critical | FP `tax_status` **always** hardcoded `"resident_individual"`; the investor's FATCA/CRS tax-residency + TIN is **discarded**; `use_default_tax_residences=true` blanket | `RealCybrillaClient.java:2454,2627,2463,2490,2645`; FATCA fields collected at `InvestorOnboarding.tsx:3154` but never serialized (`buildInvestorPayload:617-626`) | Derive `tax_status`/tax residences from the actual FATCA declaration; serialize `taxCountry`/TIN. | M |
| Critical | `nationality_country` and `country_of_birth` hardcoded `"IN"` (in create **and** order-ready PATCH and update); no UI field exists | `RealCybrillaClient.java:2460,2462,2481,2487,2636,2642` | Add nationality / country-of-birth capture; send the declared value. | M |
| High | PEP silently defaults to `"not_applicable"` for any non-yes value; UI defaults `politicalExp='No'` with no neutral/required option | `RealCybrillaClient.java:2731-2737`; `InvestorOnboarding.tsx:320,3164-3166` | Make PEP a required, neutral-default declaration. | S |
| High | Gender→`female`, occupation→`service` (also for **unknown** values), income→`upto_1lakh`, source_of_wealth→`salary` defaulted when blank; **source_of_wealth has no UI field at all** | `RealCybrillaClient.java:2665,2678,2688,2694,2718`; 0 grep for `source_of_wealth` in `Front_end` | Capture each field; **reject** unknown values rather than coercing; remove blank-defaulting. | M |
| High | Frontend blanket defaults `taxResidency='India'`, `politicalExp='No'`, `contactOwner='Self'`, `relationshipType='SELF'`, `accType='Savings'`; payload always serializes `tax_residency` + `pep` even if untouched | `InvestorOnboarding.tsx:317,320,293,247,304,623,625`; gate `canNext` case 6 `:802` only checks `incomeSlab && declared` | Require affirmative selection (tighten `canNext`); don't serialize undeclared fields. | M |
| Med | `income_slab` data-flow bug: the FATCA-step `incomeSlab` is sent under note key `income_slab` but the resolver reads `income` (step 4) → the gated dropdown is a **no-op** | `InvestorOnboarding.tsx:624` vs `RealCybrillaClient.java:2693` | Align the note key so the declared income slab is actually used. | S |
| Med | Distributor signup hard-defaults address `country='India'` and `accountType='Savings'` | `Onboarding.tsx:228,256` | Default-empty + require selection. | S |

**~5h:** **Yes**, but tight — because FP requires these fields non-null, the fix is not just deleting literals;
it is **collecting** the values (add UI fields for source-of-wealth/nationality/country-of-birth, derive
tax-status from FATCA, make PEP/income required). This is the **#1 compliance priority**.

---

## 3. Recommended ~5-hour build plan (prioritized)

Given one ~5-hour block, sequence by regulatory risk × leverage. You cannot finish all 11; this is the
highest-value, production-grade slice:

1. **(≈1.5h) To-do 11 — stop fabricating regulatory declarations.** Remove the blanket literals; make
   tax-status derive from FATCA, and PEP/nationality/source-of-wealth/income investor-declared + required; fix
   the `income_slab` key bug. *Highest risk, mostly subtractive + small captures.* Critical.
2. **(≈2h) To-do 5 — transaction-2FA engine (purchase first).** New `OtpPurpose.PURCHASE_TXN`, order-bound
   `email_otps`, consent declaration at OTP entry, `TransactionConsent` persistence, OTP-entry screen; remove
   the false "SEBI Compliant" copy. *Reusable for #6/#7.* Critical.
3. **(≈1h) To-do 8 — persist T&C acceptance.** `TermsAcceptance` entity + migration; wire the existing
   onboarding checkboxes through the DTO; audit. High, cheap.
4. **(≈0.5h) To-do 1 + 3 — self-declaration capture.** Attestation control + declared columns + fix hardcoded
   `belongs_to`; add `MobileFormat` `@Pattern` (BUG-033). High, cheap, additive.

Deliberately deferred beyond a single 5h block (each needs its own block): **#4 nominee** (entity + FP
`related_parties`/`folio_defaults` + opt-out), **#9/#10** (FP `mf_folios` + holdings-report integration), and
extending 2FA to **#6/#7**. They are scoped above and ready to plan.

> **Process note (per the loaded skills):** when we move from this audit to building, each item gets the
> `brainstorming → writing-plans → subagent-driven-development/executing-plans` cycle with TDD, and a
> `deploy-checklist` pass before anything touches a real RTA submission. Nothing here should ship to
> production without verification evidence (`mvnw -B test` + the manual OTP/nominee/holdings flows).

---

## 4. Appendix — Provider capability reference (for the solutions above)

From the 4 research agents (Cybrilla POA + FP cybrillapoa docs). Confirm exact request shapes against the live
reference before coding, as the public docs are partially incomplete.

| Need | Provider support | Endpoint(s) / fields | App-side responsibility |
|------|------------------|----------------------|-------------------------|
| Email/mobile verification | **None** (no email/mobile pre-verification type) | POA pre-verification covers only readiness, PAN/name/DOB, bank | **App** captures self-declaration; sends `belongs_to` on `email_addresses`/`phone_numbers`. |
| Pre-verification | **Yes** (in use) | `POST`/`GET /poa/pre_verifications` | Already wired. |
| Nominee / nomination | **Yes** | `POST/GET/PATCH /v2/related_parties`; `PATCH /v2/mf_investment_accounts` → `folio_defaults.nominee1/2/3` + `nomineeN_allocation_percentage`; opt-out = omit nominees (+ video consent per revised-nomination) | **App** builds capture UI, allocation (sum=100, ≤3), minor-guardian set, opt-out flow; persists `related_party` ids. |
| Transaction consent | **Partial** — `consent {email, isd_code, mobile}` on order objects | `POST/PATCH /v2/mf_purchases`, `/v2/mf_redemptions`, `/v2/mf_purchase_plans` | **App** generates/sends/verifies the **SEBI 2FA OTP** (FP does NOT), then passes the consent object + stores audit evidence. |
| Mandate authorization | **Yes** (bank-side) | `POST /v2/mandates`, `/v2/mandates/{id}/authorize` → `token_url` (NACH/UPI redirect) | Redirect + poll `CREATED→SUBMITTED→APPROVED`. **Not** a substitute for the per-txn OTP. |
| Folio number | **Yes** | `GET /v2/mf_folios` → `number` (+ investor/payout metadata; **no units**) | Display folio number on redemption/portfolio screens. |
| Holdings / units / balance | **Yes** (authoritative) | `GET /api/oms/reports/holdings` / `GET /api/oms/investment_accounts/:id/holdings` → `units`, `redeemable_units`, `market_value.amount/redeemable_amount`, `invested_value.amount`, `nav.value`, `as_on`; `GET /v2/mf_redemptions/summary` for instant limits; `POST /v2/transactions/reports/capital_gains` | **App** must use these for units/balance — **do not** sum local orders. 100-folio response cap → paginate. Data is as-of the RTA feed (`as_on`), not intraday. |
