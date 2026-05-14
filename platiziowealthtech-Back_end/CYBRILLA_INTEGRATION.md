# Cybrilla Integration Map

This file is the handoff point for wiring the real Cybrilla / Fintech Primitives APIs into the backend. Share the exact API documentation links and payload samples, and the mock adapter can be replaced without changing controllers or services.

## Current Backend Adapter

- Interface: `src/main/java/com/platizio/wealthtech/integration/CybrillaClient.java`
- Temporary implementation: `src/main/java/com/platizio/wealthtech/integration/MockCybrillaClient.java`
- Calling services:
  - `InvestorService` creates investor profiles and captures bank accounts.
  - `ProductService` refreshes product schemes.
  - `OrderService` creates purchase orders, action URLs, and redemptions.

## Environment Variables

```text
CYBRILLA_PRE_VERIFICATION_BASE_URL=https://api.sandbox.cybrilla.com
CYBRILLA_PRE_VERIFICATION_TOKEN_URL=https://s.finprim.com/v2/auth/cybrillarta/token
CYBRILLA_PRE_VERIFICATION_CLIENT_ID=<pre-verification-client-id>
CYBRILLA_PRE_VERIFICATION_CLIENT_SECRET=<pre-verification-client-secret>
CYBRILLA_TOKEN_REFRESH_BUFFER_SECONDS=120

FINPRIM_BASE_URL=https://s.finprim.com
FINPRIM_TENANT_NAME=platizio
FINPRIM_TENANT_ID=<tenant-id-header-value-if-different>
FINPRIM_TENANT_TOKEN_URL=<optional override; leave unset to use /v2/auth/{FINPRIM_TENANT_NAME}/token>
FINPRIM_TENANT_CLIENT_ID=<tenant-client-id>
FINPRIM_TENANT_CLIENT_SECRET=<tenant-client-secret>
FINPRIM_TOKEN_REFRESH_BUFFER_SECONDS=120

EXTERNAL_AUTH_TOKEN_CACHE_ENABLED=true
EXTERNAL_AUTH_TOKEN_CACHE_FILE=<optional override; defaults to user home .platizio-wealthtech/external-auth-token-cache.json>
EXTERNAL_AUTH_DEBUG_ENABLED=false
EXTERNAL_AUTH_LOG_RAW_TOKENS=false
```

## Bearer Token Handling

The backend owns all Cybrilla and Fintech Primitives credentials. Do not pass client ids,
client secrets, or provider bearer tokens to the frontend.

- Token service: `src/main/java/com/platizio/wealthtech/integration/auth/ExternalBearerTokenService.java`
- Pre-verification token: `externalBearerTokenService.getCybrillaPreVerificationAccessToken()`
- FP tenant token: `externalBearerTokenService.getFinprimTenantAccessToken()`
- Cache behavior: tokens are cached in memory and also stored in a local file under the user's home directory by default. After a backend restart, the token is reused from that local cache if it is still before the refresh time. With a 30 minute token and the default 120 second buffer, the backend reuses a token for about 28 minutes.
- Console logs: each token access logs a compact line with `status`, `token`, `new_token_in`, and `expires_in`. By default `token` is a masked SHA-256 fingerprint; with `EXTERNAL_AUTH_LOG_RAW_TOKENS=true`, `token` is the raw bearer token for temporary local debugging.
- Temporary local debugging: set `EXTERNAL_AUTH_DEBUG_ENABLED=true` to enable trigger endpoints and `EXTERNAL_AUTH_LOG_RAW_TOKENS=true` to print raw bearer tokens in the backend console. Turn both off after checking.
- Retry behavior for future API clients: if a provider call returns `401`, invalidate the relevant cached token, fetch once again, and retry that provider request one time.

Future Cybrilla API clients should set bearer auth internally:

```java
String token = externalBearerTokenService.getCybrillaPreVerificationAccessToken();

restClient.post()
        .uri("/poa/pre_verifications")
        .headers(headers -> headers.setBearerAuth(token))
        .body(request)
        .retrieve()
        .body(ResponseType.class);
```

For Fintech Primitives tenant APIs, use `getFinprimTenantAccessToken()` and include
the `x-tenant-id` header when the FP endpoint requires it.

## Suggested Real API Implementation

Create `RealCybrillaClient` in `src/main/java/com/platizio/wealthtech/integration`.

```java
@Component
@Profile("cybrilla")
public class RealCybrillaClient implements CybrillaClient {
    private final RestClient restClient;
    private final ExternalBearerTokenService tokenService;

    public RealCybrillaClient(
            CybrillaPreVerificationProperties properties,
            ExternalBearerTokenService tokenService
    ) {
        this.tokenService = tokenService;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    @Override
    public String createInvestorProfile(Investor investor) {
        // POST /investors or the exact Cybrilla endpoint
        // Map: name, email, mobile, PAN, DOB, address, distributor EUIN/ARN.
        // Return Cybrilla investor id and store it in investors.cybrilla_investor_id.
        return null;
    }

    @Override
    public void captureBankAccount(Investor investor, InvestorBankAccount bankAccount) {
        // POST /investors/{cybrillaInvestorId}/bank-accounts or exact endpoint.
        // Store the returned bank account id in investor_bank_accounts.cybrilla_bank_id.
    }

    @Override
    public List<ProductScheme> fetchProductSchemes() {
        // GET product/scheme master endpoint.
        // Upsert by product_schemes.external_scheme_code.
        return List.of();
    }

    @Override
    public String createOrder(TransactionOrder order, Investor investor) {
        // POST purchase/SIP/STP/SWP order endpoint based on transaction_type.
        // Include distributorId, investorId, productSchemeId, amount/units, payment mode.
        return null;
    }

    @Override
    public String generateInvestorActionUrl(TransactionOrder order) {
        // Use Cybrilla payment/mandate/consent URL endpoint if provided.
        return null;
    }

    @Override
    public String createRedemption(TransactionOrder order) {
        // POST redemption endpoint for the investor and scheme.
        return null;
    }
}
```

## API Mapping Needed From You

Send the Cybrilla links for:

- Authentication and token refresh.
- Investor creation or folio/profile creation.
- KYC check or KYC initiation.
- Bank account capture and penny-drop verification.
- Scheme master list.
- Purchase, SIP, and redemption order creation.
- Payment/mandate/investor action URL generation.
- Webhooks for order status, payment status, KYC status, bank verification, and redemption status.

## Webhook Architecture

Add `CybrillaWebhookController` later under `/api/v1/webhooks/cybrilla`.

- Verify webhook signature before processing.
- Store provider event id for idempotency.
- Update local status tables first.
- Write an `audit_events` entry for every material update.
- Notify the distributor only after local state is committed.

## Database Flow

- `distributors.e_uin_number` stores EUIN and is unique when present.
- `distributors.role` controls whether the row is an admin, master distributor, or sub distributor.
- `distributors.master_distributor_id` links each sub distributor to its master distributor.
- `investors.distributor_id` links investors to the distributor who owns the relationship.
- Admin can fetch all sub distributors and investors.
- Master distributor can fetch its direct sub distributors and their investors.
- Sub distributor can fetch only its own investors.
