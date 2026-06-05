# FP-Cybrilla POA Gateway Flows

Primary source: https://docs.fintechprimitives.com/fp-cybrillapoa-gateway/overview/

## Two Surfaces

The direct Cybrilla POA protocol is asynchronous and ONDC-shaped: action calls include `/search`, `/select`, `/init`, `/confirm`, `/status`, and `/update`, with matching `on_action` callbacks.

This application primarily uses the Fintech Primitives gateway surface. FP exposes tenant-scoped REST resources under `/v2/...` and orchestrates the Cybrilla POA route behind those resources. Keep these models separate.

## Identity and Onboarding

For the gateway route, collect and persist:

- investor profile
- address, including `nature`
- bank account
- email address
- mobile number
- MF investment account before purchase ordering

The current FP gateway guide says early KYC and bank-account checks can improve order placement because Cybrilla POA checks KYC and BAV while processing orders.

## KYC Application

Use FP KYC requests when readiness indicates that a fresh KYC application is required.

- Create and fetch KYC requests with `/v2/kyc_requests`.
- Treat `requirements.fields_needed` as the provider-owned checklist.
- Expect asynchronous lifecycle states such as `pending`, `esign_required`, `submitted`, `successful`, `rejected`, and `expired`.
- Use identity documents for Aadhaar fetch and proof attachment workflows. Confirm the exact current request payload before implementation.

## Purchase Order Lifecycle

For the `cybrillapoa` gateway:

1. Create an MF purchase. The order enters review.
2. Wait for asynchronous review completion. A passing order becomes actionable for consent; a failing order moves to a failed state.
3. Collect investor consent and update the order.
4. Create payment at the point required by the current flow.
5. Confirm the order for submission.
6. Redirect to a provider `token_url` or use the supported custom UPI flow.
7. Consume payment and order events, retaining fetch-based recovery.

The newer FP overview and payment-specific guides must be checked together before implementing sequencing because payment creation timing depends on the chosen checkout path.

## SIPs and Mandates

For SIP or mandate work, browse the mandate use-case guide and the exact API pages. Persist mandate IDs and state. Do not create a mandate-backed payment until the provider state is eligible. Keep first-installment behavior explicit instead of inferring it from ordinary lump-sum purchases.

## Redemptions

The gateway overview states that Cybrilla POA redemptions are limited to folios created through the Cybrilla POA gateway. Expect review, consent, confirmation, submission, and a final success or failure transition.

## Payment Retry

When a payment fails but the order remains submitted, create a new payment attempt for the same eligible order rather than recreating or reconfirming the order. Persist each payment ID and allow only one pending attempt for an order at a time. Confirm current rules in the payment retry guide before coding.
