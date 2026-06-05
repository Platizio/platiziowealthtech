# Official Documentation Index

Use these official pages as the source of truth. Browse the exact page needed for the task because provider behavior and capability limits may change.

## Cybrilla POA

- Introduction and asynchronous ONDC model: https://poa.cybrilla.com/docs/introduction
- Getting started: https://poa.cybrilla.com/docs/getting-started
- Direct POA transaction API: https://poa.cybrilla.com/docs/api
- Schema navigation: https://poa.cybrilla.com/docs/schema
- Current direct-POA capabilities: https://poa.cybrilla.com/docs/capabilities
- Pre-verification API: https://poa.cybrilla.com/docs/additional-apis/pre-verifications

Use the navigation from the pre-verification page for POA authentication, lookup, files, and KYC Forms pages. Confirm the current URL in the browser before citing or implementing them.

## Fintech Primitives

- API reference supplied for this integration: https://fintechprimitives.com/docs/api/cybrillapoa/#introduction
- Current FP-Cybrilla POA Gateway overview: https://docs.fintechprimitives.com/fp-cybrillapoa-gateway/overview/
- Gateway capabilities: https://docs.fintechprimitives.com/fp-cybrillapoa-gateway/capabilities
- Sandbox simulation: https://docs.fintechprimitives.com/fp-cybrillapoa-gateway/sandbox-simulation
- Payment retry flow: https://docs.fintechprimitives.com/fp-cybrillapoa-gateway/payment-retry
- Custom checkout flow: https://docs.fintechprimitives.com/fp-cybrillapoa-gateway/custom-checkout
- Mandate payment use cases: https://docs.fintechprimitives.com/fp-cybrillapoa-gateway/mandate-payments-usecases
- Collecting payments independently: https://docs.fintechprimitives.com/fp-cybrillapoa-gateway/collecting-payment-on-your-own
- KYC check guide: https://docs.fintechprimitives.com/identity/kyc-check/
- KYC request guide: https://docs.fintechprimitives.com/identity/kyc-request
- Bank verification guide: https://docs.fintechprimitives.com/identity/verification/perform-bank-account-verification/

## Selection Rule

- Prefer POA docs for `/poa/...` pre-verification and direct ONDC action behavior.
- Prefer FP docs for the `/v2/...` gateway integration used by this application.
- When an older API reference and a newer FP guide disagree, identify the conflict, browse the exact endpoint page, and tell the user what remains uncertain.
