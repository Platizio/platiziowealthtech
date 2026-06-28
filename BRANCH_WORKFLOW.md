# Branch workflow — READ THIS before adding features

This repo (`github.com/Platizio/platiziowealthtech`) uses **branches-as-projects**: backend branches hold the Spring Boot app (in `platiziowealthtech-Back_end/`), frontend branches hold the React app. Backend and frontend are **separate, unrelated git trees by design** — they are not one branch and cannot be combined into one.

## Backend: two branches, one job each

| Branch | Role | Build features here? |
|---|---|---|
| **`investor`** | **WORKING branch** — the single, complete backend (everyone's combined work). | ✅ **YES** |
| **`Back_end`** | **LIVE DEPLOY branch** — Render auto-deploys it. | ❌ no — deploy only |

Frontend mirror: **`investor-frontend`** = working · **`Front_end`** = live deploy.

> `investor` is **not** a second copy to maintain in parallel. It is the **one** place you develop. `Back_end` only ever changes by merging `investor` into it at deploy time. Follow the one rule below and there is no duplication to keep in sync.

## Add a feature (the easy path)

```bash
git checkout investor          # backend   (investor-frontend for the FE)
# ...build it...
./mvnw -B test                 # backend   (npm run build for the FE)
git add -A && git commit -m "feat: ..." && git push
```

That's it. `investor` already holds everyone's work, so no merge surprises.

## Deploy (only when ready — Vinayak reviews money-flow first)

```bash
git checkout investor && git merge Back_end          # 1. pull any deploy-branch hotfixes in
#   ...Vinayak reviews the diff (esp. OrderService)...
git checkout Back_end && git merge investor && git push   # 2. → Render deploys
git checkout investor                                # 3. back to building
```

## THE ONE RULE (this prevents merge-hell)

**Always develop on `investor`. Never commit a feature directly to `Back_end`.**
If a hotfix ever lands on `Back_end`, immediately run `git checkout investor && git merge Back_end` so `investor` stays a superset. The painful divergence we untangled — two different `V52`/`V53` migrations, two `OrderService` rewrites — happened precisely because work was built on a branch that drifted from deploy. This rule prevents it from recurring.

## Rollback safety net

Tags `live-prod-pre-merge` (the pre-consolidation deploy state) and `feature-pre-merge` exist on origin — if a deploy ever goes wrong, `Back_end` can be reset to `live-prod-pre-merge`.
