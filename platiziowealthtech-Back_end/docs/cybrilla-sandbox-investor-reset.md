# Cybrilla / Finprim sandbox investor profile cleanup

## API limitation

Fintech Primitives exposes **GET**, **POST**, and **PATCH** for `/v2/investor_profiles`. There is **no public DELETE** endpoint and **no dedicated "sync" API**.

Platizio list endpoints (`GET /api/v1/investors`, `GET /api/v1/investors/by-distributor/{id}`) call Finprim `GET /v2/investor_profiles?type=individual` when `CYBRILLA_INVESTOR_DATA_SOURCE=cybrilla` (default), reconcile linked rows into PostgreSQL, then return the local cache to the UI. `POST /api/v1/investors/sync-from-cybrilla` is a Platizio convenience endpoint for restoring archived investors — not a Cybrilla API.

A direct `DELETE /v2/investor_profiles/{invp_...}` against the Platizio sandbox tenant returns **404**.

Dummy investor profiles created during testing therefore **remain in the Finprim tenant** until Cybrilla support resets or archives them.

## What Platizio can do locally

| Action | Endpoint | Effect |
|--------|----------|--------|
| Purge local cache | `POST /api/v1/investors/purge-local-cache` | Soft-deletes all investors for the distributor in PostgreSQL |
| Passive list/read | `GET /api/v1/investors` | Restores/updates **already linked** profiles only; does **not** import new FP profiles |
| Explicit import | `GET /api/v1/investors?syncFromCybrilla=true` or `POST /api/v1/investors/sync-from-cybrilla` | Imports unlinked FP profiles into the distributor cache |

After purging the local cache, avoid `syncFromCybrilla=true` on routine list calls or the 18 sandbox profiles will be imported again.

## Request sandbox tenant reset from Cybrilla

Email **support@cybrilla.com** (or your onboarding contact) with:

- Tenant name: `platizio`
- Environment: sandbox (`s.finprim.com`)
- Request: reset or archive all test `investor_profiles` under tenant `platizio`
- Reason: MVP sandbox cleanup after integration testing (list profile IDs if needed)

Reference profile IDs from `GET /v2/investor_profiles?type=individual` when opening the ticket.

## Verify sync in backend logs

After restarting the backend, a list or explicit sync should emit structured logs:

```
investor_profile_sync status='started' ...
investor_profile_sync status='completed' ... provider_count='18' imported='0' skipped_unlinked='18'
```

`skipped_unlinked` on passive reads is expected after a local purge when FP still holds dummy profiles.
