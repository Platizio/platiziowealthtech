# Platizio Frontend Integration Map

The known frontend workspace is:

`C:\Users\HP\Downloads\platiziowealthtech-Front_End for now\platiziowealthtech-Front_End_sample`

Read its live files before editing. It often contains active uncommitted work.

## Stack

- Vite
- React
- TypeScript
- `axios` and `fetch` wrapper utilities
- `react-hook-form`, `zod`, and Lucide icons

## Key Files

- API boundary: `src/config/api.ts`
- Investor onboarding: `src/views/InvestorOnboarding.tsx`
- Pre-verification parsing and UI decisions: `src/utils/kycPreVerification.ts`
- Investor KYC management: `src/views/InvestorMgmt.tsx`
- Order entry: `src/views/InvestorTransaction.tsx`
- Dashboard onboarding pipeline: `src/views/Dashboard.tsx`

## Browser Boundary

The browser must call Platizio backend routes only. Do not place Cybrilla or Fintech Primitives client credentials, bearer tokens, tenant headers, or provider API calls in React code.

The frontend may receive:

- local investor and bank IDs
- masked bank-account display values
- external workflow states mapped by the backend
- retry or refresh affordances
- backend-generated redirect URLs or provider `token_url` values when investor navigation is required

Avoid exposing provider payloads wholesale. Shape stable backend DTOs for UI use when the provider response contains sensitive or noisy fields.

## Existing Onboarding Shape

`InvestorOnboarding.tsx` currently:

- captures investor identity and contact data
- calls backend KYC check routes
- parses POA `pre_verification` objects
- supports refresh for asynchronous POA checks
- starts fresh KYC requests when readiness requires it
- captures bank data and uploads local investor documents
- resumes onboarding from persisted backend state

`src/utils/kycPreVerification.ts` currently maps:

- top-level `accepted` to an in-progress UI
- readiness `verified` to continue
- `kyc_unavailable` to fresh-KYC UI
- `upstream_error` to retry UI
- demographic failures to a blocked or retry state

## Frontend Rules

- Keep sensitive values masked in logs and display surfaces.
- Remove development console payload logging before production if it includes PAN, DOB, account data, or provider objects.
- Render asynchronous status honestly. Do not show transaction success immediately after provider acceptance.
- Use a refresh action and resume-on-reload behavior for long-running checks.
- Treat redirect and postback handling as explicit product states.
- Disable duplicate submit actions while requests are in flight.
- Surface actionable failure codes with user-friendly copy while retaining the stable backend code for troubleshooting.
- Verify responsive behavior and the full onboarding path in the browser after UI changes.

## Transaction UI Gap To Check

`InvestorTransaction.tsx` currently creates a local backend order and shows a processing completion screen. Before adding payments, mandates, or provider redirects, verify the backend contract for:

- review completion
- consent collection
- payment creation
- order confirmation
- provider redirect or UPI action
- payment retry
- final webhook or polling reconciliation

Do not collapse these asynchronous provider stages into one frontend success state.
