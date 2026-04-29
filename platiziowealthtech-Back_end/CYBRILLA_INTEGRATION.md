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
CYBRILLA_BASE_URL=https://<cybrilla-base-url>
CYBRILLA_API_KEY=<api-key-or-token>
CYBRILLA_PARTNER_ID=<partner-id-if-required>
```

## Suggested Real API Implementation

Create `RealCybrillaClient` in `src/main/java/com/platizio/wealthtech/integration`.

```java
@Component
@Profile("cybrilla")
public class RealCybrillaClient implements CybrillaClient {
    private final RestClient restClient;

    public RealCybrillaClient(CybrillaProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
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
