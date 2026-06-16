# Rapid Page Build Playbook

Use when shipping a new or broken Platizio page under deadline. **Always pair with `cybrilla-boss`** for any Cybrilla-backed screen.

## 7-step build loop (target: one page per session)

| # | Step | Owner | Output |
|---|------|-------|--------|
| 1 | Name page + route | achilles | Route path in `App.tsx` |
| 2 | API contract | cybrilla-boss | Endpoints + FP/POA steps from [page-cybrilla-map.md](../../cybrilla-boss/references/page-cybrilla-map.md) |
| 3 | Backend gap | achilles | Controller method or extend existing service |
| 4 | DTO + service | achilles | Request/response types aligned with frontend |
| 5 | React view | achilles | Page in `src/views/`, `apiFetch` calls |
| 6 | States + UX | achilles | loading, error, empty, success, disabled submits |
| 7 | Smoke test | both | Sandbox row + manual steps |

## Frontend conventions (match existing views)

**Paths**

```
src/views/MyPage.tsx          — page component
src/config/api.ts             — apiFetch, API_BASE_URL, BACKEND_ORIGIN
src/App.tsx                   — Route registration
```

**Page skeleton** (follow `Portfolio.tsx`, `InvestorTransaction.tsx`):

```tsx
import React, { useState, useEffect, useCallback } from 'react';
import { apiFetch } from '../config/api';

type MyPageProps = { userData?: { distributorId?: string } };

export default function MyPage({ userData }: MyPageProps) {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [data, setData] = useState<MyDto | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await apiFetch(`/my-resource?distributorId=${userData?.distributorId}`);
      if (!res.ok) throw new Error(await res.text());
      setData(await res.json());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [userData?.distributorId]);

  useEffect(() => { void load(); }, [load]);

  if (loading) return <div className="p-6 text-slate-500">Loading…</div>;
  if (error) return <div className="p-6 text-red-600">{error}</div>;
  if (!data) return <div className="p-6 text-slate-500">No data</div>;

  return (/* page JSX */);
}
```

**Rules**

- Use `apiFetch` — not raw axios to Cybrilla.
- Investor payment/consent URLs: `BACKEND_ORIGIN` + `/investor-actions/{token}`.
- Disable buttons while `submitting` or upstream pending.
- Search/filter: client-side for small lists; `?page=&size=` for catalogue pages (see `Ledger.tsx`).
- Reuse Tailwind + Lucide patterns from sibling views.

## Backend conventions (match existing stack)

**Add a read endpoint**

```
controller/MyController.java   — @GetMapping, thin
service/MyService.java         — business logic
dto/MyResponse.java            — stable FE contract
```

**Add a Cybrilla-backed write**

1. Read cybrilla-boss contract for FP vs POA audience.
2. Call via `RealCybrillaClient` + `executeWithTenantTokenRetry` / `executeWithPoaTokenRetry`.
3. Persist external IDs on domain entity.
4. Return masked/minimal DTO to frontend.

**Flyway** only when new columns/tables required — name `V{next}__short_description.sql`.

## Route registration

In `App.tsx`, inside `<Route element={<AppLayout ...>}>`:

```tsx
<Route path="/distributor/my-page" element={<MyPage userData={userData} />} />
```

Add nav link in layout/sidebar if the page is user-facing.

## Invoke pattern (fastest)

```
@.cursor/agents/achilles.md @.cursor/agents/cybrilla-boss.md
Build /distributor/my-page: [one-line goal]
```

achilles implements; cybrilla-boss supplies API steps first when Cybrilla is involved.

## Pre-ship checklist

- [ ] Backend on **8081** only (one JVM)
- [ ] `VITE_API_BASE_URL` or dev proxy `/api/v1` (cookies survive refresh)
- [ ] No secrets in React or console logs
- [ ] Cybrilla flow tested with sandbox row from cybrilla-boss
- [ ] Error messages user-friendly; stable `errorCode` for support

## Debug order (when page breaks)

1. Browser Network tab → Platizio response body
2. Backend log on 8081
3. cybrilla-boss → correct audience + endpoint?
4. [pending-work.md](../../cybrilla-boss/references/pending-work.md) → known issue?
5. Stale JVM on 8081? Restart once.
