# FP Investor Profile PATCH Rules (Platizio)

Official surface: `GET/PATCH /v2/investor_profiles` (Fintech Primitives tenant token).

## Immutable fields after first write

FP returns `400` with:

```text
{field} is already set and cannot be modified
```

Observed for **`occupation`** on synced/demo investors. FP **GET** often omits `occupation` even when it is stored server-side.

## Platizio rules (implemented)

| Operation | When | PATCH fields |
|-----------|------|--------------|
| `POST /v2/investor_profiles` | Create profile after KYC | Includes `occupation` once |
| `ensureInvestorProfileOrderReady` | First order / first MF account prep | **Never** `occupation`; only missing `source_of_wealth`, `income_slab`, `pep_details` |
| `updateInvestorProfile` | KYC sync / investor update | **Never** `occupation` on PATCH |
| Immutable retry | Any PATCH 400 | Drop field from payload and retry (`patchInvestorProfileOrderReady`) |
| Skip prep entirely | `invp_` + `mfia_` already linked at method entry | `InvestorService.ensureMfInvestmentAccount` returns without PATCH |

## Shared pre-flight (purchase + redemption)

Both `OrderService.createOrder` and `OrderService.createRedemption` call `ensureMfInvestmentAccount` before FP order/redemption APIs.

## Ops

After changing `RealCybrillaClient` or `InvestorService`, restart the **single** Spring Boot process on port **8081**. A second `spring-boot:run` while 8081 is in use leaves the old JVM serving stale code.

## Verification logs

Success:

```text
ensure_investor_profile_order_ready status='completed'
mf_investment_account_prepare status='skipped_already_linked'
```

Or immutable skip:

```text
ensure_investor_profile_order_ready status='skip_immutable_field' field='source_of_wealth'
```

Failure (stale JVM or regression):

```text
payload_fields='[id, occupation, ...]'
occupation is already set and cannot be modified
```
